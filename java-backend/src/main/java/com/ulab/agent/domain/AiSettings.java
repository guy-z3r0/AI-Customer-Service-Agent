package com.ulab.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The agent's personality and model choice for one business. There is exactly
 * one row per business, so the business id doubles as the primary key.
 *
 * providerOverride and modelOverride are normally null, meaning "use whatever
 * the global Settings page says". Setting them lets one business run on a
 * different language model from the rest.
 */
@Entity
@Table(name = "ai_settings")
public class AiSettings {

    @Id
    @Column(name = "business_id")
    private UUID businessId;

    /** The name the agent introduces itself with. */
    @Column(name = "persona_name", nullable = false)
    private String personaName;

    @Column(name = "role_description", nullable = false)
    private String roleDescription;

    @Column(name = "reply_style", nullable = false)
    private String replyStyle;

    @Column(name = "greeting_en", nullable = false)
    private String greetingEn;

    @Column(name = "greeting_bn", nullable = false)
    private String greetingBn;

    @Column(name = "provider_override")
    private String providerOverride;

    @Column(name = "model_override")
    private String modelOverride;

    @Column(nullable = false)
    private BigDecimal temperature = new BigDecimal("0.70");

    /** How many past turns are replayed to the model on each new turn. */
    @Column(name = "max_history_turns", nullable = false)
    private int maxHistoryTurns = 20;

    public UUID getBusinessId() { return businessId; }
    public void setBusinessId(UUID businessId) { this.businessId = businessId; }

    public String getPersonaName() { return personaName; }
    public void setPersonaName(String personaName) { this.personaName = personaName; }

    public String getRoleDescription() { return roleDescription; }
    public void setRoleDescription(String roleDescription) { this.roleDescription = roleDescription; }

    public String getReplyStyle() { return replyStyle; }
    public void setReplyStyle(String replyStyle) { this.replyStyle = replyStyle; }

    public String getGreetingEn() { return greetingEn; }
    public void setGreetingEn(String greetingEn) { this.greetingEn = greetingEn; }

    public String getGreetingBn() { return greetingBn; }
    public void setGreetingBn(String greetingBn) { this.greetingBn = greetingBn; }

    public String getProviderOverride() { return providerOverride; }
    public void setProviderOverride(String providerOverride) { this.providerOverride = providerOverride; }

    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }

    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }

    public int getMaxHistoryTurns() { return maxHistoryTurns; }
    public void setMaxHistoryTurns(int maxHistoryTurns) { this.maxHistoryTurns = maxHistoryTurns; }
}
