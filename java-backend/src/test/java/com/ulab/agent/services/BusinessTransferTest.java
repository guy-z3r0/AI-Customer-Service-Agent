package com.ulab.agent.services;

import com.ulab.agent.api.dto.BusinessUpsertRequest;
import com.ulab.agent.api.dto.BusinessView;
import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.api.dto.TransferDtos;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import com.ulab.agent.repo.KbEntryRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A business leaving as a file and coming back as the same business.
 *
 * The promise this feature makes is a round trip: what is downloaded can be
 * uploaded without editing it first. That is one property, and nearly every
 * test here is a way of asking whether it still holds — particularly for the
 * opening hours, which are a JSON object stored as a string and so cross the
 * boundary twice.
 */
class BusinessTransferTest {

    private static final UUID BUSINESS = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String HOURS =
            "{\"sat\":{\"open\":\"10:00\",\"close\":\"20:00\"},\"fri\":null}";

    private BusinessService businesses;
    private KbService kb;
    private AiSettingsRepository aiSettings;
    private KbEntryRepository kbEntries;
    private EscalationContactRepository contacts;
    private BusinessTransferService transfer;

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessService.class);
        kb = mock(KbService.class);
        aiSettings = mock(AiSettingsRepository.class);
        kbEntries = mock(KbEntryRepository.class);
        contacts = mock(EscalationContactRepository.class);
        transfer = new BusinessTransferService(businesses, kb, aiSettings, kbEntries, contacts);

        when(businesses.get(BUSINESS)).thenReturn(new BusinessView(BUSINESS, "demo-courier",
                "Demo Courier", "+8801711000000", "hello@example.com", "12 Road 4, Dhaka",
                "Asia/Dhaka", HOURS, true, 2, 5));
        when(aiSettings.findById(BUSINESS)).thenReturn(Optional.empty());
        when(kb.listForPanel(BUSINESS)).thenReturn(List.of(
                new EditorDtos.KbEntryView(UUID.randomUUID(), "ABOUT", null, "A courier.", 0),
                new EditorDtos.KbEntryView(UUID.randomUUID(), "FAQ", "How late?",
                        "Until eight.", 0)));
        when(businesses.escalationContacts(BUSINESS)).thenReturn(List.of(
                new EditorDtos.EscalationView(UUID.randomUUID(), "Owner", "owner@example.com", 1)));
    }

    // ---------------------------------------------------------------- export --

    @Test
    void theFileSaysWhatItIsAndWhichBusinessItCameFrom() {
        TransferDtos.BusinessDocument document = transfer.export(BUSINESS);

        assertEquals(TransferDtos.FORMAT, document.format());
        assertEquals(TransferDtos.VERSION, document.version());
        assertEquals("demo-courier", document.slug());
        assertEquals("Demo Courier", document.business().name());
        assertEquals(2, document.knowledge().size());
        assertEquals(1, document.escalationContacts().size());
    }

    @Test
    void theHoursAreNestedJsonRatherThanAQuotedString() {
        // The column holds a string of JSON. A file a person is expected to read
        // and edit should hold the days themselves, and a closed day has to stay
        // null rather than disappear — a missing day and a closed one look the
        // same to the panel, but only one of them survives a round trip.
        TransferDtos.BusinessPart business = transfer.export(BUSINESS).business();

        assertTrue(business.hours().isObject());
        assertEquals("10:00", business.hours().path("sat").path("open").asText());
        assertTrue(business.hours().path("fri").isNull(), "a closed day stays in the file");
    }

    @Test
    void aBusinessWithNoPersonaExportsNoneRatherThanAnInventedOne() {
        assertNull(transfer.export(BUSINESS).persona());
    }

    @Test
    void theFileIsNamedAfterTheBusiness() {
        assertEquals("business-demo-courier.json",
                BusinessTransferService.fileNameFor("demo-courier"));
        // A handle is url-safe by construction; this is about what a hand-edited
        // one could carry into a Content-Disposition header.
        assertEquals("business-a-b.json", BusinessTransferService.fileNameFor("a/b"));
    }

    // ---------------------------------------------------------------- import --

    @Test
    void anExportedFileCanBeAddedBackWithoutEditingIt() {
        TransferDtos.BusinessDocument document = transfer.export(BUSINESS);
        UUID fresh = UUID.randomUUID();
        when(businesses.create(any())).thenReturn(view(fresh, "demo-courier-2"));

        TransferDtos.ImportResultView result = transfer.importDocument(
                new TransferDtos.ImportRequest("add", null, document));

        ArgumentCaptor<BusinessUpsertRequest> sent =
                ArgumentCaptor.forClass(BusinessUpsertRequest.class);
        verify(businesses).create(sent.capture());
        assertEquals("Demo Courier", sent.getValue().name());
        assertEquals(HOURS, sent.getValue().hoursJson(), "the hours go back as they came out");
        assertEquals(2, result.knowledgeEntries());
        assertEquals(1, result.escalationContacts());
    }

    @Test
    void replacingEmptiesTheOldContentFirst() {
        TransferDtos.BusinessDocument document = transfer.export(BUSINESS);
        UUID target = UUID.randomUUID();
        when(businesses.update(eq(target), any())).thenReturn(view(target, "old-one"));

        transfer.importDocument(new TransferDtos.ImportRequest("replace", target, document));

        verify(kbEntries).deleteByBusinessId(target);
        verify(contacts).deleteByBusinessId(target);
        verify(businesses, never()).create(any());
    }

    @Test
    void replacingNeedsToBeToldWhichBusiness() {
        TransferDtos.BusinessDocument document = transfer.export(BUSINESS);

        assertThrows(RuntimeException.class, () -> transfer.importDocument(
                new TransferDtos.ImportRequest("replace", null, document)));
        verify(businesses, never()).update(any(), any());
    }

    @Test
    void somebodyElsesJsonFileIsRefusedBeforeAnythingIsWritten() {
        TransferDtos.BusinessDocument notOurs = new TransferDtos.BusinessDocument(
                "some-other-tool", 1, null, null,
                new TransferDtos.BusinessPart("Whatever", null, null, null, null, null),
                null, List.of(), List.of());

        assertThrows(RuntimeException.class, () -> transfer.importDocument(
                new TransferDtos.ImportRequest("add", null, notOurs)));
        verify(businesses, never()).create(any());
    }

    @Test
    void aFileFromANewerVersionIsRefusedRatherThanHalfUnderstood() {
        TransferDtos.BusinessDocument fromTheFuture = new TransferDtos.BusinessDocument(
                TransferDtos.FORMAT, TransferDtos.VERSION + 1, null, null,
                new TransferDtos.BusinessPart("Whatever", null, null, null, null, null),
                null, List.of(), List.of());

        assertThrows(RuntimeException.class, () -> transfer.importDocument(
                new TransferDtos.ImportRequest("add", null, fromTheFuture)));
        verify(businesses, never()).create(any());
    }

    @Test
    void aModeNobodyOffersIsRefused() {
        TransferDtos.BusinessDocument document = transfer.export(BUSINESS);

        assertThrows(RuntimeException.class, () -> transfer.importDocument(
                new TransferDtos.ImportRequest("merge", null, document)));
    }

    @Test
    void thePersonaGoesBackThroughTheSameFormTheEditorPostsTo() {
        TransferDtos.BusinessDocument document = new TransferDtos.BusinessDocument(
                TransferDtos.FORMAT, TransferDtos.VERSION, null, null,
                new TransferDtos.BusinessPart("Demo Courier", null, null, null, null, null),
                new TransferDtos.PersonaPart("Rina", "You answer the phone.", "Warm and short.",
                        "Hello.", "হ্যালো।", null, null, new BigDecimal("0.7"), 20),
                List.of(), List.of());
        UUID fresh = UUID.randomUUID();
        when(businesses.create(any())).thenReturn(view(fresh, "demo-courier"));

        transfer.importDocument(new TransferDtos.ImportRequest("add", null, document));

        ArgumentCaptor<EditorDtos.AiSettingsRequest> sent =
                ArgumentCaptor.forClass(EditorDtos.AiSettingsRequest.class);
        verify(businesses).updateAiSettings(eq(fresh), sent.capture());
        assertEquals("Rina", sent.getValue().personaName());
    }

    // -------------------------------------------------- what a file may hold --

    @Test
    void aPlaceholderHandoverAddressIsAllowedThroughLikeAnyOtherValue() {
        // Found by exporting a seeded business and uploading the file unedited,
        // which is the first thing anybody does. A fresh install seeds its
        // escalation contact as PLACEHOLDER_ESCALATION_EMAIL, so a plain @Email
        // meant the app refused its own file.
        assertTrue(rejects("PLACEHOLDER_ESCALATION_EMAIL").isEmpty(),
                "a placeholder is this project's word for 'not set yet'");
        assertTrue(rejects("owner@example.com").isEmpty());
        assertFalse(rejects("not an address at all").isEmpty(),
                "which is not a licence to accept anything");
    }

    private static Set<ConstraintViolation<TransferDtos.ContactPart>> rejects(String email) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator()
                    .validate(new TransferDtos.ContactPart("Owner", email, 1));
        }
    }

    private static BusinessView view(UUID id, String slug) {
        return new BusinessView(id, slug, "Demo Courier", null, null, null, "Asia/Dhaka",
                "{}", false, 0, 0);
    }
}
