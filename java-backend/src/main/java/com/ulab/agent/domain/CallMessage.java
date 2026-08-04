package com.ulab.agent.domain;

import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.MessageRole;
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
 * One line of a call transcript.
 *
 * The three timestamps are the raw material of the latency readout, kept per
 * turn instead of as an average so a slow turn can be traced to its stage:
 * tSttFinal is when speech recognition finished, tLlmFirst when the model's
 * first word arrived, tTtsFirst when the first audio byte went out.
 */
@Entity
@Table(name = "call_message")
public class CallMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "call_id", nullable = false)
    private UUID callId;

    /** Position in the call, starting at 1. Unique within a call. */
    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    private Language language;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_at_time")
    private CallMode modeAtTime;

    @Column(name = "t_stt_final")
    private Instant tSttFinal;

    @Column(name = "t_llm_first")
    private Instant tLlmFirst;

    @Column(name = "t_tts_first")
    private Instant tTtsFirst;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCallId() { return callId; }
    public void setCallId(UUID callId) { this.callId = callId; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public MessageRole getRole() { return role; }
    public void setRole(MessageRole role) { this.role = role; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }

    public CallMode getModeAtTime() { return modeAtTime; }
    public void setModeAtTime(CallMode modeAtTime) { this.modeAtTime = modeAtTime; }

    public Instant getTSttFinal() { return tSttFinal; }
    public void setTSttFinal(Instant tSttFinal) { this.tSttFinal = tSttFinal; }

    public Instant getTLlmFirst() { return tLlmFirst; }
    public void setTLlmFirst(Instant tLlmFirst) { this.tLlmFirst = tLlmFirst; }

    public Instant getTTtsFirst() { return tTtsFirst; }
    public void setTTtsFirst(Instant tTtsFirst) { this.tTtsFirst = tTtsFirst; }
}
