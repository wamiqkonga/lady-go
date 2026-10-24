package com.ladygo.ratings.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fixed vocabulary for optional rating tags (docs/api/driver-earnings-ratings.md section 4.3). Persisted by
 * name in {@code ratings.tags}; the database only bounds the count, this enum bounds the values.
 * Which tags a rater may use depends on who is rating whom.
 */
public enum RatingTag {
    // Rider rating the driver.
    POLITE(RaterRole.CUSTOMER),
    SAFE_DRIVING(RaterRole.CUSTOMER),
    CLEAN_VEHICLE(RaterRole.CUSTOMER),
    // Driver rating the rider.
    RESPECTFUL(RaterRole.DRIVER),
    // Either side.
    ON_TIME(null);

    private final RaterRole onlyFor;

    RatingTag(RaterRole onlyFor) {
        this.onlyFor = onlyFor;
    }

    public boolean allowedFor(RaterRole raterRole) {
        return onlyFor == null || onlyFor == raterRole;
    }

    public static Set<RatingTag> forRater(RaterRole raterRole) {
        Set<RatingTag> allowed = EnumSet.noneOf(RatingTag.class);
        for (RatingTag tag : values()) {
            if (tag.allowedFor(raterRole)) {
                allowed.add(tag);
            }
        }
        return allowed;
    }
}
