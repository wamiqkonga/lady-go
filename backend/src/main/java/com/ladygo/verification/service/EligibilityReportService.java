package com.ladygo.verification.service;

import java.time.Duration;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.service.DriverReverificationService;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.verification.domain.EligibilityReport;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.repository.EligibilityReportRepository;
import com.ladygo.verification.web.dto.EligibilityReportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Always scoped to a shared booking (docs/api/driver-onboarding.md section 5) - "only between users
 * who shared a booking" comes from resolving the subject FROM the booking's other party, never from a
 * free-form subject id supplied by the caller. Identified to admins (reporterId always recorded),
 * anonymous to the reported person (nothing here ever returns reporterId to the subject's side).
 */
@Service
public class EligibilityReportService {

    private final EligibilityReportRepository reports;
    private final BookingRepository bookings;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final DriverReverificationService reverificationService;

    public EligibilityReportService(
            EligibilityReportRepository reports,
            BookingRepository bookings,
            CustomerRepository customers,
            DriverRepository drivers,
            RateLimiter rateLimiter,
            ConfigurationService config,
            DriverReverificationService reverificationService) {
        this.reports = reports;
        this.bookings = bookings;
        this.customers = customers;
        this.drivers = drivers;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.reverificationService = reverificationService;
    }

    /** The direct, rate-limited report endpoint - caller must be a party to the booking. */
    @Transactional
    public EligibilityReportResponse report(UUID reporterUserId, UUID bookingId, String reason) {
        enforceRateLimit(reporterUserId);
        Subject subject = resolveSubject(reporterUserId, bookingId);
        return toResponse(create(bookingId, reporterUserId, subject.type(), subject.id(), reason, false));
    }

    /**
     * The driver-cancel-without-penalty path (docs/api/driver-onboarding.md section 4/5) - not
     * rate-limited, since it's already naturally bounded (each one costs a real cancelled ride) and a
     * driver should never be throttled out of reporting a genuine on-the-spot concern.
     */
    @Transactional
    public void autoReportFromDriverCancellation(UUID bookingId, UUID driverUserId, UUID customerId, String reason) {
        create(bookingId, driverUserId, SubjectType.CUSTOMER, customerId, reason, true);
    }

    private EligibilityReport create(
            UUID bookingId, UUID reporterUserId, SubjectType subjectType, UUID subjectId, String reason,
            boolean autoCreated) {
        EligibilityReport report = new EligibilityReport();
        report.setBookingId(bookingId);
        report.setReporterId(reporterUserId);
        report.setSubjectType(subjectType);
        report.setSubjectId(subjectId);
        report.setReason(reason);
        report.setAutoCreated(autoCreated);
        EligibilityReport saved = reports.saveAndFlush(report);
        if (subjectType == SubjectType.DRIVER) {
            reverificationService.blockImmediatelyForReport(subjectId);
        }
        return saved;
    }

    /** Never reveals whether the booking exists to a non-party - a 404 either way. */
    private Subject resolveSubject(UUID reporterUserId, UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        Customer callerCustomer = customers.findByUserId(reporterUserId).orElse(null);
        if (callerCustomer != null && booking.getCustomerId().equals(callerCustomer.getId())) {
            if (booking.getDriverId() == null) {
                throw new ValidationException("This booking has no assigned driver to report.");
            }
            return new Subject(SubjectType.DRIVER, booking.getDriverId());
        }
        Driver callerDriver = drivers.findByUserId(reporterUserId).orElse(null);
        if (callerDriver != null && callerDriver.getId().equals(booking.getDriverId())) {
            return new Subject(SubjectType.CUSTOMER, booking.getCustomerId());
        }
        throw new ResourceNotFoundException();
    }

    private void enforceRateLimit(UUID reporterUserId) {
        int maxAttempts = config.getInt("eligibility_report.rate.max.attempts", 5);
        Duration window = config.getHours("eligibility_report.rate.window.hours", Duration.ofHours(24));
        if (!rateLimiter.tryConsume("eligibility_report:" + reporterUserId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }

    private static EligibilityReportResponse toResponse(EligibilityReport report) {
        return new EligibilityReportResponse(report.getId(), report.getCreatedAt());
    }

    private record Subject(SubjectType type, UUID id) {
    }
}
