package com.ladygo.notifications.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Amazon SES v2 sender. Wired only under staging/prod, only when {@code ladygo.otp.channel=email} and {@code ladygo.email.provider=ses}
 * (the default provider).
 * Credentials come from the AWS default provider chain (env vars / instance role); the sender address
 * must be a verified SES identity. Never logs the message body (it carries the OTP).
 */
@Component
@Profile("!dev & !test")
@ConditionalOnExpression("'${ladygo.otp.channel:sms}' == 'email' and '${ladygo.email.provider:ses}' == 'ses'")
public class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final SesV2Client client;
    private final String from;

    public SesEmailSender(
            @Value("${ladygo.email.ses.region}") String region,
            @Value("${ladygo.email.ses.from}") String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("ladygo.email.ses.from (LADYGO_EMAIL_FROM) must be set for the email OTP channel.");
        }
        this.client = SesV2Client.builder().region(Region.of(region)).build();
        this.from = from;
    }

    @Override
    public boolean send(String toAddress, String subject, String textBody) {
        try {
            client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(from)
                    .destination(Destination.builder().toAddresses(toAddress).build())
                    .content(EmailContent.builder().simple(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder().text(Content.builder().data(textBody).charset("UTF-8").build()).build())
                            .build()).build())
                    .build());
            return true;
        } catch (RuntimeException e) {
            log.warn("operation=email_send result=failed error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
