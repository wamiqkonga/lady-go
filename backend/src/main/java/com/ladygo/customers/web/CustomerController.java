package com.ladygo.customers.web;

import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.customers.service.CustomerService;
import com.ladygo.customers.web.dto.CustomerProfileResponse;
import com.ladygo.customers.web.dto.EmergencyContactRequest;
import com.ladygo.customers.web.dto.EmergencyContactResponse;
import com.ladygo.customers.web.dto.SavedLocationRequest;
import com.ladygo.customers.web.dto.SavedLocationResponse;
import com.ladygo.customers.web.dto.UpdateProfileRequest;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/customer-profile.md. */
@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    private final CustomerService customerService;
    private final BookingService bookingService;

    public CustomerController(CustomerService customerService, BookingService bookingService) {
        this.customerService = customerService;
        this.bookingService = bookingService;
    }

    /** docs/api/booking.md section 6. */
    @GetMapping("/rides")
    public ApiResponse<PageResponse<BookingDetailResponse>> rideHistory(
            @AuthenticationPrincipal AuthPrincipal principal, Pageable pageable) {
        return ApiResponse.ok(bookingService.history(principal.userId(), pageable));
    }

    @GetMapping
    public ApiResponse<CustomerProfileResponse> getProfile(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(customerService.getProfile(principal.userId()));
    }

    @PutMapping
    public ApiResponse<CustomerProfileResponse> updateProfile(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(customerService.updateProfile(principal.userId(), request));
    }

    @GetMapping("/saved-locations")
    public ApiResponse<List<SavedLocationResponse>> listSavedLocations(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(customerService.listSavedLocations(principal.userId()));
    }

    @PostMapping("/saved-locations")
    public ApiResponse<SavedLocationResponse> createSavedLocation(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody SavedLocationRequest request) {
        return ApiResponse.ok(customerService.createSavedLocation(principal.userId(), request));
    }

    @PutMapping("/saved-locations/{id}")
    public ApiResponse<SavedLocationResponse> updateSavedLocation(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody SavedLocationRequest request) {
        return ApiResponse.ok(customerService.updateSavedLocation(principal.userId(), id, request));
    }

    @DeleteMapping("/saved-locations/{id}")
    public ApiResponse<Void> deleteSavedLocation(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        customerService.deleteSavedLocation(principal.userId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/emergency-contacts")
    public ApiResponse<List<EmergencyContactResponse>> listEmergencyContacts(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(customerService.listEmergencyContacts(principal.userId()));
    }

    @PostMapping("/emergency-contacts")
    public ApiResponse<EmergencyContactResponse> createEmergencyContact(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody EmergencyContactRequest request) {
        return ApiResponse.ok(customerService.createEmergencyContact(principal.userId(), request));
    }

    @PutMapping("/emergency-contacts/{id}")
    public ApiResponse<EmergencyContactResponse> updateEmergencyContact(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody EmergencyContactRequest request) {
        return ApiResponse.ok(customerService.updateEmergencyContact(principal.userId(), id, request));
    }

    @DeleteMapping("/emergency-contacts/{id}")
    public ApiResponse<Void> deleteEmergencyContact(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        customerService.deleteEmergencyContact(principal.userId(), id);
        return ApiResponse.ok(null);
    }
}
