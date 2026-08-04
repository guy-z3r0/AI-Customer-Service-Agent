package com.ulab.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A business the agent answers calls for.
 *
 * The slug is the stable, url-safe handle ("template-business"); the name is
 * what people read. Only one business may have active = true at a time, which
 * the database enforces with a partial unique index.
 */
@Entity
@Table(name = "business")
public class Business {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    private String phone;
    private String email;
    private String address;

    @Column(nullable = false)
    private String timezone = "Asia/Dhaka";

    /** Opening hours keyed by short weekday name; a null day means closed. */
    @Column(name = "hours_json", columnDefinition = "jsonb", nullable = false)
    private String hoursJson = "{}";

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getHoursJson() { return hoursJson; }
    public void setHoursJson(String hoursJson) { this.hoursJson = hoursJson; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
