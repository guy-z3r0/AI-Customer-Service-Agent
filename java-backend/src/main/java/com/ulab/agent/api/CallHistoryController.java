package com.ulab.agent.api;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.services.CallHistoryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Calls that have already happened: the list, one in full, and the download.
 *
 * Nothing here can change anything, which is the point — a transcript is a
 * record, and a record that can be edited is not one.
 */
@RestController
@RequestMapping("/api/calls")
public class CallHistoryController {

    private final CallHistoryService history;

    public CallHistoryController(CallHistoryService history) {
        this.history = history;
    }

    /** @param businessId left out means every business's calls together */
    @GetMapping
    public List<CallDtos.CallListItem> list(
            @RequestParam(required = false) UUID businessId,
            @RequestParam(required = false) Integer limit) {
        return history.list(businessId, limit == null ? CallHistoryService.DEFAULT_LIMIT : limit);
    }

    @GetMapping("/{callId}")
    public CallDtos.CallDetail detail(@PathVariable UUID callId) {
        return history.detail(callId);
    }

    /**
     * The call as a text file.
     *
     * It is served as an attachment rather than as a page so the browser saves
     * it under a name that says which call it is, which is what makes it worth
     * anything once it has left the panel.
     */
    @GetMapping("/{callId}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID callId) {
        byte[] body = history.exportText(callId).getBytes(StandardCharsets.UTF_8);
        ContentDisposition attachment = ContentDisposition.attachment()
                .filename("call-" + callId + ".txt").build();

        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header("Content-Disposition", attachment.toString())
                .body(body);
    }
}
