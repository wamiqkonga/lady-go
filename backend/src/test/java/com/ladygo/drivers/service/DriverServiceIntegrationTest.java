package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.web.dto.DriverDocumentResponse;
import com.ladygo.drivers.web.dto.DriverProfileResponse;
import com.ladygo.drivers.web.dto.VehicleRequest;
import com.ladygo.drivers.web.dto.VehicleResponse;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import java.time.Year;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class DriverServiceIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private DriverService service;
    @Autowired
    private DriverReverificationService reverificationService;

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "doc.jpg", "image/jpeg", realJpegBytes());
    }

    private UUID newDriverUserId(City city) {
        return newDriver(city).getUserId();
    }

    @Test
    void bootstrapProfileCreatesThenIsIdempotent() {
        City city = newCity();
        UUID userId = newUser(UserRole.DRIVER).getId();

        DriverProfileResponse first = service.bootstrapProfile(userId, city.getId());
        DriverProfileResponse second = service.bootstrapProfile(userId, city.getId());

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void bootstrapProfileRejectsAnUnknownCity() {
        assertThatThrownBy(() -> service.bootstrapProfile(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadDocumentThenListReturnsIt() {
        City city = newCity();
        UUID userId = newDriverUserId(city);

        DriverDocumentResponse uploaded = service.uploadDocument(userId, DocumentType.LICENSE, jpegFile(), null, null);

        assertThat(service.listDocuments(userId)).extracting(DriverDocumentResponse::id).contains(uploaded.id());
    }

    @Test
    void uploadDocumentRejectsANonImageFile() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> service.uploadDocument(userId, DocumentType.LICENSE, pdf, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void uploadSelfieTaggedInitialWhenNoReverificationIsPending() {
        City city = newCity();
        UUID userId = newDriverUserId(city);

        service.uploadSelfie(userId, jpegFile());

        var docs = service.listDocuments(userId);
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).type()).isEqualTo(DocumentType.SELFIE);
    }

    @Test
    void uploadSelfieDuringAReportedReverificationIsAccepted() {
        City city = newCity();
        Driver driver = newDriver(city);
        reverificationService.blockImmediatelyForReport(driver.getId());

        DriverDocumentResponse response = service.uploadSelfie(driver.getUserId(), jpegFile());

        assertThat(response.type()).isEqualTo(DocumentType.SELFIE);
    }

    @Test
    void acceptDeclarationSucceeds() {
        City city = newCity();
        UUID userId = newDriverUserId(city);

        var response = service.acceptDeclaration(userId, "v1");

        assertThat(response.declarationVersion()).isEqualTo("v1");
    }

    /** The actual gap this closes: previously nothing exposed the current version's text at all, so a
     *  client had no way to show the driver what they're agreeing to before accepting it. */
    @Test
    void declarationStatusShowsTheCurrentTextBeforeAcceptance() {
        City city = newCity();
        UUID userId = newDriverUserId(city);

        var status = service.getDeclarationStatus(userId);

        assertThat(status.currentVersion()).isEqualTo("v1");
        assertThat(status.currentText()).isNotBlank();
        assertThat(status.accepted()).isFalse();
        assertThat(status.acceptedAt()).isNull();
    }

    @Test
    void declarationStatusReflectsAcceptance() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        service.acceptDeclaration(userId, "v1");

        var status = service.getDeclarationStatus(userId);

        assertThat(status.accepted()).isTrue();
        assertThat(status.acceptedVersion()).isEqualTo("v1");
        assertThat(status.acceptedAt()).isNotNull();
    }

    /** The approved design calls for "licence (photo + number + expiry)" and "insurance photo +
     *  expiry" (docs/api/driver-onboarding.md section 0) - previously there was no way to submit
     *  either value at all, even though expiresAt already existed as a column nothing ever set. */
    @Test
    void uploadDocumentStoresNumberAndExpiry() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        var expiry = java.time.LocalDate.now().plusYears(1);

        DriverDocumentResponse uploaded = service.uploadDocument(
                userId, DocumentType.LICENSE, jpegFile(), "DL-1234-5678", expiry);

        assertThat(uploaded.documentNumber()).isEqualTo("DL-1234-5678");
        assertThat(uploaded.expiresAt()).isEqualTo(expiry);
        DriverDocumentResponse listed = service.listDocuments(userId).stream()
                .filter(d -> d.id().equals(uploaded.id())).findFirst().orElseThrow();
        assertThat(listed.documentNumber()).isEqualTo("DL-1234-5678");
        assertThat(listed.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void uploadDocumentRejectsAnAlreadyExpiredDate() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        var yesterday = java.time.LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> service.uploadDocument(
                userId, DocumentType.INSURANCE, jpegFile(), null, yesterday))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void upsertVehicleThenGetReturnsIt() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        VehicleRequest request = new VehicleRequest(
                "JK01AB" + System.nanoTime() % 10000, VehicleType.CAB, "Maruti", "Dzire", "White",
                (short) (Year.now().getValue() - 2));

        VehicleResponse created = service.upsertVehicle(userId, request);

        assertThat(service.getVehicle(userId).id()).isEqualTo(created.id());
    }

    @Test
    void upsertVehicleRejectsAVehicleOlderThanTheConfiguredMaxAge() {
        City city = newCity();
        UUID userId = newDriverUserId(city);
        VehicleRequest request = new VehicleRequest(
                "JK01CD" + System.nanoTime() % 10000, VehicleType.CAB, "Maruti", "Dzire", "White",
                (short) (Year.now().getValue() - 20));

        assertThatThrownBy(() -> service.upsertVehicle(userId, request)).isInstanceOf(ValidationException.class);
    }

    @Test
    void upsertVehicleRejectsADuplicateRegistrationNumber() {
        City city = newCity();
        UUID firstUserId = newDriverUserId(city);
        UUID secondUserId = newDriverUserId(city);
        String registration = "JK01EF" + System.nanoTime() % 10000;
        service.upsertVehicle(firstUserId, new VehicleRequest(
                registration, VehicleType.CAB, "Maruti", "Dzire", "White", (short) (Year.now().getValue() - 1)));

        assertThatThrownBy(() -> service.upsertVehicle(secondUserId, new VehicleRequest(
                registration, VehicleType.CAB, "Hyundai", "i10", "Black", (short) (Year.now().getValue() - 1))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getVehicleFor404sWhenNoneSubmittedYet() {
        City city = newCity();
        UUID userId = newDriverUserId(city);

        assertThatThrownBy(() -> service.getVehicle(userId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
