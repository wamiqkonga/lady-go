package com.ladygo.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminRideDetailResponse;
import com.ladygo.admin.web.dto.AdminRideFlagResponse;
import com.ladygo.admin.web.dto.AdminRideSummaryResponse;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.rides.repository.RideRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only ride oversight + flag triage (docs/product/admin-web-design.md section 4). Deliberately no
 * admin write action on a ride itself for the pilot (open question 2, decided: read-only for v1) - the
 * founder reaches any pilot participant by phone directly per docs/product/pilot-plan.md section 6, so
 * an in-console intervention (cancel/reassign/adjust fare) is a convenience, not a necessity, at this
 * cohort size. Flag review IS a write action (open question 1, decided: add it) since triage state has
 * no other recovery path once the rider's own response window has passed.
 */
@Service
public class AdminRideOversightService {

    private final BookingRepository bookings;
    private final RideRepository rides;
    private final RideFlagRepository rideFlags;
    private final AuditLogRepository auditLogs;

    public AdminRideOversightService(
            BookingRepository bookings, RideRepository rides, RideFlagRepository rideFlags,
            AuditLogRepository auditLogs) {
        this.bookings = bookings;
        this.rides = rides;
        this.rideFlags = rideFlags;
        this.auditLogs = auditLogs;
    }

    public PageResponse<AdminRideSummaryResponse> listRides(
            BookingStatus status, UUID driverId, UUID customerId, Instant createdAfter, Instant createdBefore,
            Pageable pageable) {
        Specification<Booking> spec = bookingSpecification(status, driverId, customerId, createdAfter, createdBefore);
        Page<Booking> page = bookings.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toSummary));
    }

    public AdminRideDetailResponse getRideDetail(UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(bookingId).orElse(null);
        List<AdminRideFlagResponse> flags = rideFlags.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .map(AdminRideOversightService::toFlagResponse)
                .toList();
        return new AdminRideDetailResponse(
                booking.getId(), booking.getCustomerId(), booking.getDriverId(), booking.getStatus(),
                booking.getPaymentMethod(), booking.getCancelReason(), booking.getCancelledBy(),
                booking.getCreatedAt(), booking.getEstimatedFare(), booking.getFinalFare(),
                ride == null ? null : ride.getId(), ride == null ? null : ride.getStatus(),
                ride == null ? null : ride.getStartedAt(), ride == null ? null : ride.getCompletedAt(), flags);
    }

    public PageResponse<AdminRideFlagResponse> listFlags(RideFlagType type, Boolean reviewed, Pageable pageable) {
        Specification<RideFlag> spec = flagSpecification(type, reviewed);
        Page<RideFlag> page = rideFlags.findAll(spec, pageable);
        return PageResponse.of(page.map(AdminRideOversightService::toFlagResponse));
    }

    @Transactional
    public AdminRideFlagResponse reviewFlag(UUID flagId, UUID adminUserId, String adminRole, String ip) {
        RideFlag flag = rideFlags.findById(flagId).orElseThrow(ResourceNotFoundException::new);
        if (flag.getAdminReviewedAt() != null) {
            throw new ValidationException("This flag was already reviewed.");
        }
        flag.setAdminReviewedBy(adminUserId);
        flag.setAdminReviewedAt(Instant.now());
        RideFlag saved = rideFlags.save(flag);

        AuditLog entry = new AuditLog();
        entry.setActorId(adminUserId);
        entry.setActorRole(adminRole);
        entry.setAction("RIDE_FLAG_REVIEWED");
        entry.setTargetType("ride_flags");
        entry.setTargetId(flagId.toString());
        entry.setIp(ip);
        entry.setMetadata(Map.of("type", saved.getType().name()));
        auditLogs.save(entry);

        return toFlagResponse(saved);
    }

    private AdminRideSummaryResponse toSummary(Booking booking) {
        Ride ride = rides.findByBookingId(booking.getId()).orElse(null);
        long openFlagCount = rideFlags.countByBookingIdAndAdminReviewedAtIsNull(booking.getId());
        return new AdminRideSummaryResponse(
                booking.getId(), ride == null ? null : ride.getId(), booking.getCustomerId(), booking.getDriverId(),
                booking.getStatus(), ride == null ? null : ride.getStatus(), booking.getCreatedAt(),
                booking.getEstimatedFare(), booking.getFinalFare(), openFlagCount);
    }

    private static AdminRideFlagResponse toFlagResponse(RideFlag flag) {
        return new AdminRideFlagResponse(
                flag.getId(), flag.getBookingId(), flag.getRideId(), flag.getCustomerId(), flag.getDriverId(),
                flag.getType(), flag.getRaisedBy(), flag.getDistanceFromExpectedM(), flag.isCapExceeded(),
                flag.getRiderResponse(), flag.getRiderRespondedAt(), flag.getAdminReviewedBy(),
                flag.getAdminReviewedAt(), flag.getCreatedAt());
    }

    private static Specification<Booking> bookingSpecification(
            BookingStatus status, UUID driverId, UUID customerId, Instant createdAfter, Instant createdBefore) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (driverId != null) {
                predicates.add(cb.equal(root.get("driverId"), driverId));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (createdAfter != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
            }
            if (createdBefore != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private static Specification<RideFlag> flagSpecification(RideFlagType type, Boolean reviewed) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (reviewed != null) {
                predicates.add(reviewed
                        ? cb.isNotNull(root.get("adminReviewedAt"))
                        : cb.isNull(root.get("adminReviewedAt")));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
