package com.ulab.agent.api;

import com.ulab.agent.api.dto.ReportDtos;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.services.CallHistoryService;
import com.ulab.agent.services.CallReportService;
import com.ulab.agent.services.ConfigService;
import com.ulab.agent.services.LegacyImportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The report is reached by a path that sits next to a path made of an id.
 *
 * /api/calls/report and /api/calls/{callId} both match a request for
 * /api/calls/report, and only one of them is right. If the templated one ever
 * wins, "report" is handed to the id reader, fails to parse as a UUID, and the
 * report answers 400 for a reason nothing in the report's own code could
 * explain. That is not a thing to find out about from a browser, so it is
 * pinned here.
 */
@WebMvcTest(controllers = CallHistoryController.class)
@Import({SecurityConfig.class, ApiExceptionAdvice.class})
@TestPropertySource(properties = {
        "PANEL_USER=operator",
        "PANEL_PASSWORD=a-test-password"
})
class CallReportRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CallHistoryService history;

    @MockitoBean
    private CallReportService reports;

    @MockitoBean
    private BusinessRepository businesses;

    @MockitoBean
    private ConfigService config;

    /** Main starts the legacy importer on boot; the slice still has to satisfy it. */
    @MockitoBean
    private LegacyImportService importer;

    @Test
    void reportIsAWordAndNotACallId() throws Exception {
        when(reports.report(isNull(), any(), any())).thenReturn(emptyReport());

        mvc.perform(get("/api/calls/report").with(operator())).andExpect(status().isOk());

        verify(reports).report(isNull(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void aReportAskedForWithNoRangeIsTheLastThirtyDays() throws Exception {
        when(reports.report(isNull(), any(), any())).thenReturn(emptyReport());

        mvc.perform(get("/api/calls/report").with(operator())).andExpect(status().isOk());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(reports).report(isNull(), from.capture(), to.capture());

        assertEquals(LocalDate.now(), to.getValue(), "up to and including today");
        assertEquals(LocalDate.now().minusDays(CallReportService.DEFAULT_DAYS - 1L),
                from.getValue(), "thirty days counted, not thirty-one");
    }

    @Test
    void theRangeAndTheBusinessAreUsedExactlyAsAsked() throws Exception {
        UUID businessId = UUID.randomUUID();
        when(reports.report(any(), any(), any())).thenReturn(emptyReport());

        mvc.perform(get("/api/calls/report")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("businessId", businessId.toString())
                        .with(operator()))
                .andExpect(status().isOk());

        verify(reports).report(eq(businessId), eq(LocalDate.of(2026, 7, 1)),
                eq(LocalDate.of(2026, 7, 31)));
    }

    @Test
    void aDayThatIsNotADateIsRefusedRatherThanIgnored() throws Exception {
        // Silently reporting on the last thirty days instead would be a report
        // that quietly answers a question nobody asked.
        mvc.perform(get("/api/calls/report").param("from", "last Tuesday").with(operator()))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
        return httpBasic("operator", "a-test-password");
    }

    private static ReportDtos.CallReport emptyReport() {
        return new ReportDtos.CallReport(null, "2026-07-01", "2026-07-31",
                Instant.now().toString(),
                new ReportDtos.Totals(0, 0, 0, 0, 0, 0, 0, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
