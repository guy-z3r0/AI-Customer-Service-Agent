package com.ulab.agent.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The shapes a customer record takes on its way in and out.
 *
 * Phone and email are plain text here and encrypted bytes in the database.
 * Everything above ClientService works with this record and never sees the
 * ciphertext, which is the point: one class knows how to open the box.
 */
public final class ClientDtos {

    private ClientDtos() {
    }

    /**
     * @param pastIssues what this customer has needed before, oldest first
     * @param contactReadable false when the stored rows could not be decrypted,
     *                        which means the PII key has changed since they were
     *                        written; the record is still shown, without contacts
     */
    public record ClientView(UUID id, UUID businessId, String clientCode, String name,
                             String phone, String email, String notes,
                             List<String> pastIssues, boolean contactReadable,
                             Instant createdAt) {
    }

    /**
     * @param clientCode left blank on create, in which case the next free code
     *                   for the business is allocated
     */
    public record ClientUpsertRequest(
            @Size(max = 40) String clientCode,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 40) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 2000) String notes,
            List<String> pastIssues) {
    }
}
