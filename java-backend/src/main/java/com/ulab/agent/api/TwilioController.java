package com.ulab.agent.api;

import com.google.gson.JsonObject;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.services.ConfigService;
import com.ulab.agent.utils.Lang;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The optional telephone half: a real phone number instead of a browser tab.
 *
 * Two endpoints, and they are called by two different things. The panel asks
 * for a token so the Twilio SDK in the page can register as a device; Twilio
 * itself asks for TwiML the moment a call connects, and is told to point its
 * Media Streams websocket at the voice server. After that the call is the same
 * call as any other — same transport layer, same brain, same knowledge base.
 *
 * Nothing here needs the Twilio SDK. An access token is a JSON Web Token with
 * a particular set of claims, signed with the API key secret, and minting one
 * is twenty lines — much less than a dependency, and much easier to read than
 * one.
 */
@RestController
@RequestMapping("/api/twilio")
public class TwilioController {

    private static final Logger log = LoggerFactory.getLogger(TwilioController.class);

    /**
     * How long a minted token stays usable.
     *
     * Ten minutes, not an hour. A token is a bearer credential that can place
     * calls billed to the account, it cannot be revoked once issued, and the
     * SDK asks for a new one before the old expires — so the only thing a long
     * life buys is a longer window for a stolen one.
     */
    private static final long TOKEN_TTL_SECONDS = 600;

    /** Twilio's own marker saying which flavour of token this is. */
    private static final String TOKEN_CONTENT_TYPE = "twilio-fpa;v=1";

    /** Who the panel registers as. One operator, so one name. */
    private static final String IDENTITY = "panel-operator";

