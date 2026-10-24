package com.ladygo.bookings.service;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.storage.FileStorage;
import com.ladygo.storage.ImageWatermarker;
import com.ladygo.verification.domain.RideSelfie;
import com.ladygo.verification.repository.RideSelfieRepository;
import org.springframework.stereotype.Service;

/**
 * Delivers a rider's per-booking selfie to the one driver who accepted it, only in the window between
 * acceptance and ride start, watermarked with the driver's id, via a short-lived signed URL to a
 * throwaway derivative (docs/api/driver-onboarding.md section 4) - never a raw presigned URL straight
 * to the original, since that could carry no dynamic watermark.
 */
@Service
public class RiderSelfieDeliveryService {

    /** Delivery form (JPEG, bounded) keeps real phone photos under the driver app's 2 MB cap. */
    static final int MAX_SIDE_PX = 1024;
    static final int TARGET_BYTES = 300 * 1024;

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final RideSelfieRepository rideSelfies;
    private final FileStorage fileStorage;
    private final ConfigurationService config;

    public RiderSelfieDeliveryService(
            BookingRepository bookings, DriverRepository drivers, RideSelfieRepository rideSelfies,
            FileStorage fileStorage, ConfigurationService config) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.rideSelfies = rideSelfies;
        this.fileStorage = fileStorage;
        this.config = config;
    }

    public URI deliverFor(UUID driverUserId, UUID bookingId) {
        Driver driver = drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        if (!driver.getId().equals(booking.getDriverId())) {
            throw new ResourceNotFoundException();
        }
        boolean visible = BookingVisibilityPolicy.isVisible(
                BookingViewerRole.DRIVER, booking.getStatus(), BookingField.RIDER_SELFIE);
        if (!visible) {
            throw new ResourceNotFoundException();
        }

        RideSelfie selfie = rideSelfies.findByBookingId(bookingId).orElseThrow(ResourceNotFoundException::new);
        byte[] original = fileStorage.download(selfie.getSelfieStorageKey());
        byte[] watermarked = ImageWatermarker.withTextWatermarkJpeg(
                original, driver.getId().toString(), MAX_SIDE_PX, TARGET_BYTES);

        String derivativeKey = "rider-selfie-derivatives/" + UUID.randomUUID();
        fileStorage.upload(derivativeKey, ImageWatermarker.asStream(watermarked), watermarked.length, "image/jpeg");

        int ttlSeconds = config.getInt("verification.rider_selfie.view_url.ttl.seconds", 60);
        return fileStorage.presignedDownloadUrl(derivativeKey, Duration.ofSeconds(ttlSeconds));
    }
}
