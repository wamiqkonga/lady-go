package com.ladygo.auth.web.dto;

import java.util.List;

public record EnrolmentResponse(String enrolmentToken, List<String> remainingSteps) {
}
