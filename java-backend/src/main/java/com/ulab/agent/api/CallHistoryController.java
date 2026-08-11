package com.ulab.agent.api;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ReportDtos;
import com.ulab.agent.services.CallHistoryService;
import com.ulab.agent.services.CallReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Calls that have already happened: the list, one in full, the download, and
 * the report over many of them at once.
 *
 * Nothing here can change anything, which is the point — a transcript is a
 * record, and a record that can be edited is not one.
 */
@RestController
@RequestMapping("/api/calls")
public class CallHistoryController {

    private final CallHistoryService history;
    private final CallReportService reports;

    public CallHistoryController(CallHistoryService history, CallReportService reports) {
        this.history = history;
        this.reports = reports;
    }

    /** @param businessId left out means every business's calls together */
    @GetMapping
    public List<CallDtos.CallListItem> list(
            @RequestParam(required = false) UUID businessId,
            @RequestParam(required = false) Integer limit) {
        return history.list(businessId, limit == null ? CallHistoryService.DEFAULT_LIMIT : limit);
    }

    /**
     * Many calls read at once, over a stretch of days.
     *
     * Both ends of the range are optional: asked with neither, it answers for
     * the last thirty days, which is what somebody asking for "a report"
     * means. This is mapped above /{callId} on purpose — "report" is a word,
     * not a call id, and the literal path has to be the one that matches.
     *
     * @param from the first day counted; the thirtieth day back when left out
     * @param to   the last day counted, included in full; today when left out
     */
    @GetMapping("/report")
    public ReportDtos.CallReport report(
            @RequestParam(required = false) UUID businessId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        LocalDate last = to == null ? LocalDate.now() : to;
        LocalDate first = from == null ? last.minusDays(CallReportService.DEFAULT_DAYS - 1L) : from;
        return reports.report(businessId, first, last);
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
