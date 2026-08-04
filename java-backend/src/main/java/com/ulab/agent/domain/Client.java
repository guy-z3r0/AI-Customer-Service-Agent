package com.ulab.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer of one business.
 *
 * Phone and email are stored encrypted (pgcrypto) rather than as plain text,
 * so a copy of the database is not a copy of everyone's contact details. The
 * encrypted bytes are read and written through SQL functions in ClientService
 * (Phase 5); this class only carries them around.
 */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    /** Short handle the caller can read out, e.g. "C001". Unique per business. */
    @Column(name = "client_code", nullable = false)
    private String clientCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone_enc")
    private byte[] phoneEnc;

    @Column(name = "email_enc")
    private byte[] emailEnc;

    private String notes;

    /** JSON array of past problems, newest last. */
    @Column(name = "past_issues_json", columnDefinition = "jsonb", nullable = false)
    private String pastIssuesJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }

    public String getClientCode() { return clientCode; }
    public void setClientCode(String clientCode) { this.clientCode = clientCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public byte[] getPhoneEnc() { return phoneEnc; }
    public void setPhoneEnc(byte[] phoneEnc) { this.phoneEnc = phoneEnc; }

    public byte[] getEmailEnc() { return emailEnc; }
    public void setEmailEnc(byte[] emailEnc) { this.emailEnc = emailEnc; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPastIssuesJson() { return pastIssuesJson; }
    public void setPastIssuesJson(String pastIssuesJson) { this.pastIssuesJson = pastIssuesJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
