package com.ladygo.drivers.service;

import java.text.Normalizer;
import java.util.regex.Pattern;
import com.ladygo.common.exception.ValidationException;

/**
 * A captain's full name as reviewers see it: 2 to 80 characters once trimmed, made of letters from any script
 * (Kashmiri, Urdu, Hindi and English names all work), their combining marks, single spaces, hyphens, apostrophes and
 * dots. No digits, no markup, no symbols. Repeated spaces are collapsed.
 *
 * <p>The error messages never repeat the submitted text: a name is personal data and must not reach a log line.
 */
public final class DriverFullName {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 80;

    private static final String MESSAGE =
            "Enter your full name using letters, spaces, hyphens, apostrophes and dots only (2 to 80 characters).";
    /** Letters, combining marks (vowel signs, virama), zero-width (non-)joiners used in Urdu/Persian, and the punctuation allowed in names. */
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M}\\u200C\\u200D .'\\u2019-]+");
    private static final Pattern REPEATED_SPACES = Pattern.compile(" {2,}");

    private DriverFullName() {
    }

    /** Returns the cleaned name, or throws {@link ValidationException} (HTTP 400). */
    public static String normalise(String raw) {
        if (raw == null) {
            throw new ValidationException(MESSAGE);
        }
        String name = REPEATED_SPACES.matcher(Normalizer.normalize(raw.strip(), Normalizer.Form.NFC)).replaceAll(" ");
        int length = name.codePointCount(0, name.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH || !ALLOWED.matcher(name).matches()) {
            throw new ValidationException(MESSAGE);
        }
        // Dots, hyphens and apostrophes alone are not a name: at least two real letters are needed.
        long letters = name.codePoints().filter(Character::isLetter).count();
        if (letters < MIN_LENGTH) {
            throw new ValidationException(MESSAGE);
        }
        return name;
    }
}
