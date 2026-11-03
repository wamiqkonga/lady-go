package com.ladygo.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AdminDriverQueryRepository;
import com.ladygo.admin.repository.AdminDriverRow;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminDriverDetailResponse;
import com.ladygo.admin.web.dto.AdminDriverDetailResponse.AdminDriverDocumentsSummary;
import com.ladygo.admin.web.dto.AdminDriverDetailResponse.AdminDriverRecentRide;
import com.ladygo.admin.web.dto.AdminDriverListItemResponse;
import com.ladygo.admin.web.dto.VehicleAdminResponse;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.repository.VehicleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin Drivers screen: list/search/detail (read) and suspend/activate (audited, reasoned writes). */
@Service
public class AdminDriverService {

    private static final int RECENT_RIDES = 5;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminDriverQueryRepository queries;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final DriverDocumentRepository documents;
    private final BookingRepository bookings;
    private final AuditLogRepository auditLogs;

    public AdminDriverService(AdminDriverQueryRepository queries, DriverRepository drivers, UserRepository users,
            VehicleRepository vehicles, DriverDocumentRepository documents, BookingRepository bookings,
            AuditLogRepository auditLogs) {
        this.queries = queries;
        this.drivers = drivers;
        this.users = users;
        this.vehicles = vehicles;
        this.documents = documents;
        this.bookings = bookings;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminDriverListItemResponse> list(String q, DriverVerificationStatus status, Pageable requested) {
        Pageable pageable = PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String term = q == null ? "" : q.trim();
        boolean matchAll = term.isEmpty();
        return PageResponse.of(queries.search(matchAll, parseUuidOrNil(term), likePattern(term), status != null,
                        status == null ? DriverVerificationStatus.APPROVED : status, pageable)
                .map(AdminDriverService::toListItem));
    }

    @Transactional(readOnly = true)
    public AdminDriverDetailResponse get(UUID driverId) {
        Driver driver = drivers.findById(driverId).orElseThrow(ResourceNotFoundException::new);
        User user = users.findById(driver.getUserId()).orElse(null);
        Vehicle vehicle = vehicles.findByDriverId(driverId).stream().findFirst().orElse(null);

        var docs = documents.findByDriverId(driverId);
        AdminDriverDocumentsSummary summary = new AdminDriverDocumentsSummary(docs.size(),
                (int) docs.stream().filter(d -> d.getStatus() == DocumentStatus.PENDING).count(),
                (int) docs.stream().filter(d -> d.getStatus() == DocumentStatus.APPROVED).count(),
                (int) docs.stream().filter(d -> d.getStatus() == DocumentStatus.REJECTED).count(),
                "/driver-verification/" + driverId);

        List<AdminDriverRecentRide> recent = bookings.findAll(
                        (root, query, cb) -> cb.equal(root.get("driverId"), driverId),
                        PageRequest.of(0, RECENT_RIDES, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(AdminDriverService::toRecentRide).getContent();

        return new AdminDriverDetailResponse(driver.getId(), user == null ? null : user.getName(),
                user == null ? null : maskPhone(user.getPhone()), driver.getCityId(), driver.getVerificationStatus(),
                driver.getAvailabilityStatus(), driver.getRatingAvg(), driver.getRatingCount(), driver.getTotalRides(),
                driver.getVerifiedAt(), driver.getRejectionReason(), driver.getCreatedAt(),
                vehicle == null ? null : new VehicleAdminResponse(vehicle.getId(), vehicle.getRegistrationNumber(),
                        vehicle.getVehicleType(), vehicle.getVerificationStatus()),
                summary, recent, queries.earningsTotals(driverId));
    }

    /** APPROVED -> SUSPENDED + forced offline. Pending offers die at expiry; accepting one is blocked in matching. */
    @Transactional
    public void suspend(UUID driverId, UUID adminUserId, String adminRole, String reason, String ip) {
        requireExists(driverId);
        if (queries.suspend(driverId, Instant.now()) == 0) {
            throw new ValidationException("Only an approved driver can be suspended.");
        }
        audit(adminUserId, adminRole, "DRIVER_SUSPENDED", driverId, reason, ip);
    }

    /** SUSPENDED -> APPROVED, left offline. */
    @Transactional
    public void activate(UUID driverId, UUID adminUserId, String adminRole, String reason, String ip) {
        requireExists(driverId);
        if (queries.activate(driverId, Instant.now()) == 0) {
            throw new ValidationException("Only a suspended driver can be re-activated.");
        }
        audit(adminUserId, adminRole, "DRIVER_ACTIVATED", driverId, reason, ip);
    }

    private void requireExists(UUID driverId) {
        if (!drivers.existsById(driverId)) {
            throw new ResourceNotFoundException();
        }
    }

    private void audit(UUID actorId, String actorRole, String action, UUID driverId, String reason, String ip) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setTargetType("drivers");
        entry.setTargetId(driverId.toString());
        entry.setIp(ip);
        entry.setMetadata(Map.of("reason", reason));
        auditLogs.save(entry);
    }

    private static AdminDriverListItemResponse toListItem(AdminDriverRow row) {
        return new AdminDriverListItemResponse(row.id(), row.name(), maskPhone(row.phone()), row.verificationStatus(),
                row.availabilityStatus(), row.ratingAvg(), row.totalRides(), row.createdAt());
    }

    private static AdminDriverRecentRide toRecentRide(Booking b) {
        return new AdminDriverRecentRide(b.getId(), b.getStatus(), b.getCreatedAt(), b.getFinalFare(), b.getCurrency());
    }

    private static final UUID NIL = new UUID(0L, 0L);

    private static UUID parseUuidOrNil(String term) {
        try {
            return UUID.fromString(term);
        } catch (IllegalArgumentException e) {
            return NIL;
        }
    }

    /** Lower-cased LIKE pattern with the user's own %, _ and \ escaped so they match literally. */
    private static String likePattern(String term) {
        String escaped = term.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Same masking as the driver review screen: only the last four digits are visible. */
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
