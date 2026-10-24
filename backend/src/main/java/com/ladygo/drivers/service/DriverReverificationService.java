package com.ladygo.drivers.service;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.ReverificationReason;
import com.ladygo.drivers.repository.DriverRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-verification timing, as agreed (docs/api/driver-onboarding.md section 6): report-triggered blocks
 * going online immediately, random periodic has a grace period, neither ever interrupts an active ride
 * - this service only ever sets timestamps on {@link Driver}, never touches
 * {@code availabilityStatus}. {@link #isBlockedFromGoingOnline} is the query Phase 6's future
 * go-online endpoint will call; nothing calls it yet.
 */
@Service
public class DriverReverificationService {

    private final DriverRepository drivers;
    private final ConfigurationService config;

    public DriverReverificationService(DriverRepository drivers, ConfigurationService config) {
        this.drivers = drivers;
        this.config = config;
    }

    @Transactional
    public void triggerRandomReverification(UUID driverId) {
        Driver driver = resolve(driverId);
        int graceHours = config.getInt("driver.reverification.random.grace.hours", 24);
        driver.setReverificationRequestedAt(Instant.now());
        driver.setReverificationReason(ReverificationReason.RANDOM_REVERIFICATION);
        driver.setReverificationBlocksOnlineAt(Instant.now().plusSeconds(graceHours * 3600L));
        drivers.save(driver);
    }

    @Transactional
    public void blockImmediatelyForReport(UUID driverId) {
        Driver driver = resolve(driverId);
        driver.setReverificationRequestedAt(Instant.now());
        driver.setReverificationReason(ReverificationReason.REPORTED);
        driver.setReverificationBlocksOnlineAt(Instant.now());
        drivers.save(driver);
    }

    /** Called when an admin approves a fresh SELFIE document - that's what resolves a re-verification. */
    @Transactional
    public void clearOnApprovedSelfie(UUID driverId) {
        Driver driver = resolve(driverId);
        driver.setReverificationRequestedAt(null);
        driver.setReverificationBlocksOnlineAt(null);
        driver.setReverificationReason(null);
        drivers.save(driver);
    }

    public boolean isBlockedFromGoingOnline(UUID driverId, Instant now) {
        Driver driver = resolve(driverId);
        Instant blocksAt = driver.getReverificationBlocksOnlineAt();
        return blocksAt != null && !now.isBefore(blocksAt);
    }

    private Driver resolve(UUID driverId) {
        return drivers.findById(driverId).orElseThrow(ResourceNotFoundException::new);
    }
}
