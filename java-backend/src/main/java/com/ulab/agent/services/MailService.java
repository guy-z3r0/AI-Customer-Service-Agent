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

    /** The port that wants TLS from the first byte instead of an upgrade. */
    private static final int IMPLICIT_TLS_PORT = 465;

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
            log.warn("No SMTP settings, so this escalation was logged instead of sent."
                    + "\nTo: {}\nSubject: {}\n{}", recipients, subject, readable(body));
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
        log.warn("The escalation email was not sent. It said:\n{}\n{}", subject, readable(body));
        return false;
    }

    /**
     * As much of a message as belongs in a log file.
     *
     * The body carries the whole call transcript. Losing an escalation silently
     * is worse than logging it, which is why the fallback exists at all — but
     * application logs are not treated as customer records, so by default only
     * the summary survives and the transcript is counted, not copied. Turn
     * log_unsent_email_body on to get the lot while debugging a relay.
     */
    private String readable(String body) {
        if (!"true".equalsIgnoreCase(config.getString("log_unsent_email_body", "false"))) {
            int lines = body.split("\n", -1).length;
            int transcript = body.indexOf("\n\n", body.indexOf("\n\n") + 1);
            return (transcript > 0 ? body.substring(0, transcript) : body)
                    + "\n[" + lines + " lines withheld — set log_unsent_email_body to see them]";
        }
        return body;
    }

    /**
     * Whether to log in to the relay.
     *
     * The setting decides it, and a blank username overrides it — asking a
     * server to authenticate with nothing to authenticate as fails in a way
     * that is hard to read from the error.
     */
    boolean wantsAuthentication() {
        boolean wanted = !"false".equalsIgnoreCase(config.getString("smtp_auth", "true"));
        return wanted && !config.getString("smtp_username", "").isBlank();
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
        // Some relays on a local network take mail from anybody and refuse a
        // login attempt outright, so not logging in has to be possible. It used
        // to be inferred from whether the username still looked like a
        // placeholder, which turns authentication off for any real username
        // beginning "PLACEHOLDER_" — and the relay then refuses the message
        // with an error that says nothing about why. It is a setting now.
        properties.put("mail.smtp.auth", String.valueOf(wantsAuthentication()));

        // What travels over this connection is a full call transcript and the
        // SMTP password. "enable" only *attempts* TLS: a server that does not
        // advertise STARTTLS — or an attacker who strips it from the greeting —
        // silently gets everything in clear text. "required" refuses instead.
        // checkserveridentity makes the certificate have to match the host,
        // without which any certificate at all is accepted.
        if (sender.getPort() == IMPLICIT_TLS_PORT) {
            // Port 465 expects TLS from the first byte rather than an upgrade.
            properties.put("mail.smtp.ssl.enable", "true");
        } else {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        properties.put("mail.smtp.ssl.checkserveridentity", "true");
        properties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");

        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        properties.put("mail.smtp.timeout", TIMEOUT_MS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MS);
        return sender;
    }
}
