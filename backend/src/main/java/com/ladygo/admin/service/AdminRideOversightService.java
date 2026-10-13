package com.ladygo.admin.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminBookingEventResponse;
import com.ladygo.admin.web.dto.AdminPersonRefResponse;
import com.ladygo.admin.web.dto.AdminRideDetailResponse;
import com.ladygo.admin.web.dto.AdminRideFlagResponse;
import com.ladygo.admin.web.dto.AdminRideSummaryResponse;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingViewerRole;
import com.ladygo.bookings.service.BookingVisibilityPolicy;
import com.ladygo.bookings.service.BookingField;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only ride oversight + flag triage (docs/product/admin-web-design.md section 4). Deliberately no
 * admin write action on a ride itself for the pilot (open question 2, decided: read-only for v1) - the
 * founder reaches any pilot participant by phone directly per docs/product/pilot-plan.md section 6, so
 * an in-console intervention (cancel/reassign/adjust fare) is a convenience, not a necessity, at this
 * cohort size. Flag review IS a write action (open question 1, decided: add it) since triage state has
 * no other recovery path once the rider's own response window has passed - it requires a note
 * (docs/product/admin-web-design.md section 4 update) for the same reason an admin document rejection
 * does: a later dispute over a triage decision needs something to point to beyond "someone looked at
 * it", and the note is itself part of what gets audited.
 */
@Service
public class AdminRideOversightService {

    private final BookingRepository bookings;
    private final RideRepository rides;
    private final RideFlagRepository rideFlags;
    private final BookingEventRepository bookingEvents;
    private final AuditLogRepository auditLogs;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final UserRepository users;

    public AdminRideOversightService(
            BookingRepository bookings, RideRepository rides, RideFlagRepository rideFlags,
            BookingEventRepository bookingEvents, AuditLogRepository auditLogs, CustomerRepository customers,
            DriverRepository drivers, UserRepository users) {
        this.bookings = bookings;
        this.rides = rides;
        this.rideFlags = rideFlags;
        this.bookingEvents = bookingEvents;
        this.auditLogs = auditLogs;
        this.customers = customers;
        this.drivers = drivers;
        this.users = users;
    }

