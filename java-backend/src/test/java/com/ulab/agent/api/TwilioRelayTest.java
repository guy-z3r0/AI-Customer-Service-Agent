package com.ulab.agent.api;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a relayed media stream is sent on to.
 *
 * The relay exists so that a telephone call needs one public tunnel instead of
 * two, and the whole of it that can be tested without a live call is this: the
 * one setting naming the voice server, read as a websocket address. Get the
 * scheme wrong and the handshake fails at the moment a caller is on the line,
 * which is the most expensive place to find out.
 */
class TwilioRelayTest {

    @Test
    void theDockerAddressBecomesAWebsocketOne() {
        assertEquals(URI.create("ws://python-voice:8090/ws/twilio"),
                TwilioMediaSocket.voiceSocketUri("http://python-voice:8090"));
    }

    @Test
    void soDoesTheAddressUsedWhenRunningWithoutDocker() {
        assertEquals(URI.create("ws://localhost:8090/ws/twilio"),
                TwilioMediaSocket.voiceSocketUri("http://localhost:8090"));
    }

    @Test
    void aTlsVoiceServerKeepsItsTls() {
        // Nothing deploys this way today, but a swap of http for ws that
        // silently downgraded https would be a call's audio in clear text.
        assertEquals(URI.create("wss://voice.example:8090/ws/twilio"),
                TwilioMediaSocket.voiceSocketUri("https://voice.example:8090"));
    }

    @Test
    void aTrailingSlashDoesNotDoubleUp() {
        assertEquals(URI.create("ws://python-voice:8090/ws/twilio"),
                TwilioMediaSocket.voiceSocketUri("  http://python-voice:8090/  "));
    }
}