    /** Twilio's own way of declining a call, for a request it did not sign. */
    private static final String REJECTED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
              <Reject/>
            </Response>""";

    /**
     * Everything that must be a real value before a Twilio call can be placed.
     *
     * The auth token is on this list because it is what proves a webhook really
     * came from Twilio. Without it the /voice endpoint could not tell Twilio
     * from anyone else who found the address, so it refuses to try.
     */
    private static final List<String> REQUIRED_KEYS = List.of(
            "twilio_account_sid", "twilio_auth_token", "twilio_api_key_sid",
            "twilio_api_key_secret", "twilio_twiml_app_sid", "public_media_url");

    private final ConfigService config;
    private final CallLogService callLog;

    public TwilioController(ConfigService config, CallLogService callLog) {
        this.config = config;
        this.callLog = callLog;
    }

    // ---------------------------------------------------------------- token --

    /**
     * What the browser needs to register as a Twilio device.
     *
     * @return the token, and how long it lasts, so the page can ask again
     *         before it expires rather than after
     */
    @GetMapping("/token")
    public Map<String, Object> token() {
        String missing = firstMissingKey();
        if (missing != null) {
            log.info("A Twilio token was asked for, but {} is still a placeholder", missing);
            throw new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_TWILIO_NOT_SET_UP);
        }

        return Map.of(
                "token", mintToken(),
                "identity", IDENTITY,
                "expiresInSeconds", TOKEN_TTL_SECONDS);
    }

    /**
     * Builds and signs the token by hand.
     *
     * The shape is Twilio's: a JWT signed with the API key secret, issued by
     * the key's SID, about the account, carrying one voice grant that names the
     * TwiML application a call should be sent to.
     */
    private String mintToken() {
        long now = Instant.now().getEpochSecond();
        String apiKeySid = config.getString("twilio_api_key_sid", "");

        JsonObject header = new JsonObject();
        header.addProperty("typ", "JWT");
        header.addProperty("alg", "HS256");
        header.addProperty("cty", TOKEN_CONTENT_TYPE);

        JsonObject outgoing = new JsonObject();
        outgoing.addProperty("application_sid", config.getString("twilio_twiml_app_sid", ""));
        JsonObject voice = new JsonObject();
        voice.add("outgoing", outgoing);
        // No incoming grant. It was here so the device could be rung back for a
        // test, which is worth very little against what it costs: a token that
        // can also receive calls is a token worth stealing twice over. An
        // inbound call reaches the agent through the webhook either way.

        JsonObject grants = new JsonObject();
        grants.addProperty("identity", IDENTITY);
        grants.add("voice", voice);

        JsonObject claims = new JsonObject();
        claims.addProperty("jti", apiKeySid + "-" + now);
        claims.addProperty("iss", apiKeySid);
        claims.addProperty("sub", config.getString("twilio_account_sid", ""));
        claims.addProperty("nbf", now);
        claims.addProperty("exp", now + TOKEN_TTL_SECONDS);
        claims.add("grants", grants);

        String signingInput = encode(header.toString()) + "." + encode(claims.toString());
        return signingInput + "." + sign(signingInput);
    }

    private String sign(String signingInput) {
        String secret = config.getString("twilio_api_key_secret", "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // Only reachable on a JVM with no HMAC-SHA256, which is not a
            // condition this app can do anything sensible about.
            throw new IllegalStateException("This JVM cannot sign a Twilio token", e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------- TwiML --

    /**
     * What Twilio asks for the instant a call connects, and the only answer
     * that matters: send the audio to the voice server.
     *
     * A call placed from the panel already has a record, and its id rides along
     * as a custom parameter. A call from a real telephone has none, so one is
     * opened here — that is the difference between the two directions, and it
     * is the whole difference.
     *
     * The reply is TwiML, so a failure cannot be an HTTP error: Twilio would
     * play its own error message to a caller who is already on the line. An
     * unconfigured system says so out loud and hangs up politely instead.
     */
    @PostMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> voice(
            HttpServletRequest request,
            @RequestParam(required = false) String callId,
            @RequestParam(name = "From", required = false) String from) {
        String missing = firstMissingKey();
        if (missing != null) {
            log.warn("Twilio reached /voice but {} is still a placeholder", missing);
            return xml(sayAndHangUp(Lang.SPOKEN_TWILIO_NOT_SET_UP));
        }
        if (!signatureIsValid(request)) {
            // Nothing is opened, nothing is spoken and nothing is billed:
            // whoever sent this could not prove they were Twilio.
            log.warn("Rejected a POST to /voice that Twilio did not sign");
            return xml(REJECTED);
        }

        UUID call = resolveCall(callId, from);
        if (call == null) return xml(sayAndHangUp(Lang.SPOKEN_TWILIO_NO_BUSINESS));

        log.info("Twilio call {} connecting its media stream", call);
        return xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                  <Connect>
                    <Stream url="%s">
                      <Parameter name="callId" value="%s"/>
                    </Stream>
                  </Connect>
                </Response>""".formatted(xmlAttribute(mediaStreamUrl()), xmlAttribute(call)));
    }

    /**
     * The call this audio belongs to.
     *
     * @param from the caller's number, which is a phone call's only clue to who
     *             is ringing — it is handed to the brain as the code to look up
     * @return null when there is no active business to answer as
     */
    private UUID resolveCall(String callId, String from) {
        if (callId != null && !callId.isBlank()) {
            try {
                return UUID.fromString(callId.trim());
            } catch (IllegalArgumentException notAnId) {
                log.warn("Twilio sent a callId that is not an id: {}", callId);
            }
        }

        try {
            return callLog.start(new CallDtos.StartRequest("twilio", null, null, from)).getId();
        } catch (RuntimeException noBusiness) {
            log.warn("An inbound Twilio call could not be opened: {}", noBusiness.getMessage());
            return null;
        }
    }

    /**
     * The address Twilio should open its media socket to.
     *
     * The setting holds a public hostname — an ngrok address in a demo —
     * because the scheme is not the operator's choice: a media stream is always
     * wss, whatever they pasted in.
     */
    private String mediaStreamUrl() {
        String host = config.getString("public_media_url", "")
                .replaceFirst("^[a-zA-Z]+://", "")
                .replaceAll("/+$", "");
        return "wss://" + host + "/ws/twilio";
    }

    // ------------------------------------------------------- proving it is Twilio --

    /**
     * Whether this request really came from Twilio.
     *
     * Twilio signs every webhook: HMAC-SHA1 over the exact URL it called
     * followed by each POST parameter, sorted by name, keyed by the account's
     * auth token. Anyone can find an ngrok address — this is the only thing
     * that separates Twilio from them, and without it a stranger could open
     * calls on the account and be handed the media-stream address.
     */
    private boolean signatureIsValid(HttpServletRequest request) {
        String claimed = request.getHeader("X-Twilio-Signature");
        if (claimed == null || claimed.isBlank()) return false;

        String expected = signatureFor(config.getString("twilio_auth_token", ""),
                publicUrlOf(request), request.getParameterMap());
        // Constant-time: a byte-by-byte comparison that stops early tells an
        // attacker how much of a guessed signature was right.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                claimed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The signature Twilio would have produced for this request.
     *
     * @param parameters the POST form fields; every value of a repeated field
     *                   is appended, in order, the way Twilio does it
     */
    public static String signatureFor(String authToken, String url,
                                      Map<String, String[]> parameters) {
        StringBuilder signed = new StringBuilder(url);
        new TreeMap<>(parameters).forEach((name, values) -> {
            signed.append(name);
            for (String value : values) signed.append(value);
        });

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(signed.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JVM cannot check a Twilio signature", e);
        }
    }

    /**
     * The address Twilio thinks it called.
     *
     * It signs the URL from its own console, which is the public one — so
     * behind ngrok the request's own scheme and host are the tunnel's private
     * side and would never match. The forwarding headers carry the outside
     * address, and that is what has to be signed.
     */
    public static String publicUrlOf(HttpServletRequest request) {
        String scheme = firstHeader(request, "X-Forwarded-Proto", request.getScheme());
        String host = firstHeader(request, "X-Forwarded-Host",
                firstHeader(request, "Host", request.getServerName()));
        String query = request.getQueryString();
        return scheme + "://" + host + request.getRequestURI() + (query == null ? "" : "?" + query);
    }

    /** A forwarding header may carry a list; the first entry is the outside one. */
    private static String firstHeader(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) return fallback;
        return value.split(",")[0].trim();
    }

    private static String sayAndHangUp(String line) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                  <Say>%s</Say>
                  <Hangup/>
                </Response>""".formatted(xmlAttribute(line));
    }

    /**
     * Anything interpolated into the TwiML, made safe to interpolate.
     *
     * A quote in the media URL would close the attribute it sits in and let
     * whatever follows become TwiML verbs of its own — a `<Dial>` to a
     * premium-rate number, for instance. The values here are operator-supplied
     * rather than public, but "operator-supplied" is a property of today's
     * configuration and not of this method.
     */
    private static String xmlAttribute(Object raw) {
        return String.valueOf(raw)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    // ------------------------------------------------------------ internals --

    /** @return the first setting still holding a placeholder, or null if all are real */
    private String firstMissingKey() {
        return REQUIRED_KEYS.stream().filter(config::isPlaceholder).findFirst().orElse(null);
    }
}