    public PageResponse<AdminRideSummaryResponse> listRides(
            BookingStatus status, UUID driverId, UUID customerId, Instant createdAfter, Instant createdBefore,
            Boolean hasOpenFlags, Pageable pageable) {
        Specification<Booking> spec =
                bookingSpecification(status, driverId, customerId, createdAfter, createdBefore, hasOpenFlags);
        Page<Booking> page = bookings.findAll(spec, defaultSortNewestFirst(pageable));

        Set<UUID> customerIds = page.getContent().stream().map(Booking::getCustomerId).collect(Collectors.toSet());
        Set<UUID> driverIds = page.getContent().stream().map(Booking::getDriverId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AdminPersonRefResponse> customerRefs = customerContacts(customerIds);
        Map<UUID, AdminPersonRefResponse> driverRefs = driverContacts(driverIds);

        return PageResponse.of(page.map(booking -> toSummary(booking, customerRefs, driverRefs)));
    }

    public AdminRideDetailResponse getRideDetail(UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(bookingId).orElse(null);
        List<AdminRideFlagResponse> flags = rideFlags.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .map(flag -> toFlagResponse(flag, customerContact(flag.getCustomerId()), driverContact(flag.getDriverId())))
                .toList();
        List<AdminBookingEventResponse> timeline = bookingEvents.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(AdminRideOversightService::toEventResponse)
                .toList();

        // Never dumped unconditionally - see this DTO's own javadoc for why this goes through the
        // same policy the customer/driver-facing endpoints use instead of a third, separately-
        // maintained "admins can see everything" rule.
        String pickupAddress = BookingVisibilityPolicy.isVisible(
                BookingViewerRole.ADMIN, booking.getStatus(), BookingField.PICKUP_ADDRESS_EXACT)
                ? booking.getPickupAddress()
                : null;

        return new AdminRideDetailResponse(
                booking.getId(), booking.getCustomerId(), booking.getDriverId(),
                customerContact(booking.getCustomerId()), driverContact(booking.getDriverId()),
                booking.getStatus(), booking.getPaymentMethod(), pickupAddress, booking.getCancelReason(),
                booking.getCancelledBy(), booking.getCashConfirmedAt(), booking.getCashDisputedAt(),
                booking.getCreatedAt(), booking.getEstimatedFare(), booking.getFinalFare(),
                ride == null ? null : ride.getId(), ride == null ? null : ride.getStatus(),
                ride == null ? null : ride.getStartedAt(), ride == null ? null : ride.getCompletedAt(), flags,
                timeline);
    }

    public PageResponse<AdminRideFlagResponse> listFlags(RideFlagType type, Boolean reviewed, Pageable pageable) {
        Specification<RideFlag> spec = flagSpecification(type, reviewed);
        Page<RideFlag> page = rideFlags.findAll(spec, defaultSortNewestFirst(pageable));

        Set<UUID> customerIds = page.getContent().stream().map(RideFlag::getCustomerId).collect(Collectors.toSet());
        Set<UUID> driverIds = page.getContent().stream().map(RideFlag::getDriverId).collect(Collectors.toSet());
        Map<UUID, AdminPersonRefResponse> customerRefs = customerContacts(customerIds);
        Map<UUID, AdminPersonRefResponse> driverRefs = driverContacts(driverIds);

        return PageResponse.of(page.map(
                flag -> toFlagResponse(flag, customerRefs.get(flag.getCustomerId()), driverRefs.get(flag.getDriverId()))));
    }

    /** An unsorted query has no guaranteed row order (CI caught this as a flaky test on the audit-log
     *  endpoint; the same gap existed here) - default to newest-first when the caller didn't ask for
     *  a specific sort. */
    private static Pageable defaultSortNewestFirst(Pageable pageable) {
        return PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSortOr(Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional
    public AdminRideFlagResponse reviewFlag(UUID flagId, UUID adminUserId, String adminRole, String note, String ip) {
        if (note == null || note.isBlank()) {
            throw new ValidationException("A note is required to review a ride flag.");
        }
        RideFlag flag = rideFlags.findById(flagId).orElseThrow(ResourceNotFoundException::new);
        if (flag.getAdminReviewedAt() != null) {
            throw new ValidationException("This flag was already reviewed.");
        }
        flag.setAdminReviewedBy(adminUserId);
        flag.setAdminReviewedAt(Instant.now());
        flag.setAdminReviewNote(note);
        RideFlag saved = rideFlags.save(flag);

        AuditLog entry = new AuditLog();
        entry.setActorId(adminUserId);
        entry.setActorRole(adminRole);
        entry.setAction("RIDE_FLAG_REVIEWED");
        entry.setTargetType("ride_flags");
        entry.setTargetId(flagId.toString());
        entry.setIp(ip);
        entry.setMetadata(Map.of("type", saved.getType().name(), "note", note));
        auditLogs.save(entry);

        return toFlagResponse(saved, customerContact(saved.getCustomerId()), driverContact(saved.getDriverId()));
    }

    private AdminRideSummaryResponse toSummary(
            Booking booking, Map<UUID, AdminPersonRefResponse> customerRefs, Map<UUID, AdminPersonRefResponse> driverRefs) {
        Ride ride = rides.findByBookingId(booking.getId()).orElse(null);
        long openFlagCount = rideFlags.countByBookingIdAndAdminReviewedAtIsNull(booking.getId());
        return new AdminRideSummaryResponse(
                booking.getId(), ride == null ? null : ride.getId(), booking.getCustomerId(), booking.getDriverId(),
                customerRefs.get(booking.getCustomerId()), driverRefs.get(booking.getDriverId()),
                booking.getStatus(), ride == null ? null : ride.getStatus(), booking.getCreatedAt(),
                booking.getEstimatedFare(), booking.getFinalFare(), openFlagCount);
    }

    private static AdminRideFlagResponse toFlagResponse(
            RideFlag flag, AdminPersonRefResponse customer, AdminPersonRefResponse driver) {
        return new AdminRideFlagResponse(
                flag.getId(), flag.getBookingId(), flag.getRideId(), flag.getCustomerId(), flag.getDriverId(),
                customer, driver, flag.getType(), flag.getRaisedBy(), flag.getDistanceFromExpectedM(),
                flag.isCapExceeded(), flag.getRiderResponse(), flag.getRiderRespondedAt(), flag.getAdminReviewedBy(),
                flag.getAdminReviewedAt(), flag.getAdminReviewNote(), flag.getCreatedAt());
    }

    private static AdminBookingEventResponse toEventResponse(BookingEvent event) {
        return new AdminBookingEventResponse(
                event.getFromStatus(), event.getToStatus(), event.getActorType(), event.getActorId(),
                event.getReason(), event.getMetadata(), event.getCreatedAt());
    }

    /** Batched booking/flag-driver-id -> (name, masked phone) lookup, avoiding one extra round trip
     *  per row on a page. Mirrors AdminVerificationService's own driverContacts helper. */
    private Map<UUID, AdminPersonRefResponse> driverContacts(Collection<UUID> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }
        List<Driver> foundDrivers = drivers.findAllById(driverIds);
        Map<UUID, UUID> driverIdToUserId = foundDrivers.stream()
                .collect(Collectors.toMap(Driver::getId, Driver::getUserId));
        return contactsFromUserIds(driverIds, driverIdToUserId);
    }

    private Map<UUID, AdminPersonRefResponse> customerContacts(Collection<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        List<Customer> foundCustomers = customers.findAllById(customerIds);
        Map<UUID, UUID> customerIdToUserId = foundCustomers.stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getUserId));
        return contactsFromUserIds(customerIds, customerIdToUserId);
    }

