package com.ulab.agent.domain;

import com.ulab.agent.domain.enums.CallMode;
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
 * A record of the agent (or the operator) deciding a call belongs in a
 * different mode. fromMode is null for the very first entry, which is the mode
 * the call opened in.
 */
@Entity
@Table(name = "mode_transition")
public class ModeTransition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "call_id", nullable = false)
    private UUID callId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_mode")
    private CallMode fromMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_mode", nullable = false)
    private CallMode toMode;

    /** Plain-language reason, e.g. "caller asked for a refund we cannot approve". */
    private String reason;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCallId() { return callId; }
    public void setCallId(UUID callId) { this.callId = callId; }

    public CallMode getFromMode() { return fromMode; }
    public void setFromMode(CallMode fromMode) { this.fromMode = fromMode; }

    public CallMode getToMode() { return toMode; }
    public void setToMode(CallMode toMode) { this.toMode = toMode; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
