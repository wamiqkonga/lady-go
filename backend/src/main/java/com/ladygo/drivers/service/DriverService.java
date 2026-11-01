package com.ladygo.drivers.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.domain.ReverificationReason;
import com.ladygo.drivers.domain.VerificationReason;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.web.dto.DriverDocumentResponse;
import com.ladygo.drivers.web.dto.DriverProfileResponse;
import com.ladygo.drivers.web.dto.VehicleRequest;
import com.ladygo.drivers.web.dto.VehicleResponse;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.storage.FileStorage;
import com.ladygo.storage.FileStorageException;
import com.ladygo.storage.UploadValidator;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.repository.VehicleRepository;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.service.SelfDeclarationService;
import com.ladygo.verification.web.dto.DeclarationResponse;
import com.ladygo.verification.web.dto.DeclarationStatusResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** docs/api/driver-onboarding.md sections 0/3. */
@Service
public class DriverService {

    private final DriverRepository drivers;
    private final DriverDocumentRepository documents;
    private final VehicleRepository vehicles;
    private final CityRepository cities;
    private final FileStorage fileStorage;
    private final ConfigurationService config;
    private final SelfDeclarationService selfDeclarations;
    private final DriverReverificationService reverificationService;

    public DriverService(
            DriverRepository drivers,
            DriverDocumentRepository documents,
            VehicleRepository vehicles,
            CityRepository cities,
            FileStorage fileStorage,
            ConfigurationService config,
            SelfDeclarationService selfDeclarations,
            DriverReverificationService reverificationService) {
        this.drivers = drivers;
        this.documents = documents;
        this.vehicles = vehicles;
        this.cities = cities;
        this.fileStorage = fileStorage;
        this.config = config;
        this.selfDeclarations = selfDeclarations;
        this.reverificationService = reverificationService;
    }

    /** Idempotent - a repeat call for an existing driver just returns the existing profile. */
    @Transactional
    public DriverProfileResponse bootstrapProfile(UUID userId, UUID cityId) {
        Driver existing = drivers.findByUserId(userId).orElse(null);
        if (existing != null) {
            return toProfileResponse(existing);
        }
        if (!cities.existsById(cityId)) {
            throw new ResourceNotFoundException();
        }
        Driver driver = new Driver();
        driver.setUserId(userId);
        driver.setCityId(cityId);
        return toProfileResponse(drivers.saveAndFlush(driver));
    }

    public DriverProfileResponse getProfile(UUID userId) {
        return toProfileResponse(resolveDriver(userId));
    }

    private static final int MAX_DOCUMENT_NUMBER_LENGTH = 100;

    @Transactional
    public DriverDocumentResponse uploadDocument(
            UUID userId, DocumentType type, MultipartFile file, String number, LocalDate expiresAt) {
        if (expiresAt != null && expiresAt.isBefore(LocalDate.now())) {
            throw new ValidationException("expiresAt cannot be in the past - this document has already expired.");
        }
        // Security review finding: document_number is varchar(100) (V28) but nothing checked that
        // server-side before this - an over-length value reached fileStorage.upload (an 8 MiB file
        // already written) before failing on the column's own length constraint with an unhandled
        // DataIntegrityViolationException (500, and an orphaned file no retention job could see,
        // since the DB row never committed). Checked here, before the upload ever starts.
        if (number != null && number.length() > MAX_DOCUMENT_NUMBER_LENGTH) {
            throw new ValidationException("number must be at most " + MAX_DOCUMENT_NUMBER_LENGTH + " characters.");
        }
        Driver driver = resolveDriver(userId);
        markPendingReviewIfNotSubmitted(driver);
        DriverDocument saved = storeDocument(driver.getId(), type, file, VerificationReason.INITIAL, number, expiresAt);
        return toDocumentResponse(saved);
    }

    public List<DriverDocumentResponse> listDocuments(UUID userId) {
        Driver driver = resolveDriver(userId);
        return documents.findByDriverId(driver.getId()).stream().map(DriverService::toDocumentResponse).toList();
    }

    @Transactional
    public DriverDocumentResponse uploadSelfie(UUID userId, MultipartFile file) {
        Driver driver = resolveDriver(userId);
        markPendingReviewIfNotSubmitted(driver);
        // if/== rather than switch-on-enum: javac compiles an enum switch via an ordinal()-indexed
        // jump table, and ordinal() is banned outright in this codebase's production code (see
        // ArchitectureTest) - not because this particular mapping is a security boundary, but because
        // a blanket rule is simpler and more robust long-term than curating which switches "count".
        ReverificationReason pending = driver.getReverificationReason();
        VerificationReason reason;
        if (pending == ReverificationReason.REPORTED) {
            reason = VerificationReason.REPORTED;
        } else if (pending == ReverificationReason.RANDOM_REVERIFICATION) {
            reason = VerificationReason.RANDOM_REVERIFICATION;
        } else {
            reason = VerificationReason.INITIAL;
        }
        DriverDocument saved = storeDocument(driver.getId(), DocumentType.SELFIE, file, reason, null, null);
        return toDocumentResponse(saved);
    }

