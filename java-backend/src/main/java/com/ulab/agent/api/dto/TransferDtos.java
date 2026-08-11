package com.ulab.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A whole business as one file: what is downloaded, and what may be uploaded.
 *
 * The same record is both directions on purpose. A file that came out of here
 * has to go back in without editing, so there is one shape rather than an
 * export shape and an import shape that drift apart the first time a field is
 * added to one of them.
 *
 * What is deliberately not in it:
 *
 *  - <b>Customers.</b> Their phone and email are encrypted in the database and
 *    a downloaded file is not; a business's knowledge is not personal data and
 *    its customer list is. They stay on the Clients page.
 *  - <b>Ids and the active flag.</b> Both belong to one installation. Importing
 *    must never decide which business answers the next call.
 *  - <b>Call history.</b> A record of what happened is not part of a setup.
 */
public final class TransferDtos {

    /** What the format field of a file this app wrote always says. */
    public static final String FORMAT = "ors-business";

    /** Raised when the shape changes in a way an older file cannot satisfy. */
    public static final int VERSION = 1;

    /**
     * A handover address, or the placeholder standing in for one.
     *
     * {@code @Email} alone was wrong here, and the way it was wrong is the
     * reason this is spelled out: a fresh install seeds its escalation contact
     * as PLACEHOLDER_ESCALATION_EMAIL, so downloading a seeded business and
     * uploading it again — the first thing anybody tries — was refused by its
     * own file. A placeholder is this project's word for "not set yet", and it
     * has to survive a round trip like any other value.
     */
    private static final String EMAIL_OR_PLACEHOLDER =
            "PLACEHOLDER_[A-Z0-9_]*|[^@\\s]+@[^@\\s]+";

    private TransferDtos() {
    }

    /**
     * The file.
     *
     * @param slug       which business it came from, for a person reading the
     *                   file; never applied on import, because a handle belongs
     *                   to the installation that issued it
     * @param exportedAt when it was written, for the same reason
     */
    public record BusinessDocument(
            String format,
            int version,
            String exportedAt,
            String slug,
            @Valid @NotNull BusinessPart business,
            @Valid PersonaPart persona,
            @Size(max = 1000) List<@Valid KnowledgePart> knowledge,
            @Size(max = 50) List<@Valid ContactPart> escalationContacts) {
    }

    /**
     * @param hours opening hours keyed by short weekday name, exactly as the
     *              Hours tab writes them; a null day means closed
     */
    public record BusinessPart(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 40) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 300) String address,
            @Size(max = 60) String timezone,
            JsonNode hours) {
    }

    /** Null in a hand-written file leaves the business's persona as it is. */
    public record PersonaPart(
            @NotBlank @Size(max = 80) String personaName,
            @NotBlank @Size(max = 2000) String roleDescription,
            @NotBlank @Size(max = 2000) String replyStyle,
            @NotBlank @Size(max = 600) String greetingEn,
            @NotBlank @Size(max = 600) String greetingBn,
            @Size(max = 40) String providerOverride,
            @Size(max = 80) String modelOverride,
            @Min(0) @Max(2) BigDecimal temperature,
            @Min(2) @Max(60) Integer maxHistoryTurns) {
    }

    /**
     * @param kind      ABOUT, SERVICE, POLICY or FAQ
     * @param sortOrder where it sits in its own section, which is the order the
     *                  model reads it in
     */
    public record KnowledgePart(
            @NotBlank String kind,
            @Size(max = 500) String question,
            @NotBlank @Size(max = 4000) String content,
            Integer sortOrder) {
    }

    public record ContactPart(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = EMAIL_OR_PLACEHOLDER) @Size(max = 200) String email,
            @Min(1) @Max(99) Integer priority) {
    }

    /**
     * @param mode     "add" to create a business from the file, "replace" to
     *                 make an existing one match it
     * @param targetId which business to replace; ignored by "add"
     */
    public record ImportRequest(
            @NotBlank String mode,
            UUID targetId,
            @Valid @NotNull BusinessDocument document) {
    }

    /** What an import did, in the words the panel puts in its toast. */
    public record ImportResultView(UUID businessId, String name, String mode,
                                   int knowledgeEntries, int escalationContacts) {
    }

    /** ADD leaves every existing business alone; REPLACE overwrites one whole. */
    public enum ImportMode {
        ADD,
        REPLACE
    }
}
