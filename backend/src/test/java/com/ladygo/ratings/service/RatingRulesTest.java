package com.ladygo.ratings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.RatingTag;
import org.junit.jupiter.api.Test;

class RatingRulesTest {

    @Test
    void commentIsTrimmedAndBlankBecomesNull() {
        assertThat(RatingService.normaliseComment("  lovely ride \n")).isEqualTo("lovely ride");
        assertThat(RatingService.normaliseComment("   ")).isNull();
        assertThat(RatingService.normaliseComment(null)).isNull();
    }

    @Test
    void commentLimitIsFiveHundredAfterTrimming() {
        assertThat(RatingService.normaliseComment("a".repeat(500))).hasSize(500);
        assertThat(RatingService.normaliseComment("  " + "a".repeat(500) + "  ")).hasSize(500);
        assertThatThrownBy(() -> RatingService.normaliseComment("a".repeat(501)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void tagsMustSuitTheRaterAndAreDeduplicated() {
        assertThat(RatingService.normaliseTags(List.of(RatingTag.POLITE, RatingTag.POLITE, RatingTag.ON_TIME), RaterRole.CUSTOMER))
                .containsExactly(RatingTag.POLITE, RatingTag.ON_TIME);
        assertThat(RatingService.normaliseTags(List.of(RatingTag.RESPECTFUL), RaterRole.DRIVER))
                .containsExactly(RatingTag.RESPECTFUL);
        assertThat(RatingService.normaliseTags(null, RaterRole.DRIVER)).isEmpty();
        assertThatThrownBy(() -> RatingService.normaliseTags(List.of(RatingTag.RESPECTFUL), RaterRole.CUSTOMER))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RatingService.normaliseTags(List.of(RatingTag.SAFE_DRIVING), RaterRole.DRIVER))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void averageIsExactHalfUpToTwoDecimals() {
        assertThat(RatingService.average(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(RatingService.average(14, 3)).isEqualByComparingTo("4.67");
        assertThat(RatingService.average(13, 3)).isEqualByComparingTo("4.33");
        assertThat(RatingService.average(9, 2)).isEqualByComparingTo("4.50");
        assertThat(RatingService.average(5, 1)).isEqualByComparingTo("5.00");
        // 1005/200 = 5.025, an exact tie at the third decimal -> HALF_UP, never banker's rounding
        assertThat(RatingService.average(1005, 200)).isEqualByComparingTo("5.03");
    }
}
