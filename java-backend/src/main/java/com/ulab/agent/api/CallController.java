package com.ulab.agent.api;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.brain.ConversationBrain;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.utils.Lang;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Starting and ending a call.
 *
 * The panel calls /start, is told which websocket to send its microphone to,
 * and from then on the conversation runs over two sockets rather than through
 * here: audio between the browser and the voice server, words between the
 * voice server and this backend's /ws/turn/{callId}. Keeping the audio path
 * that short is where the two-second reply budget is won.
 *
 * /end exists for the page's own hang-up button. The voice server reports the
 * end over its socket, so a call that drops without anyone pressing anything
 * still gets written down.
 *
 * Choosing a screening mode by hand arrives with the mode machine in Phase 4.
 */
@RestController
@RequestMapping("/api/call")
public class CallController {

    private final CallLogService callLog;
    private final ConversationBrain brain;

    /**
     * The websocket address the browser should dial. It is served from here
     * rather than written into the page because it differs between Docker and
     * a laptop, and only the server knows which one it is running under.
     */
    private final String voiceWsUrl;

    public CallController(CallLogService callLog, ConversationBrain brain,
                          @Value("${voice.public-ws-url:ws://localhost:8090}") String voiceWsUrl) {
        this.callLog = callLog;
        this.brain = brain;
        this.voiceWsUrl = voiceWsUrl.replaceAll("/+$", "");
    }

    @PostMapping("/start")
    public CallDtos.StartView start(@RequestBody(required = false) CallDtos.StartRequest request) {
        CallDtos.StartRequest safe = request == null
                ? new CallDtos.StartRequest("browser", null, null) : request;
        CallRecord call = callLog.start(safe);
        return new CallDtos.StartView(
                call.getId(),
                voiceWsUrl + "/ws/browser/" + call.getId(),
                call.getFinalLanguage().name().toLowerCase(),
                call.getTelephony().name().toLowerCase());
    }

    /**
     * The operator's manual override. The same legality table that refuses the
     * model refuses a person, so a call already written off as a wrong number
     * cannot be talked back into being a customer from the panel either.
     */
    @PostMapping("/{callId}/mode")
    public Map<String, String> mode(@PathVariable UUID callId,
                                    @Valid @RequestBody CallDtos.ModeRequest request) {
        CallMode wanted = modeOf(request.mode());
        if (wanted == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Lang.ERR_VALIDATION);
        }
        if (!brain.onOperatorMode(callId, wanted, request.reason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_MODE_NOT_ALLOWED);
        }
        return Map.of("mode", wanted.name());
    }

    private static CallMode modeOf(String raw) {
        try {
            return CallMode.valueOf(raw.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException notAMode) {
            return null;
        }
    }

    @PostMapping("/{callId}/end")
    public ResponseEntity<Void> end(@PathVariable UUID callId,
                                    @RequestBody(required = false) CallDtos.EndRequest request) {
        brain.onCallEnd(callId, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }
}
