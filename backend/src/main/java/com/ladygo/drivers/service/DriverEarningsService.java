package com.ladygo.drivers.service;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.service.BookingField;
import com.ladygo.bookings.service.BookingViewerRole;
import com.ladygo.bookings.service.BookingVisibilityPolicy;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.web.dto.DriverEarningsResponse;
import com.ladygo.drivers.web.dto.DriverRideHistoryItemResponse;
import com.ladygo.drivers.web.dto.EarningsDayResponse;
import com.ladygo.drivers.web.dto.EarningsTotalsResponse;
import com.ladygo.locations.domain.City;
import com.ladygo.locations.repository.CityRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver ride history and earnings reads (docs/api/driver-earnings-ratings.md sections 4.1, 4.2). Every
 * query is scoped by the authenticated driver's own id. Plain SQL, no caching in v1.
 */
@Service
public class DriverEarningsService {

    static final int HISTORY_DAYS = 90;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_EARNINGS_DAYS = 31;

    private static final String HISTORY_FROM_WHERE = """
            FROM rides r
            JOIN bookings b ON b.id = r.booking_id
            WHERE r.driver_id = ? AND r.status IN ('COMPLETED', 'CANCELLED')
              AND COALESCE(r.completed_at, r.created_at) >= ?
            """;

