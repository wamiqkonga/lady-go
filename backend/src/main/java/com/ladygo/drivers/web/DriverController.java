package com.ladygo.drivers.web;

import java.time.LocalDate;
import java.util.List;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.service.DriverAvailabilityService;
import com.ladygo.drivers.service.DriverLocationService;
import com.ladygo.drivers.service.DriverService;
import com.ladygo.drivers.web.dto.DriverAvailabilityRequest;
import com.ladygo.drivers.web.dto.DriverAvailabilityResponse;
import com.ladygo.drivers.web.dto.DriverDocumentResponse;
import com.ladygo.drivers.web.dto.DriverLocationRequest;
import com.ladygo.drivers.web.dto.DriverProfileRequest;
import com.ladygo.drivers.web.dto.DriverProfileResponse;
import com.ladygo.drivers.web.dto.VehicleRequest;
import com.ladygo.drivers.web.dto.VehicleResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.verification.web.dto.DeclarationAcceptRequest;
import com.ladygo.verification.web.dto.DeclarationResponse;
import com.ladygo.verification.web.dto.DeclarationStatusResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** docs/api/driver-onboarding.md section 3; docs/api/matching.md sections 2/3. */
@RestController
@RequestMapping("/api/v1/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
public class DriverController {

    private final DriverService driverService;
    private final DriverAvailabilityService availabilityService;
    private final DriverLocationService locationService;

    public DriverController(
            DriverService driverService, DriverAvailabilityService availabilityService,
            DriverLocationService locationService) {
        this.driverService = driverService;
        this.availabilityService = availabilityService;
        this.locationService = locationService;
    }

    @GetMapping("/availability")
    public ApiResponse<DriverAvailabilityResponse> getAvailability(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(availabilityService.getAvailability(principal.userId()));
    }

    @PutMapping("/availability")
    public ApiResponse<DriverAvailabilityResponse> setAvailability(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody DriverAvailabilityRequest request) {
        return ApiResponse.ok(availabilityService.setAvailability(principal.userId(), request.status()));
    }

    @PostMapping("/location")
    public ApiResponse<Void> updateLocation(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody DriverLocationRequest request) {
        locationService.updateLocation(principal.userId(), request);
        return ApiResponse.ok(null);
    }

    @PostMapping
    public ApiResponse<DriverProfileResponse> bootstrapProfile(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody DriverProfileRequest request) {
        return ApiResponse.ok(driverService.bootstrapProfile(principal.userId(), request.cityId()));
    }

    @GetMapping
    public ApiResponse<DriverProfileResponse> getProfile(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(driverService.getProfile(principal.userId()));
    }

    @PostMapping("/documents")
    public ApiResponse<DriverDocumentResponse> uploadDocument(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam DocumentType type, @RequestPart MultipartFile file,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) LocalDate expiresAt) {
        return ApiResponse.ok(driverService.uploadDocument(principal.userId(), type, file, number, expiresAt));
    }

    @GetMapping("/documents")
    public ApiResponse<List<DriverDocumentResponse>> listDocuments(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(driverService.listDocuments(principal.userId()));
    }

    @PostMapping("/selfie")
    public ApiResponse<DriverDocumentResponse> uploadSelfie(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestPart MultipartFile file) {
        return ApiResponse.ok(driverService.uploadSelfie(principal.userId(), file));
    }

    @PostMapping("/declaration")
    public ApiResponse<DeclarationResponse> acceptDeclaration(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody DeclarationAcceptRequest request) {
        return ApiResponse.ok(driverService.acceptDeclaration(principal.userId(), request.declarationVersion()));
    }

    @GetMapping("/declaration")
    public ApiResponse<DeclarationStatusResponse> getDeclarationStatus(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(driverService.getDeclarationStatus(principal.userId()));
    }

    @PutMapping("/vehicle")
    public ApiResponse<VehicleResponse> upsertVehicle(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody VehicleRequest request) {
        return ApiResponse.ok(driverService.upsertVehicle(principal.userId(), request));
    }

    @GetMapping("/vehicle")
    public ApiResponse<VehicleResponse> getVehicle(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(driverService.getVehicle(principal.userId()));
    }
}
