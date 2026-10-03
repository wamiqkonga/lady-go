package com.ladygo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.domain.RefreshToken;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.auth.repository.RefreshTokenRepository;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.domain.EmergencyContact;
import com.ladygo.customers.domain.SavedLocation;
import com.ladygo.customers.repository.EmergencyContactRepository;
import com.ladygo.customers.repository.SavedLocationRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DevicePlatform;
import com.ladygo.notifications.domain.DeviceToken;
import com.ladygo.notifications.domain.NotificationChannelType;
import com.ladygo.notifications.domain.NotificationLog;
import com.ladygo.notifications.domain.NotificationStatus;
import com.ladygo.notifications.repository.DeviceTokenRepository;
import com.ladygo.notifications.repository.NotificationLogRepository;
import com.ladygo.payments.domain.PaymentMethod;
import com.ladygo.payments.domain.PaymentMethodType;
import com.ladygo.payments.domain.Refund;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.payments.domain.PaymentTransaction;
import com.ladygo.payments.repository.PaymentMethodRepository;
import com.ladygo.payments.repository.PaymentTransactionRepository;
import com.ladygo.payments.repository.RefundRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.TripShare;
import com.ladygo.rides.repository.TripShareRepository;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.IncidentType;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.domain.SafetyIncidentEvent;
import com.ladygo.support.repository.SafetyIncidentEventRepository;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.repository.VehicleRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Hibernate already validates every entity against the migrated schema at startup (ddl-auto=validate); these
 * tests go further and prove each entity can actually be written and read back, including geography, jsonb and
 * enum columns.
 */
class PersistenceMappingTest extends PersistenceTestSupport {

    @Autowired private SavedLocationRepository savedLocations;
    @Autowired private EmergencyContactRepository emergencyContacts;
    @Autowired private VehicleRepository vehicles;
    @Autowired private DriverDocumentRepository documents;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private PaymentMethodRepository paymentMethods;
    @Autowired private RefundRepository refunds;
    @Autowired private TripShareRepository tripShares;
    @Autowired private SafetyIncidentRepository incidents;
    @Autowired private SafetyIncidentEventRepository incidentEvents;
    @Autowired private DeviceTokenRepository deviceTokens;
    @Autowired private NotificationLogRepository notificationLogs;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private OtpRequestRepository otpRequests;
    @Autowired private ConfigEntryRepository config;

