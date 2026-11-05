package com.ladygo.admin.service;

/** Admins see enough of a phone number to tell people apart in a list, never the whole number. */
final class PhoneMask {

    private PhoneMask() {
    }

    static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 4) {
            return "*".repeat(phone.length());
        }
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
