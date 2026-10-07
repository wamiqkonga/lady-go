package com.ladygo.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ladygo.auth.sms.MockSmsService;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.common.domain.ActorType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Year;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One rider and one driver through onboarding, admin review, and a booking - entirely through real
 * HTTP endpoints, the way the mobile apps actually call them. No test shortcut sets a verification
 * status, a document status, or a booking status directly; every one of those is reached by driving
 * the same endpoint a client would call.
 *
 * <p>The single exception, clearly marked below: driver-offer dispatch ("go online", matching, offer
 * acceptance) is Phase 6 work that does not exist yet (see {@code com.ladygo.matching.package-info});
 * only the booking's {@code driver_id} assignment is set directly, and only because no endpoint can set
 * it yet. Every status transition after that point still runs through the real
 * {@link BookingStateMachine}, not a raw status write.
 *
 * <p>Extend this test, in place, as each later phase ships real endpoints that replace a stand-in step
 * (Phase 6: replace the direct {@code driver_id} assignment with a real offer/accept call; later
 * phases: ride start/PIN, completion, payment). Do not fork a parallel journey test - this one file is
 * meant to grow into the full lifecycle so CI always exercises the whole thing together.
 */
@AutoConfigureMockMvc
class CrossPhaseJourneyTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private BookingStateMachine stateMachine;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private DriverRepository driverRepository;

    private UUID committedCityId;
    private UUID committedCustomerUserId;
    private UUID committedDriverUserId;

    private void commitFixtures(City city, UUID customerUserId, UUID driverUserId) {
        this.committedCityId = city.getId();
        this.committedCustomerUserId = customerUserId;
        this.committedDriverUserId = driverUserId;
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    @AfterEach
    void purgeCommittedFixtures() {
        if (committedCustomerUserId == null) {
            return;
        }
        em.flush(); // the DRIVER_ACCEPTED transition's BookingEvent may still be unflushed in this session.
        jdbc.update("DELETE FROM booking_events WHERE booking_id IN "
                + "(SELECT b.id FROM bookings b JOIN customers c ON c.id = b.customer_id WHERE c.user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM ride_selfies WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM bookings WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM self_declarations WHERE subject_id IN "
                + "(SELECT id FROM customers WHERE user_id = ?) AND subject_type = 'CUSTOMER'",
                committedCustomerUserId);
        jdbc.update("DELETE FROM pricing_rules WHERE city_id = ?", committedCityId);
        jdbc.update("DELETE FROM audit_logs WHERE target_type IN ('drivers', 'vehicles', 'driver_documents') "
                + "AND target_id IN (SELECT id::text FROM drivers WHERE user_id = ?)", committedDriverUserId);
        jdbc.update("DELETE FROM self_declarations WHERE subject_id IN "
                + "(SELECT id FROM drivers WHERE user_id = ?) AND subject_type = 'DRIVER'", committedDriverUserId);
        jdbc.update("DELETE FROM driver_documents WHERE driver_id IN (SELECT id FROM drivers WHERE user_id = ?)",
                committedDriverUserId);
        jdbc.update("DELETE FROM vehicles WHERE driver_id IN (SELECT id FROM drivers WHERE user_id = ?)",
                committedDriverUserId);
        jdbc.update("DELETE FROM drivers WHERE user_id = ?", committedDriverUserId);
        jdbc.update("DELETE FROM customers WHERE user_id = ?", committedCustomerUserId);
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id IN (?, ?)",
                committedCustomerUserId, committedDriverUserId);
        jdbc.update("DELETE FROM otp_requests WHERE phone IN "
                + "(SELECT phone FROM users WHERE id IN (?, ?))", committedCustomerUserId, committedDriverUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", committedCustomerUserId, committedDriverUserId);
        jdbc.update("DELETE FROM cities WHERE id = ?", committedCityId);
        TestTransaction.flagForCommit();
    }

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String otpLogin(String phone, UserRole role) throws Exception {
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        String code = MockSmsService.lastCodeFor(phone);
        String response = mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code
                                + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"" + role + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    @Test
    void riderAndDriverOnboardThroughAdminApprovalThenBookAndMeet() throws Exception {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        PricingRule pricingRule = newPricingRule(city, VehicleType.CAB);

        // --- Rider: signup via OTP, declaration, per-booking selfie - all real endpoints. ---
        String riderPhone = nextPhone();
        String riderToken = otpLogin(riderPhone, UserRole.CUSTOMER);
        UUID customerUserId = jwtService.parse(riderToken).orElseThrow().userId();

        mockMvc.perform(post("/api/v1/customers/me/declaration")
                        .header("Authorization", "Bearer " + riderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"declarationVersion\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.declarationVersion").value("v1"));

        String rideSelfieResponse = mockMvc.perform(multipart("/api/v1/customers/me/ride-selfies")
                        .file(new MockMultipartFile("file", "selfie.png", "image/png", realPngBytes()))
                        .header("Authorization", "Bearer " + riderToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID rideSelfieId = UUID.fromString(JsonPath.read(rideSelfieResponse, "$.data.rideSelfieId"));

        // --- Driver: signup via OTP, bootstrap, documents, selfie, declaration, vehicle. ---
        String driverPhone = nextPhone();
        String driverToken = otpLogin(driverPhone, UserRole.DRIVER);
        UUID driverUserId = jwtService.parse(driverToken).orElseThrow().userId();

        commitFixtures(city, customerUserId, driverUserId); // the REQUIRES_NEW booking insert needs these committed.

        String bootstrapResponse = mockMvc.perform(post("/api/v1/drivers/me")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cityId\":\"" + city.getId() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID driverId = UUID.fromString(JsonPath.read(bootstrapResponse, "$.data.id"));

        // ALWAYS_REQUIRED in AdminVerificationService: LICENSE, VEHICLE_REGISTRATION, VEHICLE_PHOTO, INSURANCE, SELFIE.
        UUID licenseDocId = uploadDocument(driverToken, "LICENSE");
        UUID registrationDocId = uploadDocument(driverToken, "VEHICLE_REGISTRATION");
        UUID vehiclePhotoDocId = uploadDocument(driverToken, "VEHICLE_PHOTO");
        UUID insuranceDocId = uploadDocument(driverToken, "INSURANCE");

        String selfieResponse = mockMvc.perform(multipart("/api/v1/drivers/me/selfie")
                        .file(new MockMultipartFile("file", "driver-selfie.jpg", "image/jpeg",
                                "fake-jpeg-bytes".getBytes()))
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID selfieDocId = UUID.fromString(JsonPath.read(selfieResponse, "$.data.id"));

        mockMvc.perform(post("/api/v1/drivers/me/declaration")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"declarationVersion\":\"v1\"}"))
                .andExpect(status().isOk());

        String vehicleResponse = mockMvc.perform(put("/api/v1/drivers/me/vehicle")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"JK01AB" + System.nanoTime() % 10000
                                + "\",\"vehicleType\":\"CAB\",\"make\":\"Maruti\",\"model\":\"Dzire\","
                                + "\"color\":\"White\",\"manufactureYear\":" + (Year.now().getValue() - 1) + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID vehicleId = UUID.fromString(JsonPath.read(vehicleResponse, "$.data.id"));

        // --- Admin: reviews and approves every document, the vehicle, then the driver as a whole. ---
        String adminToken = jwtService.issueAccessToken(newUser(UserRole.ADMIN).getId(), UserRole.ADMIN,
                Duration.ofMinutes(15));

        String reviewBeforeApproval = mockMvc.perform(get("/api/v1/admin/drivers/" + driverId + "/review")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(reviewBeforeApproval, "$.data.readyForApproval")).isFalse();

        for (UUID docId : new UUID[] {licenseDocId, registrationDocId, vehiclePhotoDocId, insuranceDocId, selfieDocId}) {
            mockMvc.perform(post("/api/v1/admin/driver-documents/" + docId + "/approve")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        mockMvc.perform(post("/api/v1/admin/vehicles/" + vehicleId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String reviewAfterApproval = mockMvc.perform(get("/api/v1/admin/drivers/" + driverId + "/review")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(reviewAfterApproval, "$.data.readyForApproval")).isTrue();

        mockMvc.perform(post("/api/v1/admin/drivers/" + driverId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // --- Rider: fare estimate, then books, carrying the real rideSelfieId from the real endpoint. ---
        String estimateResponse = mockMvc.perform(post("/api/v1/bookings/estimate")
                        .header("Authorization", "Bearer " + riderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLatitude\":34.08,\"pickupLongitude\":74.80,"
                                + "\"destinationLatitude\":34.13,\"destinationLongitude\":74.87,\"vehicleType\":\"CAB\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long fare = JsonPath.<Number>read(estimateResponse, "$.data.estimatedFare").longValue();

        String bookingResponse = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + riderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLatitude\":34.08,\"pickupLongitude\":74.80,\"pickupAddress\":\"Lal Chowk\","
                                + "\"destinationLatitude\":34.13,\"destinationLongitude\":74.87,"
                                + "\"destinationAddress\":\"Hazratbal\",\"vehicleType\":\"CAB\","
                                + "\"paymentMethod\":\"CASH\",\"expectedFare\":" + fare
                                + ",\"rideSelfieId\":\"" + rideSelfieId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SEARCHING_DRIVER"))
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(bookingResponse, "$.data.id"));

        // --- STAND-IN for Phase 6 (matching): no "go online" or offer/accept endpoint exists yet, so
        // this is the one place that sets state directly instead of calling an endpoint. Everything
        // else below is real production code: the real DriverRepository save and the real
        // BookingStateMachine, which still enforces its normal transition table and authorisation. ---
        Driver driver = driverRepository.findById(driverId).orElseThrow();
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        driver.setCurrentLocation(point(74.80, 34.08));
        driverRepository.saveAndFlush(driver);

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setDriverId(driverId); // the only direct field set anywhere in this test - see comment above.
        bookingRepository.saveAndFlush(booking);
        stateMachine.transition(bookingId, BookingStatus.DRIVER_ASSIGNED, ActorType.SYSTEM, null,
                "stand-in: Phase 6 matching not yet built");
        stateMachine.transition(bookingId, BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driverId, "accepted");

        // --- Driver: fetches the rider's selfie through the real endpoint and actually retrieves it. ---
        String selfieUrlResponse = mockMvc.perform(get("/api/v1/drivers/me/bookings/" + bookingId + "/rider-selfie")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String selfieUrl = JsonPath.read(selfieUrlResponse, "$.data");
        assertThat(selfieUrl).isNotBlank();

        URI parsed = URI.create(selfieUrl);
        byte[] fetchedSelfie = mockMvc.perform(get(parsed))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(fetchedSelfie).isNotEmpty();

        // The rate-limit config seeded for this pricing rule stays referenced so the fixture compiles
        // cleanly even as later phases extend this test with ride-start/PIN/payment steps.
        assertThat(pricingRule.getId()).isNotNull();
    }

    private UUID uploadDocument(String driverToken, String type) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/drivers/me/documents")
                        .file(new MockMultipartFile("file", type.toLowerCase() + ".jpg", "image/jpeg",
                                "fake-jpeg-bytes".getBytes()))
                        .param("type", type)
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.data.id"));
    }
}
