package com.ladygo.notifications.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Provider-neutral SMTP sender (Resend, Brevo, Gmail, a hosting mailbox - any relay that speaks SMTP). Wired only
 * under staging/prod, only when {@code ladygo.email.provider=smtp} is chosen explicitly. It does not depend on the OTP
 * channel, so admin password-recovery emails work while driver OTP codes still go by SMS.
 *
 * <p>Security: TLS is mandatory - port 465 uses implicit TLS, every other port requires STARTTLS, and the sender
 * never falls back to plain text. The password comes only from the environment ({@code LADYGO_SMTP_PASSWORD}) and
 * is never logged; neither is the message body (it carries the OTP). Missing settings fail startup with the names of
 * the missing environment variables.
 */
@Component
@Profile("!dev & !test")
@ConditionalOnExpression("'${ladygo.email.provider:ses}' == 'smtp'")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final int IMPLICIT_TLS_PORT = 465;
    /** Exactly one plain address: no whitespace, commas, semicolons, angle brackets or line breaks, so a crafted value
     *  can never add recipients or inject headers. The sign-in form already validates the address; this is a second wall. */
    private static final Pattern SINGLE_ADDRESS = Pattern.compile("^[^\\s,;<>\"()\\[\\]\\\\]+@[^\\s,;<>\"()\\[\\]\\\\]+$");

    private final JavaMailSender mailSender;
    private final String from;

    @Autowired
    public SmtpEmailSender(
            @Value("${ladygo.email.smtp.host:}") String host,
            @Value("${ladygo.email.smtp.port:587}") int port,
            @Value("${ladygo.email.smtp.username:}") String username,
            @Value("${ladygo.email.smtp.password:}") String password,
            @Value("${ladygo.email.smtp.from:}") String from) {
        this(build(host, port, username, password, from), from);
    }

    /** For tests: supply a ready (or fake) mail sender. */
    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    static JavaMailSenderImpl build(String host, int port, String username, String password, String from) {
        List<String> missing = new ArrayList<>();
        if (isBlank(host)) missing.add("LADYGO_SMTP_HOST");
        if (isBlank(username)) missing.add("LADYGO_SMTP_USERNAME");
        if (isBlank(password)) missing.add("LADYGO_SMTP_PASSWORD");
        if (isBlank(from)) missing.add("LADYGO_EMAIL_FROM");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The SMTP email provider is selected but these environment variables are not set: " + String.join(", ", missing));
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("LADYGO_SMTP_PORT must be a valid port number.");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        if (port == IMPLICIT_TLS_PORT) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        return sender;
    }

    @Override
    public boolean send(String toAddress, String subject, String textBody) {
        if (toAddress == null || !SINGLE_ADDRESS.matcher(toAddress).matches()) {
            log.warn("operation=email_send provider=smtp result=rejected reason=invalid_recipient");
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(toAddress);
            message.setSubject(subject);
            message.setText(textBody);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            // Class name only: the exception message can echo the recipient or server details.
            log.warn("operation=email_send provider=smtp result=failed error={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
