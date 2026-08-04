package com.ulab.agent.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.domain.Client;
import com.ulab.agent.repo.ClientRepository;
import com.ulab.agent.utils.Lang;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one class that knows how to open the box.
 *
 * A customer's phone number and email address are stored as pgcrypto blobs, not
 * text, so a copy of the database is not a copy of everybody's contact details.
 * Postgres does the encrypting and decrypting, which means these rows have to
 * be read and written in SQL rather than through JPA — and it means everything
 * above this class works in plain strings and never sees the ciphertext.
 */
@Service
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    /**
     * try_decrypt is the wrapper added in V3: a row written under a different
     * PII key comes back with empty contacts instead of taking the query down.
     */
    private static final String SELECT = """
            SELECT id, business_id, client_code, name,
                   try_decrypt(phone_enc, cast(:key as text)),
                   try_decrypt(email_enc, cast(:key as text)),
                   notes, cast(past_issues_json as text), created_at
            FROM client
            """;

    private final ClientRepository clients;

    @PersistenceContext
    private EntityManager entityManager;

    /** The same key Flyway's seed and the legacy import used. */
    @Value("${PII_ENC_KEY:PLACEHOLDER_PII_ENC_KEY}")
    private String encryptionKey;

    public ClientService(ClientRepository clients) {
        this.clients = clients;
    }

    // ---------------------------------------------------------------- reads --

    @Transactional(readOnly = true)
    public List<ClientDtos.ClientView> list(UUID businessId) {
        return select(" WHERE business_id = cast(:businessId as uuid) ORDER BY client_code",
                "businessId", businessId.toString());
    }

    @Transactional(readOnly = true)
    public ClientDtos.ClientView get(UUID clientId) {
        return one(" WHERE id = cast(:id as uuid)", "id", clientId.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        Lang.ERR_CLIENT_NOT_FOUND));
    }

    /** The code a caller reads out, e.g. "C001". Case and spacing are forgiven. */
    @Transactional(readOnly = true)
    public Optional<ClientDtos.ClientView> byCode(UUID businessId, String clientCode) {
        if (clientCode == null || clientCode.isBlank()) return Optional.empty();
        return one(" WHERE business_id = cast(:businessId as uuid)"
                        + " AND upper(client_code) = upper(cast(:code as text))",
                "businessId", businessId.toString(), "code", clientCode.trim());
    }

    /**
     * Finds someone by the number they are calling from.
     *
     * Every row has to be decrypted to be compared, because pgcrypto gives the
     * same number a different ciphertext every time it is written — there is
     * nothing to match on but the plain text. At the size of one business's
     * customer list that costs nothing worth saving.
     */
    @Transactional(readOnly = true)
    public Optional<ClientDtos.ClientView> byPhone(UUID businessId, String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();

        String wanted = digitsOf(phone);
        return list(businessId).stream()
                .filter(client -> !digitsOf(client.phone()).isEmpty())
                .filter(client -> digitsOf(client.phone()).endsWith(wanted)
                        || wanted.endsWith(digitsOf(client.phone())))
                .findFirst();
    }

    // --------------------------------------------------------------- writes --

    @Transactional
    public ClientDtos.ClientView create(UUID businessId, ClientDtos.ClientUpsertRequest request) {
        String code = request.clientCode() == null || request.clientCode().isBlank()
                ? nextClientCode(businessId) : request.clientCode().trim();

        // The id is chosen here rather than by the database's default, so the
        // row can be read back without asking the insert to return anything.
        UUID id = UUID.randomUUID();
        writing("""
                INSERT INTO client (id, business_id, client_code, name, phone_enc, email_enc,
                                    notes, past_issues_json)
                VALUES (cast(:id as uuid), cast(:businessId as uuid), cast(:code as text),
                        cast(:name as text),
                        pgp_sym_encrypt(cast(:phone as text), cast(:key as text)),
                        pgp_sym_encrypt(cast(:email as text), cast(:key as text)),
                        cast(:notes as text), cast(:pastIssues as jsonb))
                """, request, "id", id.toString(), "businessId", businessId.toString(),
                "code", code);

        log.info("Client {} created for business {}", code, businessId);
        return get(id);
    }

    @Transactional
    public ClientDtos.ClientView update(UUID clientId, ClientDtos.ClientUpsertRequest request) {
        ClientDtos.ClientView existing = get(clientId);
        String code = request.clientCode() == null || request.clientCode().isBlank()
                ? existing.clientCode() : request.clientCode().trim();

        writing("""
                UPDATE client SET
                    client_code = cast(:code as text),
                    name = cast(:name as text),
                    phone_enc = pgp_sym_encrypt(cast(:phone as text), cast(:key as text)),
                    email_enc = pgp_sym_encrypt(cast(:email as text), cast(:key as text)),
                    notes = cast(:notes as text),
                    past_issues_json = cast(:pastIssues as jsonb)
                WHERE id = cast(:id as uuid)
                """, request, "id", clientId.toString(), "code", code);

        return get(clientId);
    }

    @Transactional
    public void delete(UUID clientId) {
        Client client = clients.findById(clientId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_CLIENT_NOT_FOUND));
        clients.delete(client);
        log.info("Client {} deleted", client.getClientCode());
    }

    /** Adds one line to what this customer has needed before. */
    @Transactional
    public ClientDtos.ClientView appendPastIssue(UUID clientId, String issue) {
        if (issue == null || issue.isBlank()) return get(clientId);

        ClientDtos.ClientView existing = get(clientId);
        List<String> issues = new ArrayList<>(existing.pastIssues());
        issues.add(issue.trim());

        entityManager.createNativeQuery("""
                        UPDATE client SET past_issues_json = cast(:issues as jsonb)
                        WHERE id = cast(:id as uuid)
                        """)
                .setParameter("issues", toJsonArray(issues))
                .setParameter("id", clientId.toString())
                .executeUpdate();
        return get(clientId);
    }

    /** The next free C-number for a business, so nobody has to invent one. */
    @Transactional(readOnly = true)
    public String nextClientCode(UUID businessId) {
        int highest = 0;
        for (Client client : clients.findByBusinessIdOrderByClientCodeAsc(businessId)) {
            String digits = client.getClientCode().replaceAll("\\D", "");
            if (digits.isEmpty()) continue;
            try {
                highest = Math.max(highest, Integer.parseInt(digits));
            } catch (NumberFormatException tooLong) {
                log.debug("Client code {} is not a number; skipping it", client.getClientCode());
            }
        }
        return "C%03d".formatted(highest + 1);
    }

    // ------------------------------------------------------------ internals --

    private List<ClientDtos.ClientView> select(String where, Object... namedParameters) {
        Query query = entityManager.createNativeQuery(SELECT + where)
                .setParameter("key", encryptionKey);
        for (int i = 0; i + 1 < namedParameters.length; i += 2) {
            query.setParameter(String.valueOf(namedParameters[i]), namedParameters[i + 1]);
        }
        List<?> rows = query.getResultList();
        return rows.stream().map(row -> toView((Object[]) row)).toList();
    }

    private Optional<ClientDtos.ClientView> one(String where, Object... namedParameters) {
        return select(where + " LIMIT 1", namedParameters).stream().findFirst();
    }

    /** Runs the insert or update that does the encrypting. */
    private void writing(String sql, ClientDtos.ClientUpsertRequest request,
                         Object... namedParameters) {
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("key", encryptionKey)
                .setParameter("name", request.name().trim())
                .setParameter("phone", blankToNull(request.phone()))
                .setParameter("email", blankToNull(request.email()))
                .setParameter("notes", blankToNull(request.notes()))
                .setParameter("pastIssues", toJsonArray(
                        request.pastIssues() == null ? List.of() : request.pastIssues()));
        for (int i = 0; i + 1 < namedParameters.length; i += 2) {
            query.setParameter(String.valueOf(namedParameters[i]), namedParameters[i + 1]);
        }

        try {
            query.executeUpdate();
        } catch (RuntimeException failed) {
            // Only one thing about a customer row can clash: its code. Anything
            // else is a real fault and belongs in the log as one.
            if (!mentionsTheCodeConstraint(failed)) throw failed;
            throw new ResponseStatusException(HttpStatus.CONFLICT, Lang.ERR_CLIENT_CODE_TAKEN);
        }
    }

    private static boolean mentionsTheCodeConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("uq_client_code_per_business")) return true;
        }
        return false;
    }

    private static ClientDtos.ClientView toView(Object[] row) {
        String phone = text(row[4]);
        String email = text(row[5]);
        // Null contacts are ambiguous — never filled in, or written under a key
        // this install no longer has. The stored bytes tell the two apart.
        boolean readable = phone != null || email != null;

        return new ClientDtos.ClientView(asUuid(row[0]), asUuid(row[1]), text(row[2]), text(row[3]),
                phone, email, text(row[6]), fromJsonArray(text(row[7])), readable,
                asInstant(row[8]));
    }

    private static List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) return List.of();
            List<String> issues = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonPrimitive()) issues.add(element.getAsString());
            }
            return issues;
        } catch (RuntimeException notJson) {
            return List.of();
        }
    }

    private static String toJsonArray(List<String> issues) {
        JsonArray array = new JsonArray();
        issues.stream().filter(issue -> issue != null && !issue.isBlank())
                .forEach(issue -> array.add(issue.trim()));
        return array.toString();
    }

    /** Compares numbers by their digits, so +880 17… and 017… are the same person. */
    private static String digitsOf(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static Instant asInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Instant instant) return instant;
        return Instant.now();
    }
}
