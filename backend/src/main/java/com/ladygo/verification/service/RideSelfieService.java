package com.ladygo.verification.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.storage.FileStorage;
import com.ladygo.storage.FileStorageException;
import com.ladygo.storage.UploadValidator;
import com.ladygo.verification.domain.RideSelfie;
import com.ladygo.verification.repository.RideSelfieRepository;
import com.ladygo.verification.web.dto.RideSelfieResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * A rider's live selfie, captured fresh per booking (docs/api/driver-onboarding.md section 4) - no
 * admin review, no one-time account state. {@link #claimForBooking} is what
 * {@code BookingService.create} calls to enforce "fresh, unused, this customer's."
 */
@Service
public class RideSelfieService {

    private final RideSelfieRepository rideSelfies;
    private final FileStorage fileStorage;
    private final ConfigurationService config;

    public RideSelfieService(RideSelfieRepository rideSelfies, FileStorage fileStorage, ConfigurationService config) {
        this.rideSelfies = rideSelfies;
        this.fileStorage = fileStorage;
        this.config = config;
    }

    @Transactional
    public RideSelfieResponse capture(UUID customerId, MultipartFile file) {
        long maxSize = config.getInt("verification.upload.max_size.bytes", 8_388_608);
        UploadValidator.validateImage(file, maxSize);

        String key = "ride-selfies/" + UUID.randomUUID();
        try {
            fileStorage.upload(key, file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }

        RideSelfie selfie = new RideSelfie();
        selfie.setCustomerId(customerId);
        selfie.setSelfieStorageKey(key);
        RideSelfie saved = rideSelfies.saveAndFlush(selfie);
        return new RideSelfieResponse(saved.getId(), saved.getCapturedAt());
    }

    /** 0 rows updated means the selfie doesn't exist, isn't this customer's, is already used, or is stale. */
    @Transactional
    public void claimForBooking(UUID rideSelfieId, UUID customerId, UUID bookingId) {
        int maxAgeMinutes = config.getInt("verification.ride_selfie.max_age.minutes", 30);
        Instant notBefore = Instant.now().minus(Duration.ofMinutes(maxAgeMinutes));
        int updated = rideSelfies.claimForBooking(rideSelfieId, customerId, bookingId, notBefore);
        if (updated == 0) {
            throw new ValidationException(
                    "A fresh, unused ride selfie is required to create this booking. Capture one first via "
                            + "POST /customers/me/ride-selfies.");
        }
    }
}
