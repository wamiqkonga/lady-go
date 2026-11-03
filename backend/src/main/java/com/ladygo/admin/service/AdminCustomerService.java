package com.ladygo.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminCustomerDetailResponse;
import com.ladygo.admin.web.dto.AdminCustomerRideResponse;
import com.ladygo.admin.web.dto.AdminCustomerSummaryResponse;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerAdminRow;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin customers screen: search, profile with ride history, and suspend/activate. Customers are
 * {@code users} rows with a matching {@code customers} row; the ids exposed here are the customer ids
 * (the same ones bookings reference). Suspending also ends every session; the booking, refresh and OTP
 * paths refuse a non-ACTIVE account.
 */
@Service
public class AdminCustomerService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int RIDE_HISTORY_LIMIT = 50;
    private static final UUID NO_ID = new UUID(0L, 0L);

    private final CustomerRepository customers;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final AuditLogRepository auditLogs;
    private final RefreshTokenService refreshTokens;

    public AdminCustomerService(CustomerRepository customers, UserRepository users, BookingRepository bookings,
            AuditLogRepository auditLogs, RefreshTokenService refreshTokens) {
        this.customers = customers;
        this.users = users;
        this.bookings = bookings;
        this.auditLogs = auditLogs;
        this.refreshTokens = refreshTokens;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminCustomerSummaryResponse> list(String q, UserStatus status, Pageable pageable) {
        String trimmed = q == null ? "" : q.trim();
        boolean hasQuery = !trimmed.isEmpty();
        String pattern = "%" + trimmed.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Pageable bounded = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
        Page<CustomerAdminRow> page = customers.searchForAdmin(
                status != null, status == null ? UserStatus.ACTIVE : status, hasQuery, pattern,
                hasQuery ? parseUuid(trimmed) : NO_ID, bounded);
        return PageResponse.of(page.map(row -> new AdminCustomerSummaryResponse(
                row.id(), row.name(), maskPhone(row.phone()), row.status(), row.createdAt(), row.lastLoginAt())));
    }

    @Transactional(readOnly = true)
    public AdminCustomerDetailResponse get(UUID customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(ResourceNotFoundException::new);
        User user = users.findById(customer.getUserId()).orElseThrow(ResourceNotFoundException::new);
        return toDetail(customer, user);
    }

    @Transactional
    public AdminCustomerDetailResponse suspend(UUID customerId, UUID actorId, UserRole actorRole, String reason, String ip) {
        AdminCustomerDetailResponse result =
                transition(customerId, UserStatus.ACTIVE, UserStatus.SUSPENDED, "CUSTOMER_SUSPENDED", actorId,
                        actorRole, reason, ip, "Only an active customer can be suspended.");
        return result;
    }

    @Transactional
    public AdminCustomerDetailResponse activate(UUID customerId, UUID actorId, UserRole actorRole, String reason, String ip) {
        return transition(customerId, UserStatus.SUSPENDED, UserStatus.ACTIVE, "CUSTOMER_ACTIVATED", actorId,
                actorRole, reason, ip, "Only a suspended customer can be re-activated.");
    }

    private AdminCustomerDetailResponse transition(UUID customerId, UserStatus from, UserStatus to, String action,
            UUID actorId, UserRole actorRole, String reason, String ip, String wrongStateMessage) {
        Customer customer = customers.findById(customerId).orElseThrow(ResourceNotFoundException::new);
        UUID userId = customer.getUserId();
        if (users.transitionCustomerStatus(userId, from, to, Instant.now()) == 0) {
            throw new ValidationException(wrongStateMessage);
        }
        if (to == UserStatus.SUSPENDED) {
            refreshTokens.revokeAll(userId);
        }

        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole.name());
        entry.setAction(action);
        entry.setTargetType("users");
        entry.setTargetId(userId.toString());
        entry.setMetadata(Map.of("reason", reason.trim(), "customerId", customerId.toString()));
        entry.setIp(ip);
        auditLogs.save(entry);

        return toDetail(customer, users.findById(userId).orElseThrow(ResourceNotFoundException::new));
    }

    private AdminCustomerDetailResponse toDetail(Customer customer, User user) {
        List<AdminCustomerRideResponse> rides = bookings
                .findByCustomerIdOrderByCreatedAtDesc(customer.getId(), PageRequest.of(0, RIDE_HISTORY_LIMIT))
                .map(b -> new AdminCustomerRideResponse(
                        b.getId(), b.getStatus(), b.getCreatedAt(), b.getEstimatedFare(), b.getFinalFare(),
                        b.getCurrency()))
                .getContent();
        return new AdminCustomerDetailResponse(
                customer.getId(), user.getName(), maskPhone(user.getPhone()), user.getStatus(),
                user.getStatusChangedAt(), customer.getCreatedAt(), user.getLastLoginAt(),
                bookings.countByCustomerId(customer.getId()),
                bookings.countByCustomerIdAndStatus(customer.getId(), BookingStatus.COMPLETED),
                bookings.countByCustomerIdAndStatus(customer.getId(), BookingStatus.CANCELLED), rides);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return NO_ID;
        }
    }

    /** Last 4 digits only, same as the driver review. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 4) {
            return "*".repeat(phone.length());
        }
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
