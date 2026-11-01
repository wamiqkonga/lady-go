package com.ladygo.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Integration tests run with the dev/test profile, where {@link LoggingEmailSender} is wired, so Spring never builds the
 * real senders. This proves the container picks exactly the configured provider under a production-like setup.
 */
class EmailProviderWiringTest {

    @Configuration
    @Import({SmtpEmailSender.class, SesEmailSender.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void smtpProviderWiresOnlyTheSmtpSender() {
        runner.withPropertyValues("ladygo.otp.channel=email", "ladygo.email.provider=smtp",
                        "ladygo.email.smtp.host=smtp.resend.com", "ladygo.email.smtp.port=587",
                        "ladygo.email.smtp.username=resend", "ladygo.email.smtp.password=test-key",
                        "ladygo.email.smtp.from=Lady Go <no-reply@ladygoride.com>")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SmtpEmailSender.class);
                    assertThat(context).doesNotHaveBean(SesEmailSender.class);
                });
    }

    @Test
    void sesIsTheDefaultProviderWhenTheChannelIsEmail() {
        runner.withPropertyValues("ladygo.otp.channel=email", "ladygo.email.ses.region=ap-south-1",
                        "ladygo.email.ses.from=Lady Go <no-reply@ladygoride.com>")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SesEmailSender.class);
                    assertThat(context).doesNotHaveBean(SmtpEmailSender.class);
                });
    }

    /** Admin password recovery emails need a sender even while OTP codes still go by SMS, so choosing the smtp
     *  provider explicitly wires it whatever the OTP channel is. */
    @Test
    void smtpProviderIsWiredEvenWhileOtpCodesGoBySms() {
        runner.withPropertyValues("ladygo.email.provider=smtp",
                        "ladygo.email.smtp.host=smtp.resend.com", "ladygo.email.smtp.port=587",
                        "ladygo.email.smtp.username=resend", "ladygo.email.smtp.password=test-key",
                        "ladygo.email.smtp.from=Lady Go <no-reply@ladygoride.com>")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SmtpEmailSender.class);
                    assertThat(context).doesNotHaveBean(SesEmailSender.class);
                });
    }

    @Test
    void noEmailSenderIsWiredWhenTheChannelIsSmsAndNoProviderWasChosen() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SmtpEmailSender.class);
            assertThat(context).doesNotHaveBean(SesEmailSender.class);
        });
    }

    @Test
    void choosingSmtpWithoutAPasswordStopsTheApplicationFromStarting() {
        runner.withPropertyValues("ladygo.otp.channel=email", "ladygo.email.provider=smtp",
                        "ladygo.email.smtp.host=smtp.resend.com", "ladygo.email.smtp.username=resend",
                        "ladygo.email.smtp.from=no-reply@ladygoride.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "The SMTP email provider is selected but these environment variables are not set: LADYGO_SMTP_PASSWORD");
                });
    }
}
