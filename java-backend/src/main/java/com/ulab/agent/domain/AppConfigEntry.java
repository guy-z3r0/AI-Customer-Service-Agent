package com.ulab.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One global setting, as edited on the panel's Settings page.
 *
 * The value is stored as JSON rather than plain text so one table can hold
 * strings, numbers and booleans without a column per type. ConfigService is
 * the only class that should read or write this raw JSON.
 */
@Entity
@Table(name = "app_config")
public class AppConfigEntry {

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "value_json", columnDefinition = "jsonb", nullable = false)
    private String valueJson;

    /**
     * Only JPA builds these. New settings keys are introduced by a Flyway
     * migration, never by application code, so that the panel and the database
     * can never disagree about which keys exist.
     */
    protected AppConfigEntry() {
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValueJson() { return valueJson; }
    public void setValueJson(String valueJson) { this.valueJson = valueJson; }
}