    private static final String HISTORY_SELECT = """
            SELECT b.id AS booking_id, r.id AS ride_id, r.status AS ride_status, b.status AS booking_status,
                   COALESCE(r.completed_at, r.created_at) AS ended_at,
                   b.pickup_address, b.destination_address, b.final_fare, b.currency, b.payment_method,
                   EXISTS (SELECT 1 FROM ride_flags f WHERE f.booking_id = b.id AND f.type = 'EARLY_END') AS ended_early,
                   (SELECT mr.score FROM ratings mr WHERE mr.ride_id = r.id AND mr.rater_id = ?) AS my_rating,
                   EXISTS (SELECT 1 FROM ratings rr WHERE rr.ride_id = r.id AND rr.rater_role = 'CUSTOMER')
                       AND (EXISTS (SELECT 1 FROM ratings dr WHERE dr.ride_id = r.id AND dr.rater_role = 'DRIVER')
                            OR COALESCE(r.completed_at, r.created_at) + make_interval(days => ?) < now()) AS rated_by_rider
            """ + HISTORY_FROM_WHERE + """
            ORDER BY COALESCE(r.completed_at, r.created_at) DESC, r.id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String EARNINGS_BY_LOCAL_DAY = """
            SELECT CAST((earned_at AT TIME ZONE CAST(? AS text)) AS date) AS local_day,
                   count(*) AS rides, COALESCE(sum(gross), 0) AS gross, COALESCE(sum(commission), 0) AS commission,
                   COALESCE(sum(net), 0) AS net,
                   COALESCE(sum(gross) FILTER (WHERE payment_method = 'CASH'), 0) AS cash,
                   COALESCE(sum(gross) FILTER (WHERE payment_method = 'ONLINE'), 0) AS online,
                   bool_or(commission_bps > 0) AS commissioned,
                   min(currency) AS currency, count(DISTINCT currency) AS currency_count
            FROM ride_earnings
            WHERE driver_id = ? AND earned_at >= ? AND earned_at < ?
            GROUP BY local_day
            """;

    private final JdbcTemplate jdbc;
    private final DriverRepository drivers;
    private final CityRepository cities;
    private final ConfigurationService config;

    public DriverEarningsService(
            JdbcTemplate jdbc, DriverRepository drivers, CityRepository cities, ConfigurationService config) {
        this.jdbc = jdbc;
        this.drivers = drivers;
        this.cities = cities;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public PageResponse<DriverRideHistoryItemResponse> history(UUID driverUserId, Pageable requested) {
        Driver driver = resolveDriver(driverUserId);
        Pageable pageable = PageRequest.of(
                Math.max(requested.getPageNumber(), 0), Math.min(Math.max(requested.getPageSize(), 1), MAX_PAGE_SIZE));
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(HISTORY_DAYS)));
        int windowDays = config.getInt("ratings.window.days", 7);

        Long total = jdbc.queryForObject("SELECT count(*) " + HISTORY_FROM_WHERE, Long.class, driver.getId(), cutoff);
        List<DriverRideHistoryItemResponse> items = jdbc.query(
                HISTORY_SELECT,
                (rs, i) -> {
                    // Exact addresses follow the same per-status rule as every other driver read
                    // (a CANCELLED booking never shows them), so history cannot bypass the policy.
                    BookingStatus bookingStatus = BookingStatus.valueOf(rs.getString("booking_status"));
                    boolean pickupVisible = BookingVisibilityPolicy.isVisible(
                            BookingViewerRole.DRIVER, bookingStatus, BookingField.PICKUP_ADDRESS_EXACT);
                    boolean destinationVisible = BookingVisibilityPolicy.isVisible(
                            BookingViewerRole.DRIVER, bookingStatus, BookingField.DESTINATION_ADDRESS_EXACT);
                    return new DriverRideHistoryItemResponse(
                            rs.getObject("booking_id", UUID.class),
                            rs.getObject("ride_id", UUID.class),
                            historyStatus(rs.getString("ride_status"), rs.getBoolean("ended_early")),
                            rs.getTimestamp("ended_at").toInstant(),
                            pickupVisible ? rs.getString("pickup_address") : null,
                            destinationVisible ? rs.getString("destination_address") : null,
                            rs.getObject("final_fare", Long.class),
                            rs.getString("currency"),
                            rs.getString("payment_method"),
                            rs.getObject("my_rating", Short.class),
                            rs.getBoolean("rated_by_rider"));
                },
                driverUserId, windowDays, driver.getId(), cutoff, pageable.getPageSize(), pageable.getOffset());
        return PageResponse.of(new PageImpl<>(items, pageable, total == null ? 0 : total));
    }

    private static String historyStatus(String rideStatus, boolean endedEarly) {
        if ("COMPLETED".equals(rideStatus) && endedEarly) {
            return "ENDED_EARLY";
        }
        return rideStatus;
    }

    /**
     * @param from inclusive local date (default: Monday of the current week in the city's time zone)
     * @param to   inclusive local date (default: today in the city's time zone); range at most 31 days
     */
    @Transactional(readOnly = true)
    public DriverEarningsResponse earnings(UUID driverUserId, LocalDate from, LocalDate to) {
        Driver driver = resolveDriver(driverUserId);
        City city = cities.findById(driver.getCityId()).orElseThrow(ResourceNotFoundException::new);
        ZoneId zone = ZoneId.of(city.getTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from != null ? from : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) {
            throw new ValidationException("'from' must not be after 'to'.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_EARNINGS_DAYS) {
            throw new ValidationException("The date range may be at most " + MAX_EARNINGS_DAYS + " days.");
        }

        Instant lower = start.atStartOfDay(zone).toInstant();
        Instant upper = end.plusDays(1).atStartOfDay(zone).toInstant();
        Map<LocalDate, long[]> byDay = new HashMap<>();
        Map<LocalDate, Boolean> commissionedByDay = new HashMap<>();
        Map<LocalDate, String> currencyByDay = new HashMap<>();
        jdbc.query(EARNINGS_BY_LOCAL_DAY, rs -> {
            LocalDate localDay = rs.getObject("local_day", LocalDate.class);
            if (rs.getLong("currency_count") > 1) {
                throw new IllegalStateException("Earnings for one driver and day span more than one currency.");
            }
            byDay.put(localDay, new long[] {
                rs.getLong("rides"), rs.getLong("gross"), rs.getLong("commission"), rs.getLong("net"),
                rs.getLong("cash"), rs.getLong("online")});
            commissionedByDay.put(localDay, rs.getBoolean("commissioned"));
            currencyByDay.put(localDay, rs.getString("currency"));
        }, zone.getId(), driver.getId(), Timestamp.from(lower), Timestamp.from(upper));

        // Currency and commissionConfigured come from the stored rows (a snapshot per ride), never from
        // today's config or city: changing the rate later must not rewrite the history shown.
        Set<String> currencies = new HashSet<>(currencyByDay.values());
        if (currencies.size() > 1) {
            throw new IllegalStateException("Earnings for one driver span more than one currency.");
        }
        String totalsCurrency = currencies.isEmpty() ? city.getCurrency() : currencies.iterator().next();
        boolean totalsCommissioned = commissionedByDay.containsValue(Boolean.TRUE);
        List<EarningsDayResponse> days = new ArrayList<>();
        long[] sum = new long[6];
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long[] v = byDay.getOrDefault(day, new long[6]);
            for (int i = 0; i < sum.length; i++) {
                sum[i] += v[i];
            }
            days.add(new EarningsDayResponse(day, v[0], v[1], v[2], v[3],
                    currencyByDay.getOrDefault(day, city.getCurrency()), v[4], v[5],
                    commissionedByDay.getOrDefault(day, false)));
        }
        EarningsTotalsResponse totals = new EarningsTotalsResponse(
                sum[0], sum[1], sum[2], sum[3], totalsCurrency, sum[4], sum[5], totalsCommissioned);
        return new DriverEarningsResponse(start, end, zone.getId(), totals, days);
    }

    private Driver resolveDriver(UUID userId) {
        return drivers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
    }
}
