package com.ulab.agent.api;

import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.services.BusinessService;
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
import java.util.UUID;

/**
 * Who the agent is when it answers for this business, and who it hands a call
 * to when it cannot.
 *
 * A business that has never been given a persona gets a default one the first
 * time this is read, so the editor always has something to open rather than a
 * blank form and a 404.
 */
@RestController
@RequestMapping("/api/businesses/{businessId}")
public class AiSettingsController {

    private final BusinessService businesses;

    public AiSettingsController(BusinessService businesses) {
        this.businesses = businesses;
    }

    @GetMapping("/ai-settings")
    public EditorDtos.AiSettingsView aiSettings(@PathVariable UUID businessId) {
        return businesses.aiSettings(businessId);
    }

    @PutMapping("/ai-settings")
    public EditorDtos.AiSettingsView updateAiSettings(
            @PathVariable UUID businessId,
            @Valid @RequestBody EditorDtos.AiSettingsRequest request) {
        return businesses.updateAiSettings(businessId, request);
    }

    @GetMapping("/escalation")
    public List<EditorDtos.EscalationView> escalation(@PathVariable UUID businessId) {
        return businesses.escalationContacts(businessId);
    }

    @PostMapping("/escalation")
    @ResponseStatus(HttpStatus.CREATED)
    public EditorDtos.EscalationView addContact(
            @PathVariable UUID businessId,
            @Valid @RequestBody EditorDtos.EscalationRequest request) {
        return businesses.addEscalationContact(businessId, request);
    }

    @PutMapping("/escalation/{contactId}")
    public EditorDtos.EscalationView updateContact(
            @PathVariable UUID businessId, @PathVariable UUID contactId,
            @Valid @RequestBody EditorDtos.EscalationRequest request) {
        return businesses.updateEscalationContact(contactId, request);
    }

    @DeleteMapping("/escalation/{contactId}")
    public ResponseEntity<Void> deleteContact(@PathVariable UUID businessId,
                                              @PathVariable UUID contactId) {
        businesses.deleteEscalationContact(contactId);
        return ResponseEntity.noContent().build();
    }
}
