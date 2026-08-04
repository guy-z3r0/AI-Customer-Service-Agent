package com.ulab.agent.domain;

import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.Telephony;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The header row of one call. Its transcript lives in CallMessage, its screening
 * history in ModeTransition, and its written summary in CallSummary.
 *
 * clientId stays null when the caller was never identified — that is the normal
 * state for a new customer.
 */
@Entity
@Table(name = "call_record")
public class CallRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_mode")
    private CallMode finalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_language")
    private Language finalLanguage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Telephony telephony = Telephony.BROWSER;

    /** Why the call stopped: hung up, spam, inactivity, error. */
    @Column(name = "termination_reason")
    private String terminationReason;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public CallMode getFinalMode() { return finalMode; }
    public void setFinalMode(CallMode finalMode) { this.finalMode = finalMode; }

    public Language getFinalLanguage() { return finalLanguage; }
    public void setFinalLanguage(Language finalLanguage) { this.finalLanguage = finalLanguage; }

    public Telephony getTelephony() { return telephony; }
    public void setTelephony(Telephony telephony) { this.telephony = telephony; }

    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String terminationReason) { this.terminationReason = terminationReason; }
}
