package com.ulab.agent.api;

import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.services.ConfigService;
import com.ulab.agent.services.LegacyImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A path with nothing at it answers "nothing here", not "the server is broken".
 *
 * Found by opening the panel in a browser. Every browser asks for /favicon.ico
 * on every page load, this app has none, and the catch-all handler turned that
 * into a 500 with a full stack trace — so ordinary use of the panel wrote
 * hundreds of stack traces into a log file that is now rotated and kept. The
 * same handler also told anyone who mistyped an API path that the server had
 * failed, which is the wrong thing to go and investigate.
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, ApiExceptionAdvice.class})
@TestPropertySource(properties = {
        "PANEL_USER=operator",
        "PANEL_PASSWORD=a-test-password",
        "spring.web.resources.add-mappings=true"
})
class MissingPathTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BusinessRepository businesses;

    @MockitoBean
    private ConfigService config;

    @MockitoBean
    private LegacyImportService importer;

    @ParameterizedTest
    @ValueSource(strings = {
            "/favicon.ico",
            "/api/lang/en",
            "/api/there-is-no-such-endpoint",
            "/not-a-page.html"
    })
    void nothingThereIsA404NotA500(String path) throws Exception {
        mvc.perform(get(path).with(httpBasic("operator", "a-test-password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingPathStillNeedsTheLogin() throws Exception {
        // The 404 must not become a way of asking what exists without logging in.
        mvc.perform(get("/api/there-is-no-such-endpoint"))
                .andExpect(status().isUnauthorized());
    }
}
