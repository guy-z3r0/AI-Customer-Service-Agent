package com.ulab.agent.api.dto;

import java.util.UUID;

/**
 * A business as the panel's Businesses table shows it. The two counts are
 * looked up separately rather than mapped as entity relationships, so listing
 * businesses never drags their whole knowledge base along with it.
 *
 * @param hoursJson raw opening-hours JSON, rendered by the panel
 */
public record BusinessView(UUID id, String slug, String name, String phone, String email,
                           String address, String timezone, String hoursJson, boolean active,
                           long kbEntryCount, long clientCount) {
}
