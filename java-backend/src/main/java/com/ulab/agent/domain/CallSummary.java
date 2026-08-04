package com.ulab.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What the language model made of a finished call: a paragraph a person can
 * read, plus the same information as JSON for the history page and the
 * escalation email. One row per call, so the call id is the primary key.
 */
@Entity
@Table(name = "call_summary")
public class CallSummary {

    @Id
    @Column(name = "call_id")
    private UUID callId;

    @Column(name = "summary_text", nullable = false)
    private String summaryText;

    /** Structured fields such as caller intent, sentiment and outcome. */
    @Column(name = "structured_json", columnDefinition = "jsonb", nullable = false)
    private String structuredJson = "{}";

    /** JSON array of follow-up tasks the call produced. */
    @Column(name = "action_items_json", columnDefinition = "jsonb", nullable = false)
    private String actionItemsJson = "[]";

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public UUID getCallId() { return callId; }
    public void setCallId(UUID callId) { this.callId = callId; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public String getStructuredJson() { return structuredJson; }
    public void setStructuredJson(String structuredJson) { this.structuredJson = structuredJson; }

    public String getActionItemsJson() { return actionItemsJson; }
    public void setActionItemsJson(String actionItemsJson) { this.actionItemsJson = actionItemsJson; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
