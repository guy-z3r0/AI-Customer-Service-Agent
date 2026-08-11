package com.ulab.agent.security;

import com.ulab.agent.api.HealthController;
import com.ulab.agent.api.SecurityConfig;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.services.ConfigService;
import com.ulab.agent.services.LegacyImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nothing answers a stranger.
 *
 * SECURITY-AUDIT.md SEC-002 listed what an anonymous request could do: read and
 * overwrite every setting including the SMTP host and the model keys, read and
 * delete every customer with their decrypted phone number, export any call
 * transcript, and mint a Twilio token. Each row of that table is a case below.
 *
 * Only the security rules and one controller are loaded — the deny is applied
 * by the filter chain before routing, so a path does not need its controller
 * present to prove it is refused.
 */
@WebMvcTest(controllers = HealthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "PANEL_USER=operator",
        "PANEL_PASSWORD=a-test-password"
})
class ApiRequiresLoginTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BusinessRepository businesses;

    @MockitoBean
    private ConfigService config;

    /** Main starts the legacy importer on boot; the slice still has to satisfy it. */
    @MockitoBean
    private LegacyImportService importer;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/config",
            "/api/health",
            "/api/businesses",
            "/api/calls",
            // The report is every call at once, so it is the one path here that
            // would hand a stranger the whole history in a single request.
            "/api/calls/report",
            "/api/twilio/token",
            "/",
            "/js/app.js"
    })
    void anAnonymousReadIsRefused(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/call/start", "/api/import/legacy", "/api/config"})
    void anAnonymousWriteIsRefused(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void theOperatorGetsIn() throws Exception {
        mvc.perform(get("/api/health").with(httpBasic("operator", "a-test-password")))
                .andExpect(status().isOk());
    }

    @Test
    void theWrongPasswordDoesNot() throws Exception {
        mvc.perform(get("/api/health").with(httpBasic("operator", "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLivenessProbeAnswersWithoutCredentials() throws Exception {
        // A container health check cannot log in, and this says nothing except
        // that the process is running.
        mvc.perform(get("/api/health/live")).andExpect(status().isOk());
    }

    @Test
    void theTwilioMediaStreamIsNotBlockedByTheLogin() throws Exception {
        // The webhook hands Twilio a wss address on this same host, and Twilio's
        // media servers have no more credentials than its webhook does. A 401
        // here refuses the handshake before the relay is ever reached, which
        // looks from the outside like a call that connects and stays silent.
        mvc.perform(get("/ws/twilio"))
                .andExpect(result -> {
                    if (result.getResponse().getStatus() == 401) {
                        throw new AssertionError("the media stream is behind the login, "
                                + "so no Twilio call can carry audio");
                    }
                });
    }

    @Test
    void theTwilioWebhookIsNotBlockedByTheLogin() throws Exception {
        // Twilio cannot log in either, so it is let through the filter chain
        // and made to prove itself with a signature instead — TwilioSecurityTest
        // covers that half. Here it only matters that the login is not what
        // stops it: anything but 401 means the rule is still in place.
        mvc.perform(post("/api/twilio/voice"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError("the Twilio webhook is behind the login, "
                                + "so Twilio can never reach it");
                    }
                });
    }
}
