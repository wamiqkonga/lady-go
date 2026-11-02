package com.ladygo.auth.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.users.domain.User;
import org.springframework.stereotype.Component;

/**
 * Password rules for drivers: length and a common-password check only, no composition rules (they push people to
 * "Password1!"). Spaces and any characters are allowed. Enforced on the server; the website only shows the same hint.
 */
@Component
public class DriverPasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES = 72; // bcrypt ignores everything past 72 bytes, so longer is refused rather than silently cut

    private final Set<String> common;

    public DriverPasswordPolicy() {
        this.common = load();
    }

    /** @throws ValidationException with a plain-language reason when the password is not acceptable. */
    public void validate(String password, User user) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new ValidationException("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ValidationException("That password is too long. Use at most " + MAX_BYTES + " characters.");
        }
        String folded = password.replace(" ", "").toLowerCase(Locale.ROOT);
        if (common.contains(folded) || isRepeatedCharacter(folded)) {
            throw new ValidationException("That password is too common. Choose something harder to guess.");
        }
        if (user != null && matchesOwnDetails(folded, user)) {
            throw new ValidationException("Do not use your phone number or email as your password.");
        }
    }

    private static boolean isRepeatedCharacter(String s) {
        return !s.isEmpty() && s.chars().distinct().count() == 1;
    }

    private static boolean matchesOwnDetails(String folded, User user) {
        String phone = user.getPhone() == null ? "" : user.getPhone().replaceAll("\\D", "");
        String lastTen = phone.substring(Math.max(0, phone.length() - 10));
        if (lastTen.length() >= 8 && folded.replaceAll("\\D", "").contains(lastTen)) {
            return true;
        }
        String email = user.getEmail() == null ? "" : user.getEmail().toLowerCase(Locale.ROOT);
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        return !email.isEmpty() && (folded.equals(email) || (local.length() >= 6 && folded.equals(local)));
    }

    private static Set<String> load() {
        Set<String> words = new HashSet<>();
        try (InputStream in = DriverPasswordPolicy.class.getResourceAsStream("/auth/common-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("auth/common-passwords.txt is missing from the classpath.");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase(Locale.ROOT);
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the common-password list.", ex);
        }
        return Set.copyOf(words);
    }
}
