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
import com.ladygo.notifications.sms.LoggingSmsSender;
import com.ladygo.bookings.service.RidePinService;
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
 * One rider and one driver through onboarding, admin review, matching, and offer acceptance -
 * entirely through real HTTP endpoints, the way the mobile apps actually call them. No test shortcut
 * sets a verification status, a document status, a driver's availability, or a booking status
 * directly; every one of those is reached by driving the same endpoint a client would call (as of
 * Phase 6, this now includes go-online, location reporting, and offer accept - the direct
 * {@code driver_id}/status stand-in this test used before Phase 6 shipped is gone).
 *
 * <p>Extend this test, in place, as each later phase ships real endpoints that replace a stand-in step
 * (next: ride start/PIN, completion, payment). Do not fork a parallel journey test - this one file is
 * meant to grow into the full lifecycle so CI always exercises the whole thing together.
 */
@AutoConfigureMockMvc
class CrossPhaseJourneyTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RidePinService ridePinService;

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
        jdbc.update("DELETE FROM ride_flags WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM ride_selfies WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM location_history WHERE ride_id IN "
                + "(SELECT r.id FROM rides r JOIN customers c ON c.id = r.customer_id WHERE c.user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM rides WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM ride_offers WHERE booking_id IN "
                + "(SELECT b.id FROM bookings b JOIN customers c ON c.id = b.customer_id WHERE c.user_id = ?)",
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
        String code = LoggingSmsSender.lastCodeFor(phone);
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
                                realJpegBytes()))
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

        // --- Driver: goes online (requires the approvals above) and reports a location near pickup,
        // through the real Phase 6 endpoints - matching finds this driver once the booking below
        // reaches SEARCHING_DRIVER. ---
        mockMvc.perform(put("/api/v1/drivers/me/availability")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ONLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));
        reportDriverLocation(driverToken, 34.08, 74.80);
        // Matching's search runs in BookingCreationTransaction's own REQUIRES_NEW transaction (a
        // separate connection) - it needs the driver's ONLINE status and location committed, same
        // reason the earlier commitFixtures() call exists.
        commitFixtures(city, customerUserId, driverUserId);

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
                .andExpect(jsonPath("$.data.status").value("DRIVER_ASSIGNED")) // matching ran synchronously
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(bookingResponse, "$.data.id"));

        // --- Matching already ran synchronously (the SEARCHING_DRIVER event listener), inside the same
        // transaction as booking creation - the online driver above should already have a live offer.
        // Driver: polls for it (the HTTP fallback) and accepts, through the real Phase 6 endpoints. ---
        String currentOfferResponse = mockMvc.perform(get("/api/v1/drivers/me/offers/current")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(bookingId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID offerId = UUID.fromString(JsonPath.read(currentOfferResponse, "$.data.offerId"));

        mockMvc.perform(post("/api/v1/drivers/me/offers/" + offerId + "/accept")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRIVER_ACCEPTED"))
                .andExpect(jsonPath("$.data.pickupAddress").value("Lal Chowk")); // exact address, driver accepted

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

        // --- Driver: arrives (gated on the location already reported above, near pickup), starts with
        // the real ride PIN, completes, then confirms cash collected - all through the real Phase 6.5
        // endpoints (docs/api/ride-lifecycle.md). ---
        mockMvc.perform(post("/api/v1/drivers/me/rides/" + bookingId + "/arriving")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRIVER_ARRIVING"));

        mockMvc.perform(post("/api/v1/drivers/me/rides/" + bookingId + "/arrived")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRIVER_ARRIVED"));

        String ridePin = ridePinService.derive(bookingId);
        mockMvc.perform(post("/api/v1/drivers/me/rides/" + bookingId + "/start")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"" + ridePin + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RIDE_STARTED"));

        // This test never simulates turn-by-turn driving to the destination, so the driver's last
        // known point is still near pickup when completing - the completion-time far-from-destination
        // check (docs/api/ride-lifecycle.md section 4) is expected to raise a RideFlag here. That's
        // fine: it's a soft, non-blocking check by design (purgeCommittedFixtures cleans up the row).
        // completeRide runs RIDE_COMPLETED -> PAYMENT_PENDING synchronously, so the response already
        // reflects PAYMENT_PENDING.
        mockMvc.perform(post("/api/v1/drivers/me/rides/" + bookingId + "/complete")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

        mockMvc.perform(post("/api/v1/drivers/me/rides/" + bookingId + "/confirm-cash-collected")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

        // The rate-limit config seeded for this pricing rule stays referenced so the fixture compiles
        // cleanly even as later phases extend this test with SOS/trip-share/payment steps.
        assertThat(pricingRule.getId()).isNotNull();
    }

    private void reportDriverLocation(String driverToken, double latitude, double longitude) throws Exception {
        mockMvc.perform(post("/api/v1/drivers/me/location")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + latitude + ",\"longitude\":" + longitude
                                + ",\"recordedAt\":\"" + java.time.Instant.now() + "\",\"accuracyMeters\":5.0}"))
                .andExpect(status().isOk());
    }

    private UUID uploadDocument(String driverToken, String type) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/drivers/me/documents")
                        .file(new MockMultipartFile("file", type.toLowerCase() + ".jpg", "image/jpeg",
                                realJpegBytes()))
                        .param("type", type)
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.data.id"));
    }
}
