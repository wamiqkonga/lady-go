package com.ladygo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.service.SelfDeclarationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Backend leak review (docs/product/admin-web-design.md section 9): the audit-log viewer renders
 * {@code AuditLog.metadata} verbatim (AdminAuditLogService.toResponse - no filtering on the read
 * side), so the only real control point is "never write a secret/token/OTP/hash/full phone/trip-share
 * token into it in the first place." There are exactly three call sites across the codebase that ever
 * write non-null metadata (grep for {@code .setMetadata(} under src/main/java, excluding
 * BookingStateMachine's unrelated BookingEvent.metadata): AdminConfigService, AdminRideOversightService,
 * AdminVerificationService. This test drives all three through a realistic flow and asserts, over
 * every AuditLog row written, that no metadata key or value matches a banned pattern - a regression
 * guard, not a fix for an existing finding (none was found; every current site already writes only a
 * type name, a free-text note, a boolean, or a config key/value/reason).
 */
class AdminAuditLogSensitiveDataTest extends PersistenceTestSupport {

    private static final Pattern JWT_LIKE = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$.+");
    private static final Pattern FULL_E164_PHONE = Pattern.compile("^\\+?[0-9]{10,15}$");
    private static final List<String> BANNED_KEY_SUBSTRINGS =
            List.of("secret", "token", "password", "otp", "hash", "mfa", "pin");

    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private AdminConfigService configService;
    @Autowired
    private ConfigEntryRepository configEntries;
    @Autowired
    private AdminRideOversightService rideOversightService;
    @Autowired
    private RideFlagRepository rideFlags;
    @Autowired
    private AdminVerificationService verificationService;
    @Autowired
    private DriverDocumentRepository driverDocuments;
    @Autowired
    private SelfDeclarationService selfDeclarations;

    private DriverDocument pendingDocument(Driver driver, DocumentType type) {
        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(type);
        document.setStorageKey("k-" + UUID.randomUUID());
        return driverDocuments.saveAndFlush(document);
    }

    @Test
    void noAuditLogEntryEverCarriesASecretTokenOtpHashOrFullPhoneNumber() {
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();
        UUID adminId = newUser(UserRole.ADMIN).getId();

        // CONFIGURATION_CHANGED: key/oldValue/newValue/reason only.
        var entry = configEntries.findByConfigKeyAndScopeAndCityIdIsNull(
                "driver.search.radius.m", com.ladygo.config.domain.ConfigScope.GLOBAL).orElseThrow();
        configService.update(entry.getId(), "6000", "sensitive-data regression test", superAdminId, "SUPER_ADMIN", "127.0.0.1");

        // RIDE_FLAG_REVIEWED: flag type + the admin's free-text note only.
        City city = newCity();
        Customer customer = newCustomer();
        Driver flagDriver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, flagDriver.getId());
        RideFlag flag = new RideFlag();
        flag.setBookingId(booking.getId());
        flag.setCustomerId(booking.getCustomerId());
        flag.setDriverId(flagDriver.getId());
        flag.setType(RideFlagType.RIDER_SAFETY_CANCEL);
        flag.setRaisedBy(com.ladygo.common.domain.ActorType.CUSTOMER);
        flag = rideFlags.saveAndFlush(flag);
        rideOversightService.reviewFlag(flag.getId(), adminId, "ADMIN", "reviewed for the sensitive-data test", "127.0.0.1");

        // DRIVER_APPROVED: a boolean (policeVerificationRequired) only.
        Driver approvalDriver = newDriver(city);
        Vehicle vehicle = newVehicle(approvalDriver);
        selfDeclarations.accept(SubjectType.DRIVER, approvalDriver.getId(), "driver-v2");
        for (DocumentType type : List.of(DocumentType.LICENSE, DocumentType.VEHICLE_REGISTRATION,
                DocumentType.VEHICLE_PHOTO, DocumentType.INSURANCE, DocumentType.SELFIE)) {
            DriverDocument document = pendingDocument(approvalDriver, type);
            verificationService.approveDocument(document.getId(), adminId, "ADMIN", "ok", "127.0.0.1");
        }
        verificationService.approveVehicle(vehicle.getId(), adminId, "ADMIN", "127.0.0.1");
        verificationService.approveDriver(approvalDriver.getId(), adminId, "ADMIN", "127.0.0.1");

        List<AuditLog> written = auditLogs.findAll();
        assertThat(written).extracting(AuditLog::getAction)
                .contains("CONFIGURATION_CHANGED", "RIDE_FLAG_REVIEWED", "DRIVER_APPROVED");

        for (AuditLog log : written) {
            Map<String, Object> metadata = log.getMetadata();
            if (metadata == null) continue;
            for (var mapEntry : metadata.entrySet()) {
                String key = mapEntry.getKey();
                Object value = mapEntry.getValue();
                String valueText = String.valueOf(value);

                assertThat(BANNED_KEY_SUBSTRINGS.stream().anyMatch(banned -> key.toLowerCase().contains(banned)))
                        .as("audit log action=%s has a banned metadata key '%s'", log.getAction(), key)
                        .isFalse();
                assertThat(JWT_LIKE.matcher(valueText).matches())
                        .as("audit log action=%s metadata key '%s' looks like a JWT", log.getAction(), key)
                        .isFalse();
                assertThat(BCRYPT_HASH.matcher(valueText).matches())
                        .as("audit log action=%s metadata key '%s' looks like a bcrypt hash", log.getAction(), key)
                        .isFalse();
                assertThat(FULL_E164_PHONE.matcher(valueText).matches())
                        .as("audit log action=%s metadata key '%s' looks like a full, unmasked phone number",
                                log.getAction(), key)
                        .isFalse();
            }
        }
    }
}
