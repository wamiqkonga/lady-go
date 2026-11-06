package com.ladygo.admin.service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells a captain by email what the reviewer decided. Three rules keep it from ever hurting the decision itself:
 * the email is only queued once the decision has COMMITTED (a rollback sends nothing); sending happens on one
 * bounded background worker, so the admin's click never waits for a mail server; and any failure is logged with
 * the exception class and the user id only (never the address or the text) and swallowed.
 *
 * <p>Mail goes to the address bound to the captain's own account and nowhere else; a captain with no email on
 * file is simply not emailed (the status is still visible in the app). The body is plain text, so the reviewer's
 * free-text reason is shown exactly as typed and can never be read as markup. The subject never contains it.
 * No wording here promises how long anything will take.
 */
@Service
public class CaptainDecisionNotifier {

    /** Same support address the captain registration pages and password emails already use. */
    static final String SUPPORT_EMAIL = "supportladygo@gmail.com";

    private static final Logger log = LoggerFactory.getLogger(CaptainDecisionNotifier.class);
    /** One worker keeps the mails in decision order and bounded; a full queue drops the mail (the status is still visible in the app). */
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200), runnable -> {
                Thread thread = new Thread(runnable, "captain-decision-email");
                thread.setDaemon(true);
                return thread;
            });

    private final UserRepository users;
    private final EmailSender emailSender;

    public CaptainDecisionNotifier(UserRepository users, ObjectProvider<EmailSender> emailSenders) {
        this.users = users;
        this.emailSender = emailSenders.getIfAvailable();
    }

    public void driverApproved(UUID userId) {
        afterCommit("driver_approved", userId, (name) -> new Mail("Welcome to Lady Go - your captain account is approved",
                greeting(name) + "Good news: your Lady Go captain account has been approved.\n\n"
                        + "What to do next:\n"
                        + "1. Open the Lady Go captain app and sign in.\n"
                        + "2. Keep your location switched on.\n"
                        + "3. When you are ready to take rides, go online. Ride requests near you will appear on your screen.\n\n"
                        + footer()));
    }

    public void driverRejected(UUID userId, String reason) {
        afterCommit("driver_rejected", userId, (name) -> new Mail("An update on your Lady Go captain application",
                greeting(name) + "We are sorry, we could not approve your Lady Go captain application.\n\n"
                        + "The reviewer wrote:\n" + reason + "\n\n"
                        + "If you think this is a mistake, or you would like help, please write to us.\n\n" + footer()));
    }

    public void documentRejected(UUID userId, DocumentType type, String reason) {
        String item = describe(type);
        afterCommit("document_rejected", userId, (name) -> new Mail("Please upload your " + item + " again",
                greeting(name) + "The reviewer could not accept your " + item + ".\n\n"
                        + "The reviewer wrote:\n" + reason + "\n\n"
                        + "How to fix it: sign in to your Lady Go captain account, open your registration, "
                        + "and upload a new " + item + ". Your other documents do not need to be sent again.\n\n"
                        + footer()));
    }

    public void vehicleRejected(UUID userId, String reason) {
        afterCommit("vehicle_rejected", userId, (name) -> new Mail("Please check your vehicle details",
                greeting(name) + "The reviewer could not accept your vehicle.\n\n"
                        + "The reviewer wrote:\n" + reason + "\n\n"
                        + "How to fix it: sign in to your Lady Go captain account, open your registration, "
                        + "and correct your vehicle details (and the RC photo if it was the problem).\n\n" + footer()));
    }

    private record Mail(String subject, String body) {
    }

    private interface MailBuilder {
        Mail build(String name);
    }

    /** Registers the send for after the surrounding transaction commits; with no transaction the decision is already final. */
    private void afterCommit(String kind, UUID userId, MailBuilder builder) {
        if (emailSender == null) {
            return;
        }
        Runnable enqueue = () -> {
            try {
                WORKER.execute(() -> send(kind, userId, builder));
            } catch (RejectedExecutionException ex) {
                log.warn("operation=captain_decision_email kind={} result=dropped reason=queue_full userId={}", kind, userId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    private void send(String kind, UUID userId, MailBuilder builder) {
        try {
            User user = users.findById(userId).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                log.info("operation=captain_decision_email kind={} result=no_email userId={}", kind, userId);
                return;
            }
            Mail mail = builder.build(user.getName());
            boolean sent = emailSender.send(user.getEmail(), mail.subject(), mail.body());
            log.info("operation=captain_decision_email kind={} result={} userId={}", kind, sent ? "sent" : "failed", userId);
        } catch (RuntimeException ex) {
            // Class name only: the message can echo the address.
            log.warn("operation=captain_decision_email kind={} result=failed userId={} error={}", kind, userId,
                    ex.getClass().getSimpleName());
        }
    }

    private static String greeting(String name) {
        if (name == null || name.isBlank()) {
            return "Hello,\n\n";
        }
        String first = name.trim().split("\\s+")[0];
        return "Hello " + first + ",\n\n";
    }

    private static String footer() {
        return "With thanks,\nThe Lady Go team\n\nNeed help? Write to " + SUPPORT_EMAIL + ".";
    }

    /** What the captain knows the item as. */
    static String describe(DocumentType type) {
        if (type == DocumentType.VEHICLE_REGISTRATION) {
            return "RC photo (vehicle registration certificate)";
        }
        if (type == DocumentType.SELFIE) {
            return "selfie";
        }
        if (type == DocumentType.LICENSE) {
            return "driving licence";
        }
        if (type == DocumentType.VEHICLE_PHOTO) {
            return "vehicle photo";
        }
        return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
