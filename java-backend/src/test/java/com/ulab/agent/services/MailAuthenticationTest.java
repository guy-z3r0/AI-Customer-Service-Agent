package com.ulab.agent.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether the escalation email logs in to the relay (BUG-012).
 *
 * This used to be worked out from the username: "does it still start with
 * PLACEHOLDER_?" — which quietly turns authentication off for any real username
 * that happens to begin with those eleven characters. The relay then refuses
 * the message, with an error about the message rather than about the login, and
 * the escalation goes into the log instead of to a colleague.
 */
class MailAuthenticationTest {

    private static MailService withSettings(String smtpAuth, String username) {
        ConfigService config = mock(ConfigService.class);
        when(config.getString(anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(1));
        when(config.getString(eq("smtp_auth"), anyString())).thenReturn(smtpAuth);
        when(config.getString(eq("smtp_username"), anyString())).thenReturn(username);
        return new MailService(config);
    }

    @Test
    void aRealUsernameBeginningWithPlaceholderStillLogsIn() {
        // The exact case the old rule got wrong.
        assertTrue(withSettings("true", "PLACEHOLDER_ACCOUNT@example.com").wantsAuthentication());
    }

    @Test
    void anOrdinaryUsernameLogsIn() {
        assertTrue(withSettings("true", "shop@example.com").wantsAuthentication());
    }

    @Test
    void aRelayThatTakesMailFromAnybodyCanBeToldNotToLogIn() {
        // Some relays on a local network refuse a login outright, so the choice
        // has to exist — it just has to be a choice rather than a guess.
        assertFalse(withSettings("false", "shop@example.com").wantsAuthentication());
    }

    @Test
    void thereIsNoLoggingInWithNoUsername() {
        assertFalse(withSettings("true", "").wantsAuthentication(),
                "asking a server to authenticate as nobody fails unreadably");
    }
}
