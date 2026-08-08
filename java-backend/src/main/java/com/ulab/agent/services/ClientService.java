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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final String encryptionKey;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param encryptionKey the same key Flyway's seed and the legacy import
     *                      used. Taken through the constructor rather than set
     *                      on the field afterwards, so it is final and so a
     *                      test can build this class with a key of its own.
     */
    public ClientService(ClientRepository clients,
                         @Value("${PII_ENC_KEY:PLACEHOLDER_PII_ENC_KEY}") String encryptionKey) {
        this.clients = clients;
        this.encryptionKey = encryptionKey;
    }

    // ---------------------------------------------------------------- reads --

    @Transactional(readOnly = true)
    public List<ClientDtos.ClientView> list(UUID businessId) {
        return select(" WHERE business_id = cast(:businessId as uuid) ORDER BY client_code",
                "businessId", businessId.toString());
    }

    /**
     * One customer, by id alone.
     *
     * Used where the id came from this application rather than from a request —
     * a call record naming the customer it is with. Anything reached from a URL
     * uses the two-argument form below.
     */
    @Transactional(readOnly = true)
    public ClientDtos.ClientView get(UUID clientId) {
        return one(" WHERE id = cast(:id as uuid)", "id", clientId.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        Lang.ERR_CLIENT_NOT_FOUND));
    }

    /**
     * One customer of one business.
     *
     * The business id is part of the URL these endpoints are reached at, and it
     * used to be ignored — every customer id resolved under every business's
     * path. With one operator nothing crossed; the moment there is a login per
     * business that is cross-tenant read, write and delete.
     *
     * A customer belonging to somebody else answers 404 rather than 403, so the
     * endpoint cannot be used to ask whether an id exists.
     */
    @Transactional(readOnly = true)
    public ClientDtos.ClientView get(UUID businessId, UUID clientId) {
        return one(" WHERE id = cast(:id as uuid) AND business_id = cast(:businessId as uuid)",
                "id", clientId.toString(), "businessId", businessId.toString())
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
     * Two ways, in this order. The quick one is an indexed lookup on
     * phone_hash — the hash of the last nine digits, which is what makes
     * +8801711111111 and 01711111111 one person. The slow one decrypts the
     * whole customer list and compares the plain text, because pgcrypto gives
     * the same number a different ciphertext every time it is written and
     * there is nothing else to match on.
     *
     * The slow one is still here, and still correct, because the hash cannot
     * express everything {@link #sameNumber} accepts: a number with six, seven
     * or eight digits matches by suffix but hashes to something different. It
     * runs only when the quick one found nobody, so the case that costs — a
     * known customer ringing in — is one index lookup rather than a decryption
     * per customer on the line.
     */
    @Transactional(readOnly = true)
    public Optional<ClientDtos.ClientView> byPhone(UUID businessId, String phone) {
        if (!isMatchable(phone)) return Optional.empty();

        String hash = phoneHash(phone);
        if (hash != null) {
            Optional<ClientDtos.ClientView> quick = one(
                    " WHERE business_id = cast(:businessId as uuid)"
                            + " AND phone_hash = decode(cast(:hash as text), 'hex')",
                    "businessId", businessId.toString(), "hash", hash);
            if (quick.isPresent()) return quick;
        }

        return list(businessId).stream()
                .filter(client -> sameNumber(client.phone(), phone))
                .findFirst();
    }

    /**
     * The lookup value for a number, as hex, or null when it is too short to
     * have one.
     *
     * Nine digits is the shortest run this treats as a whole number, and it is
     * chosen to agree with {@link #sameNumber}: for any two numbers of nine
     * digits or more, equal hashes and a suffix match are the same statement.
     *
     * Hex rather than a byte array so the value crosses into SQL the same way
     * every other parameter in this class does — as text with a cast — which is
     * also what lets a customer with no number bind a plain null.
     */
    static String phoneHash(String phone) {
        String digits = digitsOf(phone);
        if (digits.length() < HASHABLE_DIGITS) return null;

        String tail = digits.substring(digits.length() - HASHABLE_DIGITS);
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256")
                    .digest(tail.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) hex.append("%02x".formatted(b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException(impossible);
        }
    }

    /** Enough of a number to be the whole of it, however the caller wrote it. */
    private static final int HASHABLE_DIGITS = 9;

    /**
     * Whether two numbers belong to the same person.
     *
     * A caller reads their number out however they think of it, so the
     * comparison is on digits alone and either may be the longer — +8801711…
     * and 01711… are one person. Both sides must carry enough digits to mean
     * something: a masked number arrives here as "[MASKED_PHONE]", which has no
     * digits at all, and a rule that let it through would match whoever
     * happened to be first on the books and greet a stranger by their name.
     */
    static boolean sameNumber(String stored, String given) {
        if (!isMatchable(stored) || !isMatchable(given)) return false;

        String a = digitsOf(stored);
        String b = digitsOf(given);
        return a.endsWith(b) || b.endsWith(a);
    }

    /** Enough digits that an accidental match is not worth worrying about. */
    private static final int MATCHABLE_DIGITS = 6;

    private static boolean isMatchable(String phone) {
        return phone != null && digitsOf(phone).length() >= MATCHABLE_DIGITS;
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
                INSERT INTO client (id, business_id, client_code, name, phone_enc, phone_hash,
                                    email_enc, notes, past_issues_json)
                VALUES (cast(:id as uuid), cast(:businessId as uuid), cast(:code as text),
                        cast(:name as text),
                        pgp_sym_encrypt(cast(:phone as text), cast(:key as text)),
                        decode(cast(:phoneHash as text), 'hex'),
                        pgp_sym_encrypt(cast(:email as text), cast(:key as text)),
                        cast(:notes as text), cast(:pastIssues as jsonb))
                """, request, "id", id.toString(), "businessId", businessId.toString(),
                "code", code);

        log.info("Client {} created for business {}", code, businessId);
        return get(id);
    }

    @Transactional
    public ClientDtos.ClientView update(UUID businessId, UUID clientId,
                                        ClientDtos.ClientUpsertRequest request) {
        // Reading it under the business first is what scopes the write: a
        // customer of another business is a 404 before anything is changed.
        ClientDtos.ClientView existing = get(businessId, clientId);
        String code = request.clientCode() == null || request.clientCode().isBlank()
                ? existing.clientCode() : request.clientCode().trim();

        writing("""
                UPDATE client SET
                    client_code = cast(:code as text),
                    name = cast(:name as text),
                    phone_enc = pgp_sym_encrypt(cast(:phone as text), cast(:key as text)),
                    phone_hash = decode(cast(:phoneHash as text), 'hex'),
                    email_enc = pgp_sym_encrypt(cast(:email as text), cast(:key as text)),
                    notes = cast(:notes as text),
                    past_issues_json = cast(:pastIssues as jsonb)
                WHERE id = cast(:id as uuid)
                """, request, "id", clientId.toString(), "code", code);

        return get(clientId);
    }

    @Transactional
    public void delete(UUID businessId, UUID clientId) {
        Client client = clients.findById(clientId)
                .filter(candidate -> candidate.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        Lang.ERR_CLIENT_NOT_FOUND));
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
                .setParameter("phoneHash", phoneHash(request.phone()))
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
