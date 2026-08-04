package com.ulab.agent.api;

import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.services.KbService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a business is willing to have said about it.
 *
 * Everything here reaches the next call's prompt with no restart, in the order
 * it is listed — which is why moving an entry up the list is an endpoint of its
 * own rather than a field on the entry.
 */
@RestController
@RequestMapping("/api/businesses/{businessId}/kb")
public class KbController {

    private final KbService kb;

    public KbController(KbService kb) {
        this.kb = kb;
    }

    @GetMapping
    public List<EditorDtos.KbEntryView> list(@PathVariable UUID businessId) {
        return kb.listForPanel(businessId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EditorDtos.KbEntryView create(@PathVariable UUID businessId,
                                         @Valid @RequestBody EditorDtos.KbUpsertRequest request) {
        return kb.create(businessId, request);
    }

    @PutMapping("/{entryId}")
    public EditorDtos.KbEntryView update(@PathVariable UUID businessId, @PathVariable UUID entryId,
                                         @Valid @RequestBody EditorDtos.KbUpsertRequest request) {
        return kb.update(entryId, request);
    }

    /** @param direction "up" moves the entry earlier in the prompt */
    @PostMapping("/{entryId}/move")
    public Map<String, Boolean> move(@PathVariable UUID businessId, @PathVariable UUID entryId,
                                     @RequestBody Map<String, String> body) {
        return Map.of("moved", kb.move(entryId, "up".equalsIgnoreCase(body.get("direction"))));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(@PathVariable UUID businessId, @PathVariable UUID entryId) {
        kb.delete(entryId);
        return ResponseEntity.noContent().build();
    }
}
