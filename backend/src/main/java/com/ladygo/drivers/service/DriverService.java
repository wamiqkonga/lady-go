package com.ladygo.drivers.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
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
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.RegistrationNumbers;
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
    private final UserRepository users;
    private final AuditLogRepository auditLogs;

    public DriverService(
            DriverRepository drivers,
            DriverDocumentRepository documents,
            VehicleRepository vehicles,
            CityRepository cities,
            FileStorage fileStorage,
            ConfigurationService config,
            SelfDeclarationService selfDeclarations,
            DriverReverificationService reverificationService,
            UserRepository users,
            AuditLogRepository auditLogs) {
        this.drivers = drivers;
        this.documents = documents;
        this.vehicles = vehicles;
        this.cities = cities;
        this.fileStorage = fileStorage;
        this.config = config;
        this.selfDeclarations = selfDeclarations;
        this.reverificationService = reverificationService;
        this.users = users;
        this.auditLogs = auditLogs;
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

    /**
     * Sets the signed-in captain's full name (trimmed, validated by {@link DriverFullName}). The first set and every
     * change that alters the name are audit-logged; the entry names the user but never the name, the old name or the
     * phone, and the name is never written to an application log.
     */
    @Transactional
    public DriverProfileResponse updateName(UUID userId, String rawName, String clientIp) {
        String name = DriverFullName.normalise(rawName);
        Driver driver = resolveDriver(userId);
        User user = users.findById(userId).orElseThrow(ResourceNotFoundException::new);
        String previous = user.getName();
        boolean hadName = previous != null && !previous.isBlank();
        // Reviewers matched the documents to the name they saw, so once a decision has been made (approved,
        // rejected, suspended) a captain cannot rename themselves; support corrects it. Giving a first name
        // is always allowed, so existing captains without one can still complete their profile.
        DriverVerificationStatus status = driver.getVerificationStatus();
        boolean editable = status == DriverVerificationStatus.NOT_SUBMITTED
                || status == DriverVerificationStatus.PENDING_REVIEW;
        if (hadName && !name.equals(previous) && !editable) {
            throw new ValidationException("Your name can no longer be changed here. Contact support to correct it.");
        }
        if (!name.equals(previous)) {
            user.setName(name);
            users.saveAndFlush(user);
            AuditLog entry = new AuditLog();
            entry.setActorId(userId);
            entry.setActorRole(user.getRole().name());
            entry.setAction(hadName ? "DRIVER_NAME_CHANGED" : "DRIVER_NAME_SET");
            entry.setTargetType("users");
            entry.setTargetId(userId.toString());
            entry.setIp(clientIp);
            auditLogs.save(entry);
        }
        return toProfileResponse(driver);
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

    /** The captain's own uploaded image, so the wizard can show what was sent. Only the owner can read it
     *  (anyone else's id, a purged file and an unknown id all look like "not found"). Served through the
     *  authenticated API rather than a storage link, so the file is never reachable without a login. */
    public StoredImage documentContent(UUID userId, UUID documentId) {
        Driver driver = resolveDriver(userId);
        DriverDocument document = documents.findById(documentId)
                .filter(d -> d.getDriverId().equals(driver.getId()))
                .filter(d -> d.getContentDeletedAt() == null)
                .orElseThrow(ResourceNotFoundException::new);
        byte[] bytes = fileStorage.download(document.getStorageKey());
        boolean png = bytes.length > 3 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        return new StoredImage(bytes, png ? "image/png" : "image/jpeg");
    }

    public record StoredImage(byte[] bytes, String contentType) {
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

        String registrationNumber = RegistrationNumbers.normalise(request.registrationNumber());

        markPendingReviewIfNotSubmitted(driver);
        Vehicle vehicle = vehicles.findByDriverId(driver.getId()).stream().findFirst().orElseGet(Vehicle::new);
        vehicle.setDriverId(driver.getId());
        vehicle.setRegistrationNumber(registrationNumber);
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

    private DriverProfileResponse toProfileResponse(Driver driver) {
        String name = users.findById(driver.getUserId()).map(User::getName)
                .filter(n -> !n.isBlank()).orElse(null);
        return new DriverProfileResponse(driver.getId(), driver.getCityId(), driver.getVerificationStatus(), name);
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