    @Transactional
    public DeclarationResponse acceptDeclaration(UUID userId, String declarationVersion) {
        Driver driver = resolveDriver(userId);
        markPendingReviewIfNotSubmitted(driver);
        return selfDeclarations.accept(SubjectType.DRIVER, driver.getId(), declarationVersion);
    }

    public DeclarationStatusResponse getDeclarationStatus(UUID userId) {
        Driver driver = resolveDriver(userId);
        return selfDeclarations.currentStatus(SubjectType.DRIVER, driver.getId());
    }

    @Transactional
    public VehicleResponse upsertVehicle(UUID userId, VehicleRequest request) {
        Driver driver = resolveDriver(userId);
        if (request.vehicleType() != VehicleType.BIKE) {
            throw new ValidationException("Only two-wheelers can be registered right now.");
        }
        if (request.manufactureYear() != null) {
            int maxAgeYears = config.getInt("vehicle.max_age.years", 8);
            int oldestAllowed = Year.now().getValue() - maxAgeYears;
            if (request.manufactureYear() < oldestAllowed) {
                throw new ValidationException(
                        "Vehicles older than " + maxAgeYears + " years are not accepted.");
            }
        }

        markPendingReviewIfNotSubmitted(driver);
        Vehicle vehicle = vehicles.findByDriverId(driver.getId()).stream().findFirst().orElseGet(Vehicle::new);
        vehicle.setDriverId(driver.getId());
        vehicle.setRegistrationNumber(request.registrationNumber());
        vehicle.setVehicleType(request.vehicleType());
        vehicle.setMake(request.make());
        vehicle.setModel(request.model());
        vehicle.setColor(request.color());
        if (request.manufactureYear() != null) {
            vehicle.setManufactureYear(request.manufactureYear());
        }
        try {
            return toVehicleResponse(vehicles.saveAndFlush(vehicle));
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("That registration number is already in use.");
        }
    }

    public VehicleResponse getVehicle(UUID userId) {
        Driver driver = resolveDriver(userId);
        Vehicle vehicle = vehicles.findByDriverId(driver.getId()).stream().findFirst()
                .orElseThrow(ResourceNotFoundException::new);
        return toVehicleResponse(vehicle);
    }

    private DriverDocument storeDocument(
            UUID driverId, DocumentType type, MultipartFile file, VerificationReason reason,
            String number, LocalDate expiresAt) {
        long maxSize = config.getInt("verification.upload.max_size.bytes", 8_388_608);
        UploadValidator.validateImage(file, maxSize);

        String key = "driver-documents/" + UUID.randomUUID();
        try {
            fileStorage.upload(key, file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }

        DriverDocument document = new DriverDocument();
        document.setDriverId(driverId);
        document.setType(type);
        document.setStorageKey(key);
        document.setReason(reason);
        document.setDocumentNumber(number);
        document.setExpiresAt(expiresAt);
        return documents.saveAndFlush(document);
    }

    /** First meaningful onboarding action moves a driver from NOT_SUBMITTED into the review queue. */
    private void markPendingReviewIfNotSubmitted(Driver driver) {
        if (driver.getVerificationStatus() == DriverVerificationStatus.NOT_SUBMITTED) {
            driver.setVerificationStatus(DriverVerificationStatus.PENDING_REVIEW);
            drivers.save(driver);
        }
    }

    private Driver resolveDriver(UUID userId) {
        return drivers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
    }

    private static DriverProfileResponse toProfileResponse(Driver driver) {
        return new DriverProfileResponse(driver.getId(), driver.getCityId(), driver.getVerificationStatus());
    }

    private static DriverDocumentResponse toDocumentResponse(DriverDocument document) {
        return new DriverDocumentResponse(document.getId(), document.getType(), document.getStatus(),
                document.getDocumentNumber(), document.getExpiresAt(),
                document.getReviewNote(), document.getReviewedAt(), document.getCreatedAt());
    }

    private static VehicleResponse toVehicleResponse(Vehicle vehicle) {
        return new VehicleResponse(vehicle.getId(), vehicle.getRegistrationNumber(), vehicle.getVehicleType(),
                vehicle.getMake(), vehicle.getModel(), vehicle.getColor(), vehicle.getManufactureYear(),
                vehicle.getVerificationStatus());
    }
}
