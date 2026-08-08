package com.ulab.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The operator login.
 *
 * Until this existed, anything that could reach the port could read every
 * customer record with decrypted contact details, overwrite the SMTP host,
 * open calls as any customer and mint Twilio tokens billed to the account.
 * That was a defensible trade while the app only ever listened on 127.0.0.1 —
 * and it stopped being one the moment Phase 7 put an ngrok tunnel in front of
 * it, because ngrok forwards the whole host, not one path.
 *
 * Two things are deliberately left open:
 *
 *  - {@code /api/twilio/voice}, because Twilio has to reach it and cannot log
 *    in. It is not unprotected: it proves who it is with a signature instead,
 *    which is checked before anything is opened or spoken.
 *  - {@code /api/health/live}, a bare "is the process up" for the container
 *    health check. It says nothing about what is configured — the detailed
 *    health that lists unset credentials needs the login.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Long enough that guessing it is not the easy way in. */
    private static final int GENERATED_PASSWORD_BYTES = 18;

    /**
     * Where the panel is allowed to load code from and talk to.
     *
     * `script-src 'self'` with no exceptions, which is only possible because
     * the Twilio SDK is served from this app rather than a CDN. The websocket
     * entries are the voice server, which runs beside this one on the host, and
     * Twilio's own media edge once a telephone call is placed.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self' ws://localhost:* ws://127.0.0.1:* wss://*.twilio.com "
                    + "https://*.twilio.com",
            "object-src 'none'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "form-action 'self'");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers("/api/twilio/voice", "/api/health/live").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // No session is created here, and no cookie is set — every
                // request carries the credentials itself. That is also what
                // gets the panel's websockets through: JavaScript cannot put an
                // Authorization header on an upgrade, but the browser applies
                // the credentials it has already cached for this origin, which
                // was checked in Chrome against a running stack.
                //
                // With no cookie there is no CSRF token to protect, so the
                // filter is off. What remains reachable from another site is
                // the handful of POSTs that take no body — anything with a JSON
                // body or a method other than POST needs a CORS preflight,
                // which nothing here answers. Worth revisiting if this is ever
                // served anywhere but a loopback address.
                .csrf(csrf -> csrf.disable())
                // Defence in depth behind a panel that already avoids innerHTML
                // everywhere. The CSP names the two places the page legitimately
                // reaches: itself, and the voice server's websocket.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "microphone=(self), camera=(), geolocation=()"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));
        return http.build();
    }

    /**
     * The single operator account.
     *
     * The password comes from the environment. When it is not set the app still
     * boots — that is the placeholder-first rule this whole project is built on
     * — but it boots with a freshly generated password printed once to the log,
     * rather than with a default that everyone knows.
     */
    @Bean
    public UserDetailsService operator(@Value("${PANEL_USER:operator}") String username,
                                       @Value("${PANEL_PASSWORD:}") String password,
                                       PasswordEncoder encoder) {
        String actual = password;
        if (actual == null || actual.isBlank()) {
            actual = generatePassword();
            log.warn("""

                    ================================================================
                     No PANEL_PASSWORD is set, so one has been generated for this run:

                         username: {}
                         password: {}

                     It changes every restart. Put PANEL_PASSWORD in your .env to
                     keep one. The voice server needs the same value.
                    ================================================================""",
                    username, actual);
        }

        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(actual))
                .roles("OPERATOR")
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static String generatePassword() {
        byte[] random = new byte[GENERATED_PASSWORD_BYTES];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
