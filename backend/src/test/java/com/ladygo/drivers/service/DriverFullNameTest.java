package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A captain's full name: 2 to 80 characters after trimming, letters of any script, spaces, hyphens, apostrophes, dots. */
class DriverFullNameTest {

    @Test
    void trimsAndCollapsesRepeatedSpaces() {
        assertThat(DriverFullName.normalise("   Aisha    Khan \t")).isEqualTo("Aisha Khan");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Aisha Khan", "Mohammad Yusuf Dar", "Mary-Anne O'Neil", "Dr. A. R. Mir", "Ab",
            "عائشہ خان",            // Urdu
            "आयशा खान",             // Hindi (combining vowel signs)
            "ئایشہ",                 // Kashmiri Arabic script
            "कश्मीर सिंह",          // virama and combining marks
            "Zoë Müller", "O’Brien"})
    void acceptsNamesInAnyScript(String name) {
        assertThat(DriverFullName.normalise(name)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "A", "Aisha 2", "1234", "Aisha<b>", "<script>alert(1)</script>", "Aisha_Khan", "Aisha@Khan",
            "Aisha\nKhan", "Aisha&Khan", "Aisha;DROP", "Aisha/Khan", "😀 Aisha", "-", ". .", "'''"})
    void rejectsDigitsMarkupSymbolsEmojiAndTooShort(String name) {
        assertThatThrownBy(() -> DriverFullName.normalise(name)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> DriverFullName.normalise(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void enforcesTheEightyCharacterLimitAfterCollapsingSpaces() {
        String eighty = "a".repeat(80);
        assertThat(DriverFullName.normalise(eighty)).hasSize(80);
        assertThatThrownBy(() -> DriverFullName.normalise(eighty + "a")).isInstanceOf(ValidationException.class);
        assertThat(DriverFullName.normalise("a".repeat(40) + "      " + "a".repeat(39))).hasSize(80);
    }

    @Test
    void errorMessageNeverEchoesTheSubmittedName() {
        assertThatThrownBy(() -> DriverFullName.normalise("Secret Name 123"))
                .hasMessageNotContaining("Secret");
    }
}