    @Test
    void userRoundTripsAndIsFoundByPhone() {
        User saved = newUser(UserRole.CUSTOMER);
        flushAndClear();

        User loaded = users.findByPhone(saved.getPhone()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void geographyColumnsKeepTheirCoordinates() {
        Customer customer = newCustomer();
        SavedLocation home = new SavedLocation();
        home.setCustomerId(customer.getId());
        home.setLabel("Home");
        home.setAddress("Rajbagh, Srinagar");
        home.setLocation(point(74.8123, 34.0722));
        savedLocations.saveAndFlush(home);
        flushAndClear();

        Point loaded = savedLocations.findByCustomerIdOrderByLabelAsc(customer.getId()).get(0).getLocation();
        assertThat(loaded.getX()).isEqualTo(74.8123, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(loaded.getY()).isEqualTo(34.0722, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(loaded.getSRID()).isEqualTo(4326);
    }

    @Test
    void jsonbPreferencesRoundTrip() {
        Customer customer = newCustomer();
        customer.setPreferences(Map.of("language", "en", "notifications", Map.of("push", true)));
        customers.saveAndFlush(customer);
        flushAndClear();

        Map<String, Object> loaded = customers.findById(customer.getId()).orElseThrow().getPreferences();
        assertThat(loaded).containsEntry("language", "en");
        assertThat(loaded.get("notifications")).isEqualTo(Map.of("push", true));
    }

    @Test
    void nearbyOnlineDriversAreFoundWithinRadiusAndOrderedByDistance() {
        City city = newCity();
        // Search centre: Lal Chowk. Distances below are approximate straight-line figures.
        double lon = 74.8097, lat = 34.0717;
        Driver near = newOnlineDriver(city, 74.8150, 34.0750);   // ~0.7 km
        Driver nearer = newOnlineDriver(city, 74.8100, 34.0720); // ~0.05 km
        newOnlineDriver(city, 75.0500, 34.2500);                 // ~30 km, outside the radius
        Driver offline = newDriver(city);                        // never online
        offline.setCurrentLocation(point(74.8098, 34.0718));
        drivers.saveAndFlush(offline);
        // Same spot, different city: must not be returned.
        newOnlineDriver(newCity(), 74.8098, 34.0718);
        flushAndClear();

        List<Driver> found = drivers.findNearbyOnline(city.getId(), lon, lat, 5000, 10);

        assertThat(found).extracting(Driver::getId).containsExactly(nearer.getId(), near.getId());
    }

    @Test
    void nearbyQueryHonoursTheResultLimit() {
        City city = newCity();
        for (int i = 0; i < 5; i++) {
            newOnlineDriver(city, 74.8097 + i * 0.001, 34.0717);
        }
        flushAndClear();
        assertThat(drivers.findNearbyOnline(city.getId(), 74.8097, 34.0717, 5000, 3)).hasSize(3);
    }

    @Test
    void optimisticLockingStopsALostUpdateOnBookings() {
        Booking booking = newBooking(newCustomer(), newCity(), BookingStatus.REQUESTED, null);
        flushAndClear();

        Booking stale = bookings.findById(booking.getId()).orElseThrow();
        em.detach(stale);
        Booking current = bookings.findById(booking.getId()).orElseThrow();
        current.setStatus(BookingStatus.SEARCHING_DRIVER);
        bookings.saveAndFlush(current);

        stale.setStatus(BookingStatus.SEARCHING_DRIVER);
        assertThatThrownBy(() -> bookings.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void customerBookingHistoryIsPagedNewestFirst() {
        City city = newCity();
        Customer customer = newCustomer();
        for (int i = 0; i < 3; i++) {
            newBooking(customer, city, BookingStatus.REQUESTED, null);
        }
        flushAndClear();

        var page = bookings.findByCustomerIdOrderByCreatedAtDesc(customer.getId(),
                org.springframework.data.domain.PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getCreatedAt()).isAfterOrEqualTo(page.getContent().get(1).getCreatedAt());
    }

    @Test
    void everyRemainingEntityCanBeWrittenAndReadBack() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        Ride ride = newRide(booking, driver);
        User customerUser = users.findById(customer.getUserId()).orElseThrow();

        EmergencyContact contact = new EmergencyContact();
        contact.setCustomerId(customer.getId());
        contact.setName("Sister");
        contact.setPhone(nextPhone());
        contact.setRelationship("Family");
        emergencyContacts.save(contact);

        Vehicle vehicle = new Vehicle();
        vehicle.setDriverId(driver.getId());
        vehicle.setRegistrationNumber("JK01AB1234");
        vehicle.setVehicleType(VehicleType.CAB);
        vehicle.setMake("Maruti");
        vehicle.setModel("Dzire");
        vehicles.save(vehicle);
        assertThat(vehicles.existsByRegistrationNumberIgnoreCase("jk01ab1234")).isTrue();

        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(DocumentType.LICENSE);
        document.setStorageKey("kyc/" + driver.getId() + "/license.jpg");
        documents.save(document);

        PaymentMethod method = new PaymentMethod();
        method.setCustomerId(customer.getId());
        method.setProvider("acme");
        method.setProviderToken("tok_123");
        method.setType(PaymentMethodType.CARD);
        method.setDisplayLabel("Visa ending 4242");
        method.setDefaultMethod(true);
        paymentMethods.save(method);

        PaymentTransaction payment = new PaymentTransaction();
        payment.setBookingId(booking.getId());
        payment.setCustomerId(customer.getId());
        payment.setProvider("acme");
        payment.setMethod(PaymentMode.ONLINE);
        payment.setAmount(25000);
        payment.setCurrency("INR");
        payment.setIdempotencyKey("idem-mapping");
        payments.saveAndFlush(payment);

        Refund refund = new Refund();
        refund.setTransactionId(payment.getId());
        refund.setAmount(5000);
        refund.setReason("Partial refund");
        refunds.save(refund);

        TripShare share = new TripShare();
        share.setRideId(ride.getId());
        share.setCreatedBy(customerUser.getId());
        share.setTokenHash("a".repeat(64));
        share.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        tripShares.save(share);

        SafetyIncident incident = new SafetyIncident();
        incident.setRideId(ride.getId());
        incident.setReporterId(customerUser.getId());
        incident.setType(IncidentType.SOS);
        incident.setSeverity(IncidentSeverity.CRITICAL);
        incident.setLocation(point(74.81, 34.07));
        incidents.saveAndFlush(incident);

        SafetyIncidentEvent incidentEvent = new SafetyIncidentEvent();
        incidentEvent.setIncidentId(incident.getId());
        incidentEvent.setEventType("CREATED");
        incidentEvent.setMetadata(Map.of("source", "app"));
        incidentEvents.save(incidentEvent);

        DeviceToken token = new DeviceToken();
        token.setUserId(customerUser.getId());
        token.setPlatform(DevicePlatform.ANDROID);
        token.setApp(AppType.CUSTOMER);
        token.setToken("fcm-token-1");
        deviceTokens.save(token);

        NotificationLog notification = new NotificationLog();
        notification.setUserId(customerUser.getId());
        notification.setEventType("BOOKING_ACCEPTED");
        notification.setChannel(NotificationChannelType.PUSH);
        notification.setStatus(NotificationStatus.SENT);
        notificationLogs.save(notification);

        AuditLog audit = new AuditLog();
        audit.setActorId(customerUser.getId());
        audit.setActorRole("CUSTOMER");
        audit.setAction("booking.create");
        audit.setTargetType("booking");
        audit.setTargetId(booking.getId().toString());
        audit.setMetadata(Map.of("fare", 25000));
        audit.setIp("203.0.113.7");
        auditLogs.save(audit);

        RefreshToken refresh = new RefreshToken();
        refresh.setUserId(customerUser.getId());
        refresh.setTokenHash("b".repeat(64));
        refresh.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        refreshTokens.save(refresh);

        OtpRequest otp = new OtpRequest();
        otp.setPhone(customerUser.getPhone());
        otp.setCodeHash("hashed-code");
        otp.setPurpose(OtpPurpose.LOGIN);
        otp.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
        otpRequests.save(otp);

        ConfigEntry cityOverride = new ConfigEntry();
        cityOverride.setConfigKey("driver.search.radius.m");
        cityOverride.setConfigValue("3000");
        cityOverride.setScope(ConfigScope.CITY);
        cityOverride.setCityId(city.getId());
        config.save(cityOverride);

        flushAndClear();

        assertThat(emergencyContacts.findByCustomerId(customer.getId())).hasSize(1);
        assertThat(vehicles.findByDriverId(driver.getId())).hasSize(1);
        assertThat(documents.findByDriverId(driver.getId())).extracting(DriverDocument::getType).containsExactly(DocumentType.LICENSE);
        assertThat(paymentMethods.findByCustomerId(customer.getId())).hasSize(1);
        assertThat(payments.findByIdempotencyKey("idem-mapping")).isPresent();
        assertThat(refunds.findByTransactionId(payment.getId())).hasSize(1);
        assertThat(tripShares.findByTokenHash("a".repeat(64))).isPresent();
        assertThat(incidents.findByStatusInOrderBySeverityDescCreatedAtAsc(Set.of(IncidentStatus.OPEN))).hasSize(1);
        assertThat(incidentEvents.findByIncidentIdOrderByCreatedAtAsc(incident.getId())).hasSize(1);
        assertThat(deviceTokens.findByToken("fcm-token-1")).isPresent();
        assertThat(auditLogs.findByActorIdOrderByCreatedAtDesc(customerUser.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5)).getContent())
                .extracting(AuditLog::getAction).containsExactly("booking.create");
        assertThat(refreshTokens.findByTokenHash("b".repeat(64))).isPresent();
        assertThat(otpRequests.findFirstByPhoneAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                customerUser.getPhone(), OtpPurpose.LOGIN)).isPresent();
        assertThat(config.findByConfigKeyAndScopeAndCityId("driver.search.radius.m", ConfigScope.CITY, city.getId()))
                .map(ConfigEntry::getConfigValue).contains("3000");
        assertThat(config.findByConfigKeyAndScopeAndCityIdIsNull("driver.search.radius.m", ConfigScope.GLOBAL))
                .map(ConfigEntry::getConfigValue).contains("5000");
    }

    @Test
    void globalConfigurationCannotBeDuplicated() {
        ConfigEntry duplicate = new ConfigEntry();
        duplicate.setConfigKey("driver.search.radius.m");
        duplicate.setConfigValue("1");
        duplicate.setScope(ConfigScope.GLOBAL);
        assertThatThrownBy(() -> config.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
