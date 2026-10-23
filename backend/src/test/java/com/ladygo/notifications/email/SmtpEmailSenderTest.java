package com.ladygo.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailSenderTest {

    private static final String FROM = "Lady Go <no-reply@ladygoride.com>";

    @Test
    void sendsTheMessageWithSenderRecipientSubjectAndBody() {
        JavaMailSender mail = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mail, FROM);

        boolean accepted = sender.send("driver@example.test", "Your Lady Go verification code", "Your code is 123456.");

        assertThat(accepted).isTrue();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo(FROM);
        assertThat(sent.getTo()).containsExactly("driver@example.test");
        assertThat(sent.getSubject()).isEqualTo("Your Lady Go verification code");
        assertThat(sent.getText()).isEqualTo("Your code is 123456.");
    }

    @Test
    void aProviderFailureReturnsFalseInsteadOfThrowing() {
        JavaMailSender mail = mock(JavaMailSender.class);
        doThrow(new MailSendException("550 mailbox unavailable for secret@example.test")).when(mail).send(any(SimpleMailMessage.class));

        assertThat(new SmtpEmailSender(mail, FROM).send("secret@example.test", "s", "b")).isFalse();
    }

    @Test
    void refusesRecipientsThatCouldAddRecipientsOrInjectHeaders() {
        JavaMailSender mail = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mail, FROM);

        for (String bad : new String[] {
                "a@example.test,b@example.test", "a@example.test;b@example.test", "a@example.test\r\nBcc: x@example.test",
                "a@example.test\nSubject: hacked", "Name <a@example.test>", "a b@example.test", "no-at-sign", "", null}) {
            assertThat(sender.send(bad, "s", "b")).as("rejected: %s", bad).isFalse();
        }
        org.mockito.Mockito.verifyNoInteractions(mail);
        assertThat(sender.send("first.last+tag@example.co.uk", "s", "b")).isTrue();
    }

    @Test
    void startupFailsNamingEveryMissingSetting() {
        assertThatThrownBy(() -> SmtpEmailSender.build("", 587, " ", null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LADYGO_SMTP_HOST")
                .hasMessageContaining("LADYGO_SMTP_USERNAME")
                .hasMessageContaining("LADYGO_SMTP_PASSWORD")
                .hasMessageContaining("LADYGO_EMAIL_FROM");
    }

    @Test
    void theErrorNeverContainsTheSecret() {
        assertThatThrownBy(() -> SmtpEmailSender.build("smtp.example.test", 587, "user", "super-secret-key", ""))
                .hasMessageNotContaining("super-secret-key");
    }

    @Test
    void anInvalidPortIsRejected() {
        assertThatThrownBy(() -> SmtpEmailSender.build("smtp.example.test", 0, "u", "p", FROM))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LADYGO_SMTP_PORT");
    }

    @Test
    void tlsIsMandatoryOnTheSubmissionPort() {
        JavaMailSenderImpl s = SmtpEmailSender.build("smtp.example.test", 587, "u", "p", FROM);
        assertThat(s.getJavaMailProperties()).containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        assertThat(s.getHost()).isEqualTo("smtp.example.test");
        assertThat(s.getPort()).isEqualTo(587);
    }

    @Test
    void port465UsesImplicitTlsAndNoStarttlsFallback() {
        JavaMailSenderImpl s = SmtpEmailSender.build("smtp.example.test", 465, "u", "p", FROM);
        assertThat(s.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }
}
