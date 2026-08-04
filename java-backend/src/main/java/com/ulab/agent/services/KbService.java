package com.ulab.agent.services;

import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.KbKind;
import com.ulab.agent.repo.KbEntryRepository;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Everything a business has told the agent it may say: its About text, its
 * services, its policies and its answers to common questions.
 *
 * The order matters. Entries come back in the order their owner put them in,
 * because that order becomes the order they appear in the prompt, and a model
 * pays more attention to what it reads first. Moving one up the list is
 * therefore an editorial act, not a cosmetic one.
 */
@Service
public class KbService {

    private static final Logger log = LoggerFactory.getLogger(KbService.class);

    private final KbEntryRepository entries;

    public KbService(KbEntryRepository entries) {
        this.entries = entries;
    }

    // ---------------------------------------------------------------- reads --

    @Transactional(readOnly = true)
    public List<KbEntry> forBusiness(UUID businessId) {
        return entries.findByBusinessIdOrderByKindAscSortOrderAsc(businessId);
    }

    @Transactional(readOnly = true)
    public List<KbEntry> ofKind(UUID businessId, KbKind kind) {
        return entries.findByBusinessIdAndKindOrderBySortOrderAsc(businessId, kind);
    }

    @Transactional(readOnly = true)
    public List<EditorDtos.KbEntryView> listForPanel(UUID businessId) {
        return forBusiness(businessId).stream().map(KbService::toView).toList();
    }

    // --------------------------------------------------------------- writes --

    @Transactional
    public EditorDtos.KbEntryView create(UUID businessId, EditorDtos.KbUpsertRequest request) {
        KbKind kind = kindOf(request.kind());
        KbEntry entry = new KbEntry();
        entry.setBusinessId(businessId);
        entry.setKind(kind);
        apply(entry, request);
        // New entries land at the end of their own section unless told otherwise.
        entry.setSortOrder(request.sortOrder() != null
                ? request.sortOrder() : ofKind(businessId, kind).size());

        entries.save(entry);
        log.info("Knowledge entry added to {} ({})", businessId, kind);
        return toView(entry);
    }

    @Transactional
    public EditorDtos.KbEntryView update(UUID entryId, EditorDtos.KbUpsertRequest request) {
        KbEntry entry = require(entryId);
        entry.setKind(kindOf(request.kind()));
        apply(entry, request);
        if (request.sortOrder() != null) entry.setSortOrder(request.sortOrder());

        entries.save(entry);
        return toView(entry);
    }

    @Transactional
    public void delete(UUID entryId) {
        entries.delete(require(entryId));
    }

    /**
     * Swaps an entry with its neighbour in the same section.
     *
     * @param up true to move it earlier in the prompt
     * @return true when it moved; false when it was already at the end it was
     *         being pushed towards
     */
    @Transactional
    public boolean move(UUID entryId, boolean up) {
        KbEntry entry = require(entryId);
        List<KbEntry> section = ofKind(entry.getBusinessId(), entry.getKind());

        int at = indexOf(section, entryId);
        int swapWith = up ? at - 1 : at + 1;
        if (at < 0 || swapWith < 0 || swapWith >= section.size()) return false;

        KbEntry neighbour = section.get(swapWith);
        int mine = entry.getSortOrder();
        entry.setSortOrder(neighbour.getSortOrder());
        neighbour.setSortOrder(mine);
        entries.saveAll(List.of(entry, neighbour));
        return true;
    }

    // ------------------------------------------------------------ internals --

    private KbEntry require(UUID entryId) {
        return entries.findById(entryId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_KB_NOT_FOUND));
    }

    private static void apply(KbEntry entry, EditorDtos.KbUpsertRequest request) {
        entry.setQuestion(request.question() == null || request.question().isBlank()
                ? null : request.question().trim());
        entry.setContent(request.content().trim());
    }

    private static int indexOf(List<KbEntry> section, UUID entryId) {
        for (int i = 0; i < section.size(); i++) {
            if (section.get(i).getId().equals(entryId)) return i;
        }
        return -1;
    }

    private static KbKind kindOf(String raw) {
        try {
            return KbKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException notAKind) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Lang.ERR_VALIDATION);
        }
    }

    private static EditorDtos.KbEntryView toView(KbEntry entry) {
        return new EditorDtos.KbEntryView(entry.getId(), entry.getKind().name(),
                entry.getQuestion(), entry.getContent(), entry.getSortOrder());
    }
}
