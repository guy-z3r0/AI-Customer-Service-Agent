package com.ulab.agent.domain;

import com.ulab.agent.domain.enums.KbKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One fact the agent is allowed to say about a business: a line of the About
 * text, a service with its price, a policy, or a question-and-answer pair.
 *
 * question is only filled in for FAQ entries; for the other kinds the whole
 * fact lives in content.
 */
@Entity
@Table(name = "kb_entry")
public class KbEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KbKind kind;

    private String question;

    @Column(nullable = false)
    private String content;

    /** Position within its kind, so the owner controls the reading order. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }

    public KbKind getKind() { return kind; }
    public void setKind(KbKind kind) { this.kind = kind; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
