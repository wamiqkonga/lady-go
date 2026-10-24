package com.ladygo.ratings.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.AlreadyRatedException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import com.ladygo.ratings.domain.RatingTag;
import com.ladygo.ratings.repository.RatingRepository;
import com.ladygo.ratings.web.dto.DriverRatingSummaryResponse;
import com.ladygo.ratings.web.dto.RatingResponse;
import com.ladygo.ratings.web.dto.SubmitRatingRequest;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ratings in both directions (docs/api/driver-earnings-ratings.md section 4.3, 7). The rated person is
 * derived from the ride, never from the request. Ownership failures are 404 (not 403) so another user's
 * ride id reveals nothing. Comments are stored but never logged and never returned to the rated person.
 *
 * <p>Concurrency: a submission first runs every check that needs no lock (ride completed, window open for
 * a first rating) and rejects without touching a row lock. Only then does it lock, in this order: the
 * driver row (rider-to-driver ratings only; serialises the aggregate recompute, which then reads committed
 * ratings plus its own), then the ride row (serialises the once-per-rater check). Deadlock freedom comes
 * from the early status check: ride completion writes the ride row and then the driver row (the opposite
 * order), but it does so while the ride is still not COMPLETED as far as any other transaction can see, so a
 * rater is rejected before it locks anything and can never be one side of a lock cycle; once completion has
 * committed it holds no locks. Both entities are refreshed under their lock so no stale state is acted on.
 *
 * <p>Privacy (retaliation): a driver must not be able to work out one rider's score. The aggregate
 * breakdown (distribution, tag counts) is therefore only released once at least
 * {@link #MIN_RATINGS_FOR_BREAKDOWN} ratings exist.
 */
@Service
public class RatingService {

    static final int MAX_COMMENT_LENGTH = 500;
    static final int MAX_TAGS = 5;
    static final short LOW_RATING_THRESHOLD = 2;
    static final int REPEAT_WINDOW_DAYS = 7;
    static final int REPEAT_ONE_STAR_LIMIT = 2;
    static final int RECENT_TAG_SAMPLE = 10;
    /** Below this many rider ratings the summary shows only avg and count (no distribution, no tags). */
    static final int MIN_RATINGS_FOR_BREAKDOWN = 5;

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);

    private final RatingRepository ratings;
    private final BookingRepository bookings;
    private final RideRepository rides;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final RideFlagRepository rideFlags;
    private final ConfigurationService config;
    private final RateLimiter rateLimiter;

    @PersistenceContext
    private EntityManager entityManager;

    public RatingService(
            RatingRepository ratings, BookingRepository bookings, RideRepository rides, CustomerRepository customers,
            DriverRepository drivers, RideFlagRepository rideFlags, ConfigurationService config,
            RateLimiter rateLimiter) {
        this.ratings = ratings;
        this.bookings = bookings;
        this.rides = rides;
        this.customers = customers;
        this.drivers = drivers;
        this.rideFlags = rideFlags;
        this.config = config;
        this.rateLimiter = rateLimiter;
    }

    /** Rider rates the driver of their own ride. */
    @Transactional
    public RatingResponse rateDriver(UUID customerUserId, UUID bookingId, SubmitRatingRequest request) {
        Customer customer = customers.findByUserId(customerUserId).orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow(BookingStateException::new);
        return submit(customerUserId, RaterRole.CUSTOMER, booking, ride, request);
    }

    /** Driver rates the rider of their own ride. */
    @Transactional
    public RatingResponse rateRider(UUID driverUserId, UUID bookingId, SubmitRatingRequest request) {
        Driver driver = drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(booking.getId())
                .filter(r -> r.getDriverId().equals(driver.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        return submit(driverUserId, RaterRole.DRIVER, booking, ride, request);
    }

    private RatingResponse submit(
            UUID raterUserId, RaterRole raterRole, Booking booking, Ride ride, SubmitRatingRequest request) {
        String comment = normaliseComment(request.comment());
        Set<RatingTag> tags = normaliseTags(request.tags(), raterRole);
        short score = request.score().shortValue();

        // Checks that need no lock come first, so a doomed attempt never queues behind ride completion.
        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new BookingStateException("This ride has not been completed yet.");
        }
        if (!ratings.existsByRideIdAndRaterId(ride.getId(), raterUserId)) {
            requireWindowOpen(ride);
        }

        // Serialise everything below for this ride (and, for rider ratings, this driver). Lock order:
        // driver, then ride - the same order ride completion uses. Refresh under the lock: the entities
        // may already be in the persistence context with stale state.
        entityManager.flush(); // a refresh must never discard this transaction's own pending changes
        Driver driver = drivers.findById(ride.getDriverId()).orElseThrow(ResourceNotFoundException::new);
        if (raterRole == RaterRole.CUSTOMER) {
            entityManager.refresh(driver, LockModeType.PESSIMISTIC_WRITE);
        }
        Ride lockedRide = rides.findById(ride.getId()).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(lockedRide, LockModeType.PESSIMISTIC_WRITE);
        Customer customer = customers.findById(lockedRide.getCustomerId()).orElseThrow(ResourceNotFoundException::new);

        Rating existing = ratings.findByRideIdAndRaterId(lockedRide.getId(), raterUserId).orElse(null);
        if (existing != null) {
            if (isIdentical(existing, score, comment, tags)) {
                return toResponse(booking.getId(), existing); // idempotent resubmit, nothing changes
            }
            throw new AlreadyRatedException();
        }

        if (lockedRide.getStatus() != RideStatus.COMPLETED) {
            throw new BookingStateException("This ride has not been completed yet.");
        }
        requireWindowOpen(lockedRide);
        enforceRateLimit(raterUserId);

        UUID rateeUserId = raterRole == RaterRole.CUSTOMER ? driver.getUserId() : customer.getUserId();
        Rating rating = new Rating();
        rating.setRideId(lockedRide.getId());
        rating.setRaterId(raterUserId);
        rating.setRateeId(rateeUserId);
        rating.setRaterRole(raterRole);
        rating.setScore(score);
        rating.setComment(comment);
        rating.setTags(tags.stream().map(Enum::name).toArray(String[]::new));
        Rating saved = ratings.saveAndFlush(rating);

        if (raterRole == RaterRole.CUSTOMER) {
            recomputeDriverAggregate(driver);
        }
        raiseFlagsIfLow(raterRole, booking, lockedRide, score, raterUserId, rateeUserId);
        log.info("operation=rating_submitted rideId={} raterRole={} result=created", lockedRide.getId(), raterRole);
        return toResponse(booking.getId(), saved);
    }

    private void requireWindowOpen(Ride ride) {
        Instant completedAt = ride.getCompletedAt() != null ? ride.getCompletedAt() : ride.getUpdatedAt();
        Duration window = Duration.ofDays(config.getInt("ratings.window.days", 7));
        if (Instant.now().isAfter(completedAt.plus(window))) {
            throw new BookingStateException("The rating window for this ride has closed.");
        }
    }

    private void recomputeDriverAggregate(Driver driver) {
        long count = ratings.countByRateeIdAndRaterRole(driver.getUserId(), RaterRole.CUSTOMER);
        long sum = ratings.sumScores(driver.getUserId(), RaterRole.CUSTOMER);
        driver.setRatingCount((int) count);
        driver.setRatingAvg(average(sum, count));
        drivers.save(driver);
    }

    /** Exact half-up average to two decimals from integer sums (matches numeric(3,2)). */
    static BigDecimal average(long sum, long count) {
        if (count == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private void raiseFlagsIfLow(
            RaterRole raterRole, Booking booking, Ride ride, short score, UUID raterUserId, UUID rateeUserId) {
        ActorType raisedBy = raterRole == RaterRole.CUSTOMER ? ActorType.CUSTOMER : ActorType.DRIVER;
        if (score <= LOW_RATING_THRESHOLD) {
            raiseFlag(booking, ride, RideFlagType.LOW_RATING, raisedBy);
        }
        if (raterRole == RaterRole.CUSTOMER && score == 1) {
            long oneStars = ratings.countByRaterIdAndRateeIdAndScoreAndCreatedAtAfter(
                    raterUserId, rateeUserId, (short) 1, Instant.now().minus(Duration.ofDays(REPEAT_WINDOW_DAYS)));
            // One open flag per rider/driver pair per window: further 1-stars add nothing for ops to review.
            boolean alreadyOpen = rideFlags.existsByCustomerIdAndDriverIdAndTypeAndAdminReviewedAtIsNullAndCreatedAtAfter(
                    ride.getCustomerId(), ride.getDriverId(), RideFlagType.REPEATED_LOW_RATING,
                    Instant.now().minus(Duration.ofDays(REPEAT_WINDOW_DAYS)));
            if (oneStars > REPEAT_ONE_STAR_LIMIT && !alreadyOpen) {
                raiseFlag(booking, ride, RideFlagType.REPEATED_LOW_RATING, raisedBy);
            }
        }
    }

    private void raiseFlag(Booking booking, Ride ride, RideFlagType type, ActorType raisedBy) {
        RideFlag flag = new RideFlag();
        flag.setRideId(ride.getId());
        flag.setBookingId(booking.getId());
        flag.setCustomerId(ride.getCustomerId());
        flag.setDriverId(ride.getDriverId());
        flag.setType(type);
        flag.setRaisedBy(raisedBy);
        rideFlags.save(flag);
    }

    private void enforceRateLimit(UUID userId) {
        int max = config.getInt("ratings.submit.max_per_hour", 10);
        if (!rateLimiter.tryConsume("rating_submit:" + userId, max, Duration.ofHours(1))) {
            throw new RateLimitExceededException();
        }
    }

    /**
     * The driver's own aggregate view. {@code avg} and {@code count} are always returned. The 1-5
     * {@code distribution} and {@code recentTagCounts} are only returned once the driver has at least
     * {@link #MIN_RATINGS_FOR_BREAKDOWN} rider ratings; below that, {@code distribution} still has keys
     * "1" to "5" but all zero, and {@code recentTagCounts} is empty, so a single rider's score or tags
     * cannot be read off the breakdown (retaliation risk).
     */
    @Transactional(readOnly = true)
    public DriverRatingSummaryResponse summaryForDriver(UUID driverUserId) {
        Driver driver = drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new);
        Map<String, Long> distribution = new LinkedHashMap<>();
        Map<String, Long> recentTags = new LinkedHashMap<>();
        boolean enough = driver.getRatingCount() >= MIN_RATINGS_FOR_BREAKDOWN;
        for (short sc = 1; sc <= 5; sc++) {
            distribution.put(String.valueOf(sc),
                    enough ? ratings.countByRateeIdAndRaterRoleAndScore(driverUserId, RaterRole.CUSTOMER, sc) : 0L);
        }
        if (enough) {
            Map<RatingTag, Long> tagCounts = new EnumMap<>(RatingTag.class);
            for (Rating r : ratings.findByRateeIdAndRaterRoleOrderByCreatedAtDesc(
                    driverUserId, RaterRole.CUSTOMER, PageRequest.of(0, RECENT_TAG_SAMPLE))) {
                for (String name : r.getTags()) {
                    RatingTag tag = parseTag(name);
                    if (tag != null) {
                        tagCounts.merge(tag, 1L, Long::sum);
                    }
                }
            }
            tagCounts.forEach((tag, n) -> recentTags.put(tag.name(), n));
        }
        return new DriverRatingSummaryResponse(driver.getRatingAvg(), driver.getRatingCount(), distribution, recentTags);
    }

    /** Stored tag names outlive code: an unknown one (renamed or removed tag) is skipped, never a 500. */
    private static RatingTag parseTag(String name) {
        try {
            return RatingTag.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String normaliseComment(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment must be at most " + MAX_COMMENT_LENGTH + " characters.");
        }
        return trimmed;
    }

    static Set<RatingTag> normaliseTags(List<RatingTag> raw, RaterRole raterRole) {
        Set<RatingTag> tags = new LinkedHashSet<>();
        if (raw != null) {
            for (RatingTag tag : raw) {
                if (tag == null || !tag.allowedFor(raterRole)) {
                    throw new ValidationException("A tag is not allowed for this kind of rating.");
                }
                tags.add(tag);
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw new ValidationException("At most " + MAX_TAGS + " tags are allowed.");
        }
        return tags;
    }

    private static boolean isIdentical(Rating existing, short score, String comment, Set<RatingTag> tags) {
        Set<String> existingTags = Arrays.stream(existing.getTags()).collect(Collectors.toSet());
        Set<String> newTags = tags.stream().map(Enum::name).collect(Collectors.toSet());
        return existing.getScore() == score && Objects.equals(existing.getComment(), comment)
                && existingTags.equals(newTags);
    }

    private static RatingResponse toResponse(UUID bookingId, Rating rating) {
        return new RatingResponse(bookingId, rating.getScore(), List.of(rating.getTags()), rating.getCreatedAt());
    }
}