    private Map<UUID, AdminPersonRefResponse> contactsFromUserIds(
            Collection<UUID> subjectIds, Map<UUID, UUID> subjectIdToUserId) {
        Set<UUID> userIds = Set.copyOf(subjectIdToUserId.values());
        Map<UUID, User> usersById = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, AdminPersonRefResponse> result = new HashMap<>();
        for (UUID subjectId : subjectIds) {
            User user = usersById.get(subjectIdToUserId.get(subjectId));
            result.put(subjectId, user == null
                    ? new AdminPersonRefResponse(null, null)
                    : new AdminPersonRefResponse(user.getName(), maskPhone(user.getPhone())));
        }
        return result;
    }

    private AdminPersonRefResponse driverContact(UUID driverId) {
        if (driverId == null) {
            return new AdminPersonRefResponse(null, null);
        }
        return driverContacts(Set.of(driverId)).getOrDefault(driverId, new AdminPersonRefResponse(null, null));
    }

    private AdminPersonRefResponse customerContact(UUID customerId) {
        return customerContacts(Set.of(customerId)).getOrDefault(customerId, new AdminPersonRefResponse(null, null));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 4) {
            return "*".repeat(phone.length());
        }
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }

    private static Specification<Booking> bookingSpecification(
            BookingStatus status, UUID driverId, UUID customerId, Instant createdAfter, Instant createdBefore,
            Boolean hasOpenFlags) {
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
            if (hasOpenFlags != null) {
                jakarta.persistence.criteria.Subquery<UUID> openFlagSubquery = query.subquery(UUID.class);
                var flagRoot = openFlagSubquery.from(RideFlag.class);
                openFlagSubquery.select(flagRoot.get("bookingId"))
                        .where(cb.and(
                                cb.equal(flagRoot.get("bookingId"), root.get("id")),
                                cb.isNull(flagRoot.get("adminReviewedAt"))));
                predicates.add(hasOpenFlags
                        ? cb.exists(openFlagSubquery)
                        : cb.not(cb.exists(openFlagSubquery)));
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
