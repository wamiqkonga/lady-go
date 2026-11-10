package com.ladygo.vehicles.domain;

import com.ladygo.common.exception.ValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Indian vehicle registration number rules (authoritative; the captain site mirrors them for
 * instant feedback). Input may use spaces or hyphens and any case; the stored form is upper case
 * with no separators, so "jk 01-ab 1234" and "JK01AB1234" are the same vehicle for the unique check.
 *
 * <ul>
 *   <li>Standard: 2 letters (state), 1-2 digits (RTO), 0-3 letters (series), 4 digits. e.g. JK01AB1234.</li>
 *   <li>Bharat series: 2 digits (year), BH, 4 digits, 1-2 letters (no I or O). e.g. 22BH1234AA.</li>
 * </ul>
 */
public final class RegistrationNumbers {

    private static final Pattern STANDARD = Pattern.compile("^[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}$");
    private static final Pattern BHARAT = Pattern.compile("^\\d{2}BH\\d{4}[A-HJ-NP-Z]{1,2}$");
    static final String MESSAGE =
            "Enter a valid vehicle number, for example JK01AB1234.";

    private RegistrationNumbers() {
    }

    /** @return the normalised number; @throws ValidationException when it is not a valid format. */
    public static String normalise(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!STANDARD.matcher(value).matches() && !BHARAT.matcher(value).matches()) {
            throw new ValidationException(MESSAGE);
        }
        return value;
    }
}
