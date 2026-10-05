package com.ladygo.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.domain.ConfigValueType;
import com.ladygo.drivers.domain.CheckStatus;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.domain.ReverificationReason;
import com.ladygo.drivers.domain.VerificationReason;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DevicePlatform;
import com.ladygo.notifications.domain.NotificationChannelType;
import com.ladygo.notifications.domain.NotificationStatus;
import com.ladygo.payments.domain.PaymentMethodType;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.payments.domain.PaymentStatus;
import com.ladygo.payments.domain.RefundStatus;
import com.ladygo.payments.domain.WebhookStatus;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.verification.domain.EligibilityReportStatus;
import com.ladygo.verification.domain.SubjectType;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Java enums and SQL CHECK constraints are written separately; this test fails if they ever drift apart
 * (a value added in one place only would otherwise surface as a runtime error in production).
 */
@IntegrationTest
class EnumSchemaParityTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'::character varying");

    @Autowired
    private JdbcTemplate jdbc;

    private void check(String table, String column, Class<? extends Enum<?>> enumType) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND conname = ?",
                String.class, table, table + "_" + column + "_check");
        Set<String> inDatabase = new TreeSet<>();
        Matcher matcher = QUOTED.matcher(definition);
        while (matcher.find()) {
            inDatabase.add(matcher.group(1));
        }
        Set<String> inJava = Arrays.stream(enumType.getEnumConstants()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(inJava).as("%s.%s vs %s", table, column, enumType.getSimpleName()).isEqualTo(inDatabase);
    }

    @Test
    void javaEnumsMatchDatabaseCheckConstraints() {
        check("users", "role", UserRole.class);
        check("users", "status", UserStatus.class);
        check("otp_requests", "purpose", OtpPurpose.class);
        check("configuration", "scope", ConfigScope.class);
        check("configuration", "value_type", ConfigValueType.class);
        check("vehicles", "vehicle_type", VehicleType.class);
        check("pricing_rules", "vehicle_type", VehicleType.class);
        check("bookings", "vehicle_type", VehicleType.class);
        check("vehicles", "verification_status", VehicleVerificationStatus.class);
        check("drivers", "verification_status", DriverVerificationStatus.class);
        check("drivers", "availability_status", DriverAvailability.class);
        check("drivers", "identity_verification_status", CheckStatus.class);
        check("drivers", "background_verification_status", CheckStatus.class);
        check("driver_documents", "type", DocumentType.class);
        check("driver_documents", "status", DocumentStatus.class);
        check("bookings", "status", BookingStatus.class);
        check("bookings", "cancelled_by", ActorType.class);
        check("bookings", "payment_method", PaymentMode.class);
        check("booking_events", "actor_type", ActorType.class);
        check("ride_offers", "status", OfferStatus.class);
        check("rides", "status", RideStatus.class);
        check("payment_methods", "type", PaymentMethodType.class);
        check("payment_transactions", "method", PaymentMode.class);
        check("payment_transactions", "status", PaymentStatus.class);
        check("payment_webhook_events", "status", WebhookStatus.class);
        check("refunds", "status", RefundStatus.class);
        check("ratings", "rater_role", RaterRole.class);
        check("safety_incidents", "type", com.ladygo.support.domain.IncidentType.class);
        check("safety_incidents", "severity", IncidentSeverity.class);
        check("safety_incidents", "status", com.ladygo.support.domain.IncidentStatus.class);
        check("device_tokens", "platform", DevicePlatform.class);
        check("device_tokens", "app", AppType.class);
        check("notification_logs", "channel", NotificationChannelType.class);
        check("notification_logs", "status", NotificationStatus.class);
        check("driver_documents", "reason", VerificationReason.class);
        check("drivers", "reverification_reason", ReverificationReason.class);
        check("self_declarations", "subject_type", SubjectType.class);
        check("eligibility_reports", "subject_type", SubjectType.class);
        check("eligibility_reports", "status", EligibilityReportStatus.class);
    }
}
