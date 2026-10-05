package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.sms.MockSmsService;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * docs/api/customer-profile.md section 1: proves the ACTUAL transactional boundary of
 * {@code OtpLoginService.verifyOtp}, not the test framework's own rollback - this class is
 * deliberately NOT {@code @Transactional}, so the only thing that can undo the User insert is the
 * service method's own {@code @Transactional} proxy reacting to the Customer insert failing.
 */
@IntegrationTest
class OtpLoginServiceCustomerRollbackTest {

    private static final AtomicInteger SEQ = new AtomicInteger(8000);

    @Autowired
    private OtpLoginService otpLoginService;
    @Autowired
    private UserRepository users;
    @MockitoSpyBean
    private CustomerRepository customers;

    private static String nextPhone() {
        return "+9192" + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void customerRowCreationFailureRollsBackTheNewUserToo() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.CUSTOMER);
        String code = MockSmsService.lastCodeFor(phone);

        doThrow(new RuntimeException("simulated customer insert failure")).when(customers).save(any());

        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.CUSTOMER, "d1"))
                .isInstanceOf(RuntimeException.class);

        assertThat(users.findByPhone(phone)).isEmpty();
    }
}
