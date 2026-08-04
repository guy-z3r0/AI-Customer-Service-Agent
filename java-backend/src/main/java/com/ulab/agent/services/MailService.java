package com.ulab.agent.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Properties;

/**
 * Sends the one kind of email this app sends: the note to a colleague saying a
 * caller needs a person.
 *
 * On a fresh install there are no SMTP credentials, and that is a normal state
 * rather than a fault. The email is then written to the log in full instead of
 * being sent — so the escalation is never silently lost, and so a demo can show
 * exactly what would have arrived without anybody's mail password being in the
 * building.
 *
 * The sender is built from the settings each time rather than wired once at
 * boot, because those settings are editable in the panel and an operator who
 * fixes a wrong SMTP host should not have to restart the app to find out.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** Long enough for a slow relay, short enough not to hold a thread all day. */
    private static final String TIMEOUT_MS = "10000";

    private final ConfigService config;

    public MailService(ConfigService config) {
        this.config = config;
    }

    /** True when a real server and a real from-address are both set. */
    public boolean isConfigured() {
        return !config.isPlaceholder("smtp_host") && !config.isPlaceholder("smtp_from");
    }

    /**
     * @param recipients who to tell, in the order they should be told
     * @return true only when the message actually left the building
     */
    public boolean send(List<String> recipients, String subject, String body) {
        if (recipients == null || recipients.isEmpty()) {
            log.warn("Nobody is listed to escalate to. The message was not sent:\n{}\n{}",
                    subject, body);
            return false;
        }
        if (!isConfigured()) {
            log.warn("No SMTP settings, so this email was logged instead of sent."
                    + "\nTo: {}\nSubject: {}\n{}", recipients, subject, body);
            return false;
        }
        return deliver(recipients, subject, body);
    }

    // ------------------------------------------------------------ internals --

    /**
     * One attempt, then one more. A relay that refuses twice in a row is down
     * rather than busy, and the caller has already hung up — there is nobody
     * left to wait for it.
     */
    private boolean deliver(List<String> recipients, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.getString("smtp_from", ""));
        message.setTo(recipients.toArray(new String[0]));
        message.setSubject(subject);
        message.setText(body);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                buildSender().send(message);
                log.info("Escalation email sent to {}", recipients);
                return true;
            } catch (RuntimeException failed) {
                log.warn("Sending the escalation email failed (attempt {}): {}",
                        attempt, failed.getMessage());
            }
        }
        log.warn("The escalation email was not sent. It said:\n{}\n{}", subject, body);
        return false;
    }

    private JavaMailSenderImpl buildSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getString("smtp_host", ""));
        sender.setPort(config.getInt("smtp_port", 587));
        sender.setUsername(config.getString("smtp_username", ""));
        sender.setPassword(config.getString("smtp_password", ""));
        sender.setDefaultEncoding("UTF-8");

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        // Authentication only when a username was given; some relays on a local
        // network take mail from anybody and refuse a login attempt outright.
        properties.put("mail.smtp.auth",
                String.valueOf(!config.isPlaceholder("smtp_username")));
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        properties.put("mail.smtp.timeout", TIMEOUT_MS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MS);
        return sender;
    }
}
