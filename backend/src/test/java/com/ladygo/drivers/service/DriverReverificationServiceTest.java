package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.ReverificationReason;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/driver-onboarding.md section 6 - the re-verification timing rules, as agreed. */
class DriverReverificationServiceTest extends PersistenceTestSupport {

    @Autowired
    private DriverReverificationService service;

    @Test
    void reportTriggeredBlocksImmediately() {
        Driver driver = newDriver(newCity());

        service.blockImmediatelyForReport(driver.getId());

        assertThat(service.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isTrue();
        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getReverificationReason()).isEqualTo(ReverificationReason.REPORTED);
    }

    @Test
    void randomReverificationHasAGracePeriodBeforeBlocking() {
        Driver driver = newDriver(newCity());

        service.triggerRandomReverification(driver.getId());

        assertThat(service.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isFalse();
        assertThat(service.isBlockedFromGoingOnline(driver.getId(), Instant.now().plusSeconds(25 * 3600L))).isTrue();
    }

    @Test
    void notBlockedWhenNoReverificationIsPending() {
        Driver driver = newDriver(newCity());

        assertThat(service.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isFalse();
    }

    @Test
    void clearingResolvesTheBlock() {
        Driver driver = newDriver(newCity());
        service.blockImmediatelyForReport(driver.getId());

        service.clearOnApprovedSelfie(driver.getId());

        assertThat(service.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isFalse();
    }

    @Test
    void triggeringAReverificationNeverTouchesAvailabilityStatus() {
        City city = newCity();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        drivers.saveAndFlush(driver);

        service.blockImmediatelyForReport(driver.getId());

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getAvailabilityStatus()).isEqualTo(DriverAvailability.ONLINE);
    }
}
