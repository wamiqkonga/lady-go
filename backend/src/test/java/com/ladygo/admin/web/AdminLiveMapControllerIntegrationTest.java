package com.ladygo.admin.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/admin/live-map: SUPPORT and above, read only, current positions only (never history). */
@AutoConfigureMockMvc
class AdminLiveMapControllerIntegrationTest extends PersistenceTestSupport {

    private static final String URL = "/api/v1/admin/live-map";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private ResultActions getAs(UserRole role) throws Exception {
        UUID userId = newUser(role).getId();
        return mockMvc.perform(get(URL).header("Authorization",
                "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15))));
    }

    private void setPositionAge(Driver driver, Duration age) {
        jdbc.update("UPDATE drivers SET current_location_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), driver.getId());
    }

    private void setAvailability(Driver driver, DriverAvailability availability) {
        jdbc.update("UPDATE drivers SET availability_status = ? WHERE id = ?", availability.name(), driver.getId());
    }

    private UUID newIncident(UUID rideId, UUID reporterId, String status, boolean withLocation) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO safety_incidents (id, ride_id, reporter_id, type, severity, status, location) "
                        + "VALUES (?, ?, ?, 'SOS', 'HIGH', ?, "
                        + (withLocation ? "ST_SetSRID(ST_MakePoint(74.81, 34.09), 4326)::geography)" : "NULL)"),
                id, rideId, reporterId, status);
        return id;
    }

    @Test
    void supportAdminAndSuperAdminCanRead() throws Exception {
        for (UserRole role : new UserRole[] {UserRole.SUPPORT, UserRole.ADMIN, UserRole.SUPER_ADMIN}) {
            getAs(role).andExpect(status().isOk()).andExpect(jsonPath("$.data.drivers").isArray());
        }
    }

    @Test
    void driverAndCustomerAreForbiddenAndAnonymousIsUnauthorized() throws Exception {
        getAs(UserRole.DRIVER).andExpect(status().isForbidden());
        getAs(UserRole.CUSTOMER).andExpect(status().isForbidden());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void listsFreshOnlineDriverWithMaskedNameAndPosition() throws Exception {
        City city = newCity();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        jdbc.update("UPDATE users SET name = 'Aisha Khan' WHERE id = ?", driver.getUserId());
        String phone = users.findById(driver.getUserId()).orElseThrow().getPhone();

        getAs(UserRole.SUPPORT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].name").value(hasItem("Aisha K.")))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].latitude").value(hasItem(34.08)))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].longitude").value(hasItem(74.80)))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].availability").value(hasItem("ONLINE")))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].ageSeconds").exists())
                .andExpect(content().string(not(containsString("Khan"))))
                .andExpect(content().string(not(containsString(phone))));
    }

    @Test
    void stalePositionsAreExcluded() throws Exception {
        City city = newCity();
        Driver fresh = newOnlineDriver(city, 74.80, 34.08);
        Driver stale = newOnlineDriver(city, 74.81, 34.09);
        setPositionAge(stale, Duration.ofMinutes(30)); // default max age is 10 minutes
        Driver unknown = newOnlineDriver(city, 74.82, 34.10);
        jdbc.update("UPDATE drivers SET current_location = NULL, current_location_at = NULL WHERE id = ?", unknown.getId());

        getAs(UserRole.ADMIN)
                .andExpect(jsonPath("$.data.maxAgeMinutes").value(10))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + fresh.getId() + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + stale.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + unknown.getId() + "')]").isEmpty());
    }

    @Test
    void maxAgeFollowsConfiguration() throws Exception {
        City city = newCity();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        setPositionAge(driver, Duration.ofMinutes(30));
        jdbc.update("UPDATE configuration SET value = '60' WHERE key = 'admin.livemap.max_age.minutes'");

        getAs(UserRole.ADMIN)
                .andExpect(jsonPath("$.data.maxAgeMinutes").value(60))
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')]").isNotEmpty());
    }

    @Test
    void offlineAndSuspendedDriversAreNotShown() throws Exception {
        City city = newCity();
        Driver offline = newOnlineDriver(city, 74.80, 34.08);
        setAvailability(offline, DriverAvailability.OFFLINE);

        getAs(UserRole.ADMIN).andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + offline.getId() + "')]").isEmpty());
    }

    @Test
    void activeRideShowsPickupDropDriverAndMaskedPeople() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        jdbc.update("UPDATE users SET name = 'Zainab Mir' WHERE id = ?", customer.getUserId());
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        setAvailability(driver, DriverAvailability.ON_RIDE);
        Booking active = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        Booking done = newBooking(customer, city, BookingStatus.COMPLETED, null);
        Booking cancelled = newBooking(customer, city, BookingStatus.CANCELLED, null);

        String ride = "$.data.rides[?(@.bookingId=='" + active.getId() + "')]";
        getAs(UserRole.SUPPORT)
                .andExpect(jsonPath(ride + ".status").value(hasItem("RIDE_STARTED")))
                .andExpect(jsonPath(ride + ".pickupLatitude").value(hasItem(34.08)))
                .andExpect(jsonPath(ride + ".dropLatitude").value(hasItem(34.13)))
                .andExpect(jsonPath(ride + ".driverId").value(hasItem(driver.getId().toString())))
                .andExpect(jsonPath(ride + ".customerName").value(hasItem("Zainab M.")))
                .andExpect(jsonPath("$.data.rides[?(@.bookingId=='" + done.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$.data.rides[?(@.bookingId=='" + cancelled.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')].availability")
                        .value(hasItem("ON_RIDE")))
                .andExpect(content().string(not(containsString("Mir"))));
    }

    @Test
    void openIncidentsAreShownWithPositionOnlyWhenOneExists() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        UUID rideId = newRide(booking, driver).getId();
        UUID withPosition = newIncident(rideId, customer.getUserId(), "OPEN", true);
        UUID withoutPosition = newIncident(rideId, customer.getUserId(), "ESCALATED", false);
        UUID resolved = newIncident(rideId, customer.getUserId(), "RESOLVED", true);

        getAs(UserRole.SUPPORT)
                .andExpect(jsonPath("$.data.incidents[?(@.incidentId=='" + withPosition + "')].latitude").value(hasItem(34.09)))
                .andExpect(jsonPath("$.data.incidents[?(@.incidentId=='" + withPosition + "')].severity").value(hasItem("HIGH")))
                .andExpect(jsonPath("$.data.incidents[?(@.incidentId=='" + withoutPosition + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.incidents[?(@.incidentId=='" + withoutPosition + "')].latitude").value(hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.incidents[?(@.incidentId=='" + resolved + "')]").isEmpty());
    }

    @Test
    void neverLeaksLocationHistory() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        UUID rideId = newRide(booking, driver).getId();
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                    + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, 31.4321), 4326)::geography, now())",
                    rideId, driver.getId(), 71.1234 + i);
        }

        getAs(UserRole.ADMIN)
                .andExpect(jsonPath("$.data.drivers[?(@.driverId=='" + driver.getId() + "')]", hasSize(1)))
                .andExpect(content().string(not(containsString("71.1234"))))
                .andExpect(content().string(not(containsString("31.4321"))))
                .andExpect(content().string(not(containsString("history"))))
                .andExpect(content().string(not(containsString("trail"))));
    }

    @Test
    void responseIsBounded() throws Exception {
        City city = newCity();
        for (int i = 0; i < 205; i++) {
            newOnlineDriver(city, 74.80 + i * 0.0001, 34.08);
        }

        getAs(UserRole.ADMIN)
                .andExpect(jsonPath("$.data.drivers", hasSize(200)))
                .andExpect(jsonPath("$.data.limit").value(200))
                .andExpect(jsonPath("$.data.driversTruncated").value(true))
                .andExpect(jsonPath("$.data.ridesTruncated").value(equalTo(false)));
    }
}
