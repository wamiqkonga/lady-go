package com.ladygo.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.DriverUnavailableException;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.PaymentException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.common.exception.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void mapsNotFound() throws Exception {
        mockMvc.perform(get("/t/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void mapsUnauthorized() throws Exception {
        mockMvc.perform(get("/t/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void mapsForbidden() throws Exception {
        mockMvc.perform(get("/t/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void mapsValidationException() throws Exception {
        mockMvc.perform(get("/t/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void mapsBookingStateConflict() throws Exception {
        mockMvc.perform(get("/t/booking-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_INVALID_STATE"));
    }

    @Test
    void mapsPaymentFailure() throws Exception {
        mockMvc.perform(get("/t/payment"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_FAILED"));
    }

    @Test
    void mapsDriverUnavailable() throws Exception {
        mockMvc.perform(get("/t/driver-unavailable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DRIVER_UNAVAILABLE"));
    }

    @Test
    void bodyValidationReturnsFieldDetails() throws Exception {
        mockMvc.perform(post("/t/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mockMvc.perform(post("/t/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void mapsConcurrentUpdateToAConflictNotAServerError() throws Exception {
        mockMvc.perform(get("/t/concurrent-update"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    void missingRequiredHeaderIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(get("/t/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unexpectedErrorsNeverLeakInternalDetails() throws Exception {
        mockMvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-internal-detail"))))
                .andExpect(content().string(not(containsString("RuntimeException"))))
                .andExpect(content().string(not(containsString("at com.ladygo"))));
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/t/not-found")
        void notFound() {
            throw new ResourceNotFoundException();
        }

        @GetMapping("/t/unauthorized")
        void unauthorized() {
            throw new UnauthorizedException();
        }

        @GetMapping("/t/forbidden")
        void forbidden() {
            throw new ForbiddenException();
        }

        @GetMapping("/t/validation")
        void validation() {
            throw new ValidationException("bad input");
        }

        @GetMapping("/t/booking-state")
        void bookingState() {
            throw new BookingStateException();
        }

        @GetMapping("/t/payment")
        void payment() {
            throw new PaymentException();
        }

        @GetMapping("/t/driver-unavailable")
        void driverUnavailable() {
            throw new DriverUnavailableException();
        }

        @GetMapping("/t/boom")
        void boom() {
            throw new RuntimeException("secret-internal-detail");
        }

        @GetMapping("/t/concurrent-update")
        void concurrentUpdate() {
            throw new OptimisticLockingFailureException("stale version");
        }

        @GetMapping("/t/required-header")
        void requiredHeader(@RequestHeader("X-Required") String header) {
        }

        @PostMapping("/t/body")
        void body(@Valid @RequestBody Body body) {
        }
    }
}
