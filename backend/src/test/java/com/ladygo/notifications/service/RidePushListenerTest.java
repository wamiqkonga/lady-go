package com.ladygo.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.service.RidePushListener.Notice;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Who is told what, for each ride transition. */
class RidePushListenerTest {

    private static Optional<Notice> notice(BookingStatus to, ActorType cancelledBy, boolean hasDriver) {
        return RidePushListener.noticeFor(to, cancelledBy, hasDriver);
    }

    @Test
    void aDriverIsToldAboutAnOfferAndTheRiderAboutEachStepOfTheRide() {
        assertThat(notice(BookingStatus.DRIVER_ASSIGNED, null, true)).get()
                .extracting(Notice::audience, Notice::eventType).containsExactly(AppType.DRIVER, "RIDE_OFFER");
        assertThat(notice(BookingStatus.DRIVER_ACCEPTED, null, true)).get()
                .extracting(Notice::audience, Notice::eventType).containsExactly(AppType.CUSTOMER, "DRIVER_ACCEPTED");
        assertThat(notice(BookingStatus.DRIVER_ARRIVED, null, true)).get()
                .extracting(Notice::audience, Notice::eventType).containsExactly(AppType.CUSTOMER, "DRIVER_ARRIVED");
        assertThat(notice(BookingStatus.RIDE_COMPLETED, null, true)).get()
                .extracting(Notice::audience, Notice::eventType).containsExactly(AppType.CUSTOMER, "RATE_RIDE");
    }

    @Test
    void theOtherSideIsToldWhoeverCancelled() {
        assertThat(notice(BookingStatus.CANCELLED, ActorType.DRIVER, true)).get()
                .extracting(Notice::audience, Notice::eventType)
                .containsExactly(AppType.CUSTOMER, "RIDE_CANCELLED_BY_DRIVER");
        assertThat(notice(BookingStatus.CANCELLED, ActorType.CUSTOMER, true)).get()
                .extracting(Notice::audience, Notice::eventType)
                .containsExactly(AppType.DRIVER, "RIDE_CANCELLED_BY_RIDER");
        assertThat(notice(BookingStatus.CANCELLED, ActorType.SYSTEM, false)).get()
                .extracting(Notice::audience, Notice::eventType).containsExactly(AppType.CUSTOMER, "NO_DRIVER_FOUND");
    }

    @Test
    void aRiderCancellingBeforeAnyDriverWasAssignedNotifiesNobody() {
        assertThat(notice(BookingStatus.CANCELLED, ActorType.CUSTOMER, false)).isEmpty();
    }

    @Test
    void quietTransitionsSendNothing() {
        for (BookingStatus quiet : new BookingStatus[] {
                BookingStatus.REQUESTED, BookingStatus.SEARCHING_DRIVER, BookingStatus.DRIVER_ARRIVING,
                BookingStatus.RIDE_STARTED, BookingStatus.PAYMENT_PENDING, BookingStatus.COMPLETED}) {
            assertThat(notice(quiet, null, true)).as(quiet.name()).isEmpty();
        }
    }

    @Test
    void noMessageEverCarriesAnAddressPinOrFare() {
        for (BookingStatus status : BookingStatus.values()) {
            for (ActorType actor : new ActorType[] {null, ActorType.DRIVER, ActorType.CUSTOMER, ActorType.SYSTEM}) {
                notice(status, actor, true).ifPresent(n ->
                        assertThat(n.title() + " " + n.body()).doesNotContainPattern("\\d{3,}|₹|PIN \\d"));
            }
        }
    }
}
