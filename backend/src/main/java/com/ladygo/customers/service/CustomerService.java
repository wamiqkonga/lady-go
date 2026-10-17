package com.ladygo.customers.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.ladygo.common.exception.LimitExceededException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.domain.EmergencyContact;
import com.ladygo.customers.domain.SavedLocation;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.customers.repository.EmergencyContactRepository;
import com.ladygo.customers.repository.SavedLocationRepository;
import com.ladygo.customers.web.dto.CustomerProfileResponse;
import com.ladygo.customers.web.dto.EmergencyContactRequest;
import com.ladygo.customers.web.dto.EmergencyContactResponse;
import com.ladygo.customers.web.dto.SavedLocationRequest;
import com.ladygo.customers.web.dto.SavedLocationResponse;
import com.ladygo.customers.web.dto.UpdateProfileRequest;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.service.RideSelfieService;
import com.ladygo.verification.service.SelfDeclarationService;
import com.ladygo.verification.web.dto.DeclarationResponse;
import com.ladygo.verification.web.dto.DeclarationStatusResponse;
import com.ladygo.verification.web.dto.RideSelfieResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** docs/api/customer-profile.md, docs/api/driver-onboarding.md section 4. */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final UserRepository users;
    private final SavedLocationRepository savedLocations;
    private final EmergencyContactRepository emergencyContacts;
    private final ConfigurationService config;
    private final CityRepository cities;
    private final SelfDeclarationService selfDeclarations;
    private final RideSelfieService rideSelfies;

    public CustomerService(
            CustomerRepository customers,
            UserRepository users,
            SavedLocationRepository savedLocations,
            EmergencyContactRepository emergencyContacts,
            ConfigurationService config,
            CityRepository cities,
            SelfDeclarationService selfDeclarations,
            RideSelfieService rideSelfies) {
        this.customers = customers;
        this.users = users;
        this.savedLocations = savedLocations;
        this.emergencyContacts = emergencyContacts;
        this.config = config;
        this.cities = cities;
        this.selfDeclarations = selfDeclarations;
        this.rideSelfies = rideSelfies;
    }

    @Transactional
    public DeclarationResponse acceptDeclaration(UUID userId, String declarationVersion) {
        Customer customer = resolveCustomer(userId);
        return selfDeclarations.accept(SubjectType.CUSTOMER, customer.getId(), declarationVersion);
    }

    /** The current declaration text and whether this rider has accepted it - a client has to be able to
     *  show the text before asking for acceptance (mirrors the driver-side status endpoint). */
    public DeclarationStatusResponse getDeclarationStatus(UUID userId) {
        Customer customer = resolveCustomer(userId);
        return selfDeclarations.currentStatus(SubjectType.CUSTOMER, customer.getId());
    }

    @Transactional
    public RideSelfieResponse captureRideSelfie(UUID userId, MultipartFile file) {
        Customer customer = resolveCustomer(userId);
        return rideSelfies.capture(customer.getId(), file);
    }

    public CustomerProfileResponse getProfile(UUID userId) {
        Customer customer = resolveCustomer(userId);
        User user = resolveUser(userId);
        return toProfileResponse(customer, user);
    }

    @Transactional
    public CustomerProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        Customer customer = resolveCustomer(userId);
        User user = resolveUser(userId);

        user.setName(request.name());
        if (request.email() != null && !request.email().isBlank()) {
            String normalized = request.email().toLowerCase(Locale.ROOT);
            users.findByEmailIgnoreCase(normalized)
                    .filter(existing -> !existing.getId().equals(userId))
                    .ifPresent(existing -> {
                        throw new ValidationException("That email is already in use.");
                    });
            user.setEmail(normalized);
        }
        users.save(user);

        if (request.defaultCityId() != null) {
            if (!cities.existsById(request.defaultCityId())) {
                // Without this check, an unknown city id surfaces as a raw FK-violation 500 on save
                // below instead of a clean 404 (adversarial review finding, LOW).
                throw new ResourceNotFoundException();
            }
            customer.setDefaultCityId(request.defaultCityId());
            customers.save(customer);
        }
        return toProfileResponse(customer, user);
    }

    public List<SavedLocationResponse> listSavedLocations(UUID userId) {
        Customer customer = resolveCustomer(userId);
        return savedLocations.findByCustomerIdOrderByLabelAsc(customer.getId()).stream()
                .map(CustomerService::toResponse)
                .toList();
    }

    @Transactional
    public SavedLocationResponse createSavedLocation(UUID userId, SavedLocationRequest request) {
        Customer customer = resolveCustomer(userId);
        customers.lockById(customer.getId()); // serializes concurrent create calls - see CustomerRepository#lockById.
        enforceLimit(savedLocations.countByCustomerId(customer.getId()),
                config.getInt("customer.saved_locations.max.count", 10), "saved locations");

        SavedLocation entity = new SavedLocation();
        entity.setCustomerId(customer.getId());
        entity.setLabel(request.label());
        entity.setAddress(request.address());
        entity.setLocation(GeoPoints.of(request.latitude(), request.longitude()));
        try {
            return toResponse(savedLocations.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("You already have a saved location with that label.");
        }
    }

    @Transactional
    public SavedLocationResponse updateSavedLocation(UUID userId, UUID locationId, SavedLocationRequest request) {
        Customer customer = resolveCustomer(userId);
        SavedLocation entity = savedLocations.findById(locationId)
                .filter(loc -> loc.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);

        entity.setLabel(request.label());
        entity.setAddress(request.address());
        entity.setLocation(GeoPoints.of(request.latitude(), request.longitude()));
        try {
            return toResponse(savedLocations.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("You already have a saved location with that label.");
        }
    }

    @Transactional
    public void deleteSavedLocation(UUID userId, UUID locationId) {
        Customer customer = resolveCustomer(userId);
        SavedLocation entity = savedLocations.findById(locationId)
                .filter(loc -> loc.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        savedLocations.delete(entity);
    }

    public List<EmergencyContactResponse> listEmergencyContacts(UUID userId) {
        Customer customer = resolveCustomer(userId);
        return emergencyContacts.findByCustomerId(customer.getId()).stream()
                .map(CustomerService::toResponse)
                .toList();
    }

    @Transactional
    public EmergencyContactResponse createEmergencyContact(UUID userId, EmergencyContactRequest request) {
        Customer customer = resolveCustomer(userId);
        customers.lockById(customer.getId()); // serializes concurrent create calls - see CustomerRepository#lockById.
        enforceLimit(emergencyContacts.countByCustomerId(customer.getId()),
                config.getInt("customer.emergency_contacts.max.count", 5), "emergency contacts");

        EmergencyContact entity = new EmergencyContact();
        entity.setCustomerId(customer.getId());
        entity.setName(request.name());
        entity.setPhone(request.phone());
        entity.setRelationship(request.relationship());
        try {
            return toResponse(emergencyContacts.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("That number is already an emergency contact.");
        }
    }

    @Transactional
    public EmergencyContactResponse updateEmergencyContact(
            UUID userId, UUID contactId, EmergencyContactRequest request) {
        Customer customer = resolveCustomer(userId);
        EmergencyContact entity = emergencyContacts.findById(contactId)
                .filter(contact -> contact.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);

        entity.setName(request.name());
        entity.setPhone(request.phone());
        entity.setRelationship(request.relationship());
        try {
            return toResponse(emergencyContacts.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("That number is already an emergency contact.");
        }
    }

    @Transactional
    public void deleteEmergencyContact(UUID userId, UUID contactId) {
        Customer customer = resolveCustomer(userId);
        EmergencyContact entity = emergencyContacts.findById(contactId)
                .filter(contact -> contact.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        emergencyContacts.delete(entity);
    }

    private void enforceLimit(long currentCount, int max, String resourceName) {
        if (currentCount >= max) {
            throw new LimitExceededException("You can save up to " + max + " " + resourceName + ".");
        }
    }

    private Customer resolveCustomer(UUID userId) {
        return customers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
    }

    private User resolveUser(UUID userId) {
        return users.findById(userId).orElseThrow(ResourceNotFoundException::new);
    }

    private static CustomerProfileResponse toProfileResponse(Customer customer, User user) {
        return new CustomerProfileResponse(
                customer.getId(), user.getPhone(), user.getPhoneVerifiedAt(), user.getName(), user.getEmail(),
                user.getProfilePhotoKey(), customer.getDefaultCityId(), customer.getPreferences());
    }

    private static SavedLocationResponse toResponse(SavedLocation entity) {
        return new SavedLocationResponse(
                entity.getId(), entity.getLabel(), entity.getAddress(),
                entity.getLocation().getY(), entity.getLocation().getX(), entity.getCreatedAt());
    }

    private static EmergencyContactResponse toResponse(EmergencyContact entity) {
        return new EmergencyContactResponse(
                entity.getId(), entity.getName(), entity.getPhone(), entity.getRelationship(), entity.getCreatedAt());
    }
}
