package com.ladygo.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DeviceToken;
import com.ladygo.notifications.domain.NotificationLog;
import com.ladygo.notifications.domain.NotificationStatus;
import com.ladygo.notifications.push.PushDeliveryResult;
import com.ladygo.notifications.push.PushMessage;
import com.ladygo.notifications.push.PushSender;
import com.ladygo.notifications.repository.DeviceTokenRepository;
import com.ladygo.notifications.repository.NotificationLogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PushNotificationServiceTest {

    private final DeviceTokenRepository tokens = mock(DeviceTokenRepository.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final PushSender sender = mock(PushSender.class);
    private final PushNotificationService service = new PushNotificationService(tokens, logs, sender);

    private final UUID userId = UUID.randomUUID();
    private final PushMessage message = new PushMessage("DRIVER_ACCEPTED", "Title", "Body", Map.of("bookingId", "b1"));

    private static DeviceToken device(String token) {
        DeviceToken d = new DeviceToken();
        d.setToken(token);
        return d;
    }

    @Test
    void everyRegisteredDeviceGetsThePushAndTheDeliveryIsLoggedWithoutTheMessage() {
        when(tokens.findByUserIdAndApp(userId, AppType.CUSTOMER)).thenReturn(List.of(device("t1"), device("t2")));
        when(sender.send(any(), any())).thenReturn(PushDeliveryResult.sent("ref"));

        service.notifyUser(userId, AppType.CUSTOMER, message);

        verify(sender).send("t1", message);
        verify(sender).send("t2", message);
        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(l -> {
            assertThat(l.getEventType()).isEqualTo("DRIVER_ACCEPTED");
            assertThat(l.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    void aDeadTokenIsRemovedSoWeStopPushingToIt() {
        DeviceToken dead = device("dead");
        when(tokens.findByUserIdAndApp(userId, AppType.DRIVER)).thenReturn(List.of(dead));
        when(sender.send("dead", message)).thenReturn(PushDeliveryResult.invalidToken());

        service.notifyUser(userId, AppType.DRIVER, message);

        verify(tokens).delete(dead);
    }

    @Test
    void aProviderFailureIsLoggedAsFailedAndNeverThrownAtTheRide() {
        DeviceToken live = device("live");
        when(tokens.findByUserIdAndApp(userId, AppType.CUSTOMER)).thenReturn(List.of(live));
        when(sender.send("live", message)).thenThrow(new IllegalStateException("fcm down"));

        assertThatCode(() -> service.notifyUser(userId, AppType.CUSTOMER, message)).doesNotThrowAnyException();

        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        verify(tokens, never()).delete(any(DeviceToken.class));
    }

    @Test
    void aUserWithNoDevicesIsANoOp() {
        when(tokens.findByUserIdAndApp(userId, AppType.CUSTOMER)).thenReturn(List.of());

        service.notifyUser(userId, AppType.CUSTOMER, message);

        verify(sender, never()).send(any(), any());
    }
}
