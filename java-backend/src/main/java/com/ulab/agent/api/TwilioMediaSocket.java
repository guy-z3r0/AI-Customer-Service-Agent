package com.ulab.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Twilio's media stream, passed straight through to the voice server, at
 * /ws/twilio.
 *
 * Twilio has to reach this machine from the internet, and a free tunnel gives
 * out exactly one public hostname. The webhook needs the backend on 8080 and
 * the audio needs the voice server on 8090, so a two-service split meant two
 * tunnels — which that plan cannot give. This handler is the second address,
 * moved inside: Twilio connects here, and every frame is relayed over the
 * private network to the voice server's own /ws/twilio, which is unchanged.
 *
 * It reads nothing it carries. Media Streams is spoken at both ends — Twilio's
 * and {@code transports/twilio_ws.py}'s — and neither can tell this sits
 * between them. Knowing nothing is what makes that true: there is no protocol
 * here to fall behind the protocol at either end.
 */
@Component
public class TwilioMediaSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TwilioMediaSocket.class);

    /** The voice server's own Media Streams route, which this one feeds. */
    private static final String VOICE_PATH = "/ws/twilio";

    /**
     * How long to wait for the voice server to accept the relayed socket.
     *
     * Short, because a caller is already on the line while this runs: the two
     * services sit on the same private network, and one that has not answered
     * in five seconds is not about to carry a conversation.
     */
    private static final long CONNECT_TIMEOUT_SECONDS = 5;

    /** Where each Twilio session keeps its own end of the pair. */
    private static final String VOICE_END = "voiceEnd";

    private final WebSocketClient client = new StandardWebSocketClient();
    private final URI voiceUri;

    public TwilioMediaSocket(
            @Value("${voice.internal-url:http://localhost:8090}") String voiceUrl) {
        this.voiceUri = voiceSocketUri(voiceUrl);
    }

    /**
     * The voice server's websocket address, worked out from its http one.
     *
     * There is one setting naming the voice server; the health check reads it
     * over http and this dials it over ws. Deriving the second from the first
     * rather than adding a setting is what stops the two ever pointing at
     * different servers.
     */
    static URI voiceSocketUri(String voiceUrl) {
        String base = voiceUrl.trim().replaceAll("/+$", "");
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        }
        return URI.create(base + VOICE_PATH);
    }

    /**
     * Opens the far half of the pair before Twilio's first frame arrives.
     *
     * Connecting here rather than on the first media event is what lets the
     * relay stay dumb: with both ends up front, every frame is a send, and no
     * frame has to be held anywhere or inspected to decide whether it is the
     * one that starts a call.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession fromTwilio) {
        try {
            WebSocketSession toVoice = client
                    .execute(new VoiceEnd(fromTwilio), new WebSocketHttpHeaders(), voiceUri)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            fromTwilio.getAttributes().put(VOICE_END, toVoice);
            log.info("Relaying a Twilio media stream to {}", voiceUri);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close(fromTwilio, CloseStatus.SERVICE_RESTARTED);
        } catch (Exception unreachable) {
            // There is nothing to relay to, so the stream ends — which is what
            // Twilio would meet if this endpoint did not exist at all.
            log.warn("A Twilio media stream could not reach the voice server at {}: {}",
                    voiceUri, unreachable.toString());
            close(fromTwilio, CloseStatus.SERVICE_RESTARTED);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession fromTwilio, TextMessage message) {
        relay(voiceEndOf(fromTwilio), message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession fromTwilio, CloseStatus status) {
        log.info("The Twilio end of a media stream closed ({})", status.getCode());
        close(voiceEndOf(fromTwilio), CloseStatus.NORMAL);
    }

    @Override
    public void handleTransportError(WebSocketSession fromTwilio, Throwable error) {
        log.debug("The Twilio end of a media stream failed: {}", error.toString());
        close(voiceEndOf(fromTwilio), CloseStatus.NORMAL);
    }

    // ------------------------------------------------------------ internals --

    /**
     * The voice server's end of one relayed call.
     *
     * One per call rather than one shared handler, because it holds the Twilio
     * session its frames belong to — a singleton would have to look that up
     * from something inside the audio, which is exactly the protocol knowledge
     * this relay is built not to have.
     */
    private static final class VoiceEnd extends TextWebSocketHandler {

        private final WebSocketSession toTwilio;

        private VoiceEnd(WebSocketSession toTwilio) {
            this.toTwilio = toTwilio;
        }

        @Override
        protected void handleTextMessage(WebSocketSession voice, TextMessage message) {
            relay(toTwilio, message);
        }

        /**
         * A half-open pair is worse than a closed one: Twilio would go on
         * sending a whole call's audio into a socket with nothing behind it. So
         * whichever end goes first takes the other with it.
         *
         * The status is not passed along. Codes like 1006 are how a socket
         * reports being dropped without a close frame, and are not codes a
         * close frame may carry — relaying one would fail on the way out.
         */
        @Override
        public void afterConnectionClosed(WebSocketSession voice, CloseStatus status) {
            log.info("The voice server's end of a media stream closed ({})", status.getCode());
            close(toTwilio, CloseStatus.NORMAL);
        }

        @Override
        public void handleTransportError(WebSocketSession voice, Throwable error) {
            log.debug("The voice server's end of a media stream failed: {}", error.toString());
            close(toTwilio, CloseStatus.NORMAL);
        }
    }

    private static WebSocketSession voiceEndOf(WebSocketSession fromTwilio) {
        return (WebSocketSession) fromTwilio.getAttributes().get(VOICE_END);
    }

    /**
     * A session is not safe for two threads, and each of these is written by
     * the other end's reader thread while its own may be closing it.
     */
    private static void relay(WebSocketSession to, TextMessage message) {
        if (to == null) return;
        try {
            synchronized (to) {
                if (to.isOpen()) to.sendMessage(message);
            }
        } catch (Exception e) {
            // Mid-call this means the far end went away, which the close
            // handlers deal with; there is nothing useful to do with the frame.
            log.debug("Could not relay a media frame: {}", e.toString());
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        if (session == null) return;
        try {
            synchronized (session) {
                if (session.isOpen()) session.close(status);
            }
        } catch (Exception e) {
            log.debug("Could not close the other end of a media stream: {}", e.toString());
        }
    }
}
