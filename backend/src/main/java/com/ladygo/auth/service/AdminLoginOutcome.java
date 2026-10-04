package com.ladygo.auth.service;

import java.util.List;

/** Result of an admin login attempt (docs/security/auth-design.md section 3.2). */
public sealed interface AdminLoginOutcome {

    record Authenticated(TokenPair tokens) implements AdminLoginOutcome {
    }

    record EnrolmentRequired(String enrolmentToken, List<String> remainingSteps) implements AdminLoginOutcome {
    }
}
