package com.ulab.agent.brain.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.brain.CallModeMachine;
import com.ulab.agent.brain.CallSession;
import com.ulab.agent.brain.LanguageSense;
import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.services.ClientService;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Carries out the actions the model asks for, and tells it what happened.
 *
 * Every answer is JSON, including every refusal, because the model reads it
 * back on the next pass and has to be able to tell "done" from "no". Nothing
 * here trusts what it was handed: a language that is not one of the two and a
 * mode change the call is not allowed to make are both ordinary answers, not
 * errors.
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** How many words of "what the matter is" an escalation has to carry. */
    private static final int MEANINGFUL_DETAIL_WORDS = 4;

    private final CallModeMachine modes;
    private final CallLogService callLog;
    private final ClientService clients;

    public ToolExecutor(CallModeMachine modes, CallLogService callLog, ClientService clients) {
        this.modes = modes;
        this.callLog = callLog;
        this.clients = clients;
    }

    /** @return the result as JSON text, for the model to read on its next pass */
    public String run(CallSession session, String name, String argumentsJson) {
        JsonObject arguments = parse(argumentsJson);
        log.info("[{}] tool {} {}", session.callId(), name, arguments);

        return switch (name == null ? "" : name) {
            case ToolRegistry.SET_LANGUAGE -> setLanguage(session, text(arguments, "language"));
            case ToolRegistry.SET_MODE -> setMode(session, text(arguments, "mode"),
                    text(arguments, "reason"));
            case ToolRegistry.END_CALL -> endCall(session, text(arguments, "reason"));
            case ToolRegistry.LOOKUP_CLIENT -> lookupClient(session,
                    text(arguments, "clientCode"), text(arguments, "phone"),
                    text(arguments, "phoneLastFour"), text(arguments, "name"));
            case ToolRegistry.CREATE_CLIENT -> createClient(session, text(arguments, "name"),
                    text(arguments, "phone"), text(arguments, "request"));
            case ToolRegistry.LOG_REQUEST -> logRequest(session, text(arguments, "summary"));
            case ToolRegistry.ESCALATE_TO_HUMAN -> escalate(session, text(arguments, "reason"),
                    text(arguments, "details"));
            default -> refused("there is no action by that name");
        };
    }

    // -------------------------------------------------------------- customers --

    /**
     * Finds the caller on the records, by the code they read out or the number
     * they gave. A caller who turns out to be on the books stops being a
     * stranger, which changes the agent's standing orders for the rest of the
     * call.
     */
    /**
     * Works out whether the caller is who they say they are.
     *
     * Two things changed here after the audit, and both are about what a
     * stranger can get out of this line.
     *
     * A customer code alone is not proof. They run C001, C002, C003 — anyone
     * can guess one — so a code has to arrive with the last four digits of the
     * number on file. A caller who reads out their whole phone number has
     * shown they know something a guesser does not, and that stands on its own.
     *
     * And a match never returns the name. It used to, which meant one guessed
     * code handed a stranger a real customer's name; now the tool only confirms
     * or denies, and the name reaches the agent through the caller record on
     * the next turn, once the call is already tied to that customer.
     *
     * The name is now a third requirement rather than a nicety. A number on its
     * own was treated as proof, and a number is not: handsets are shared, numbers
     * are reassigned, and one wrong digit read out over a bad line lands on
     * somebody else's record — which the caller would then be greeted from. Two
     * facts that agree is the rule, and asking for a name costs one sentence.
     */
    private String lookupClient(CallSession session, String clientCode,
                                String phone, String phoneLastFour, String name) {
        if (session.lookupsExhausted()) {
            return refused("this call has already guessed wrong too many times; "
                    + "carry on as an unknown caller and take their details instead");
        }
        if (!notBlank(name)) {
            return refused("a number is not enough on its own. Ask the caller for their name "
                    + "as well, and send both");
        }

        UUID businessId = session.business().getId();
        Optional<ClientDtos.ClientView> found = clients.byPhone(businessId, phone);
        if (found.isEmpty() && notBlank(clientCode)) {
            found = clients.byCode(businessId, clientCode)
                    .filter(client -> lastFourMatch(client.phone(), phoneLastFour));
        }

        if (found.isEmpty()) {
            int attempts = session.recordFailedLookup();
            return refused("that did not match anybody. Ask for the customer code and the "
                    + "last four digits of the number we hold" + (session.lookupsExhausted()
                    ? ". That was the last attempt this call may make." : "")
                    + " (attempt " + attempts + ")");
        }
        if (!ClientService.sameName(found.get().name(), name)) {
            // Deliberately vague about which half was wrong: saying "the number
            // is ours but the name is not" tells a guesser they found a real
            // customer, which is most of the way to being one.
            int attempts = session.recordFailedLookup();
            return refused("that did not match anybody. Check the name and the number with "
                    + "the caller (attempt " + attempts + ")");
        }

        ClientDtos.ClientView client = found.get();
        identify(session, client, true);
        modes.apply(session, CallMode.EXISTING_CUSTOMER, "the caller proved who they are");

        // Deliberately no name: confirming identity and disclosing it are two
        // different things, and only one of them was asked for.
        return result(true, "clientCode", client.clientCode(),
                "pastIssueCount", String.valueOf(client.pastIssues().size()));
    }

    /** Whether the digits offered are really the tail of the number on file. */
    private static boolean lastFourMatch(String storedPhone, String offered) {
        if (storedPhone == null || offered == null) return false;
        String stored = storedPhone.replaceAll("\\D", "");
        String given = offered.replaceAll("\\D", "");
        return given.length() == 4 && stored.length() >= 4 && stored.endsWith(given);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Writes down somebody the records did not have.
     *
     * It checks the number first. Writing a second record for a customer who is
     * already on the books gives a business two histories for one person and
     * whoever rings them back the wrong one — and it is easy to do, because a
     * caller who never mentions being a customer is a stranger as far as the
     * conversation goes.
     */
    private String createClient(CallSession session, String name, String phone, String request) {
        if (name == null || name.isBlank()) return refused("a new record needs a name");
        if (session.client() != null) {
            return refused("this caller is already on the records as "
                    + session.client().clientCode());
        }

        Optional<ClientDtos.ClientView> already =
                clients.byPhone(session.business().getId(), phone);
        if (already.isPresent()) {
            return refused("that number is already on the records. Check the name with the "
                    + "caller and use lookup_client with the name and the number instead");
        }

        ClientDtos.ClientView created = clients.create(session.business().getId(),
                new ClientDtos.ClientUpsertRequest(null, name, phone, null, null,
                        request == null || request.isBlank() ? List.of() : List.of(request)));
        // Written down, not recognised: the panel says which, because a note
        // reading "Recognised: Sadman" over a record created ten seconds ago is
        // the app claiming to know something it has just been told.
        identify(session, created, false);

        return result(true, "clientCode", created.clientCode(), "name", created.name());
    }

    private String logRequest(CallSession session, String summary) {
        if (session.client() == null) {
            return refused("there is nobody on the records to write this against yet — "
                    + "look the caller up or create a record first");
        }
        if (summary == null || summary.isBlank()) return refused("there is nothing to write down");

        session.setClient(clients.appendPastIssue(session.client().id(), summary));
        return result(true, "clientCode", session.client().clientCode());
    }

    /**
     * Ties the call to a customer, on the session and on the call's own row.
     *
     * @param recognised true when the caller was found on the records, false
     *                   when the record is one this call has just written
     */
    private void identify(CallSession session, ClientDtos.ClientView client, boolean recognised) {
        session.setClient(client);
        callLog.recordClient(session.callId(), client.id(), client.name(), recognised);
    }

    // ---------------------------------------------------------------- tools --

    private String setLanguage(CallSession session, String wanted) {
        if (wanted == null || !("en".equalsIgnoreCase(wanted) || "bn".equalsIgnoreCase(wanted))) {
            return refused("this call speaks English (en) and Bangla (bn), nothing else");
        }

        Language language = Language.of(wanted);
        // One place moves a call between languages, because the brain does it
        // too — on what the caller's own words were written in, without waiting
        // for the model to notice.
        boolean changed = LanguageSense.apply(session, language, callLog);
        return result(true, "language", language.code(), "changed", changed);
    }

    private String setMode(CallSession session, String wanted, String reason) {
        CallMode mode = modeOf(wanted);
        if (mode == null) return refused("there is no call kind by that name");

        ModeTransition transition = modes.apply(session, mode, reason);
        if (transition == null) return refused(Lang.ERR_MODE_NOT_ALLOWED);

        boolean ending = modes.endsTheCall(mode);
        if (ending) {
            session.requestHangup(mode.name().toLowerCase(),
                    spoken(session, modes.farewellKey(mode)));
        }
        return result(true, "mode", mode.name(), "callIsEnding", ending);
    }

    /**
     * Hands the call to a member of staff.
     *
     * The promise is kept by the mode rather than by this method: moving to
     * COMPLEX_REQUEST is what makes the summary go out by email when the call
     * ends, and the reason given here is what that email says. Details the
     * caller gave go into the transcript as their own line, so they travel with
     * the call whether it is read on screen or in the email.
     *
     * It refuses an escalation that cannot say what the matter is. On a real
     * call somebody said "I want to talk with your manager", was escalated on
     * that sentence alone, and the colleague was handed a call with nothing in
     * it but the request itself — nothing to prepare for, and nothing the agent
     * might have answered on the spot. Asking what it is about is the agent's
     * job, and this is what makes it do that job before handing the call on.
     */
    private String escalate(CallSession session, String reason, String details) {
        String why = reason == null || reason.isBlank() ? "the caller needs a person" : reason;
        boolean alreadyHandedOver = session.mode() == CallMode.COMPLEX_REQUEST;

        if (!alreadyHandedOver && !saysWhatTheMatterIs(details)) {
            return refused("nothing here says what the caller actually needs. Ask them what "
                    + "the matter is about and let them explain it, try to answer it from the "
                    + "knowledge you have, and only then call this again with what they told "
                    + "you in details. Do not tell them you are handing the call over yet.");
        }
        if (!alreadyHandedOver && modes.apply(session, CallMode.COMPLEX_REQUEST, why) == null) {
            return refused(Lang.ERR_MODE_NOT_ALLOWED);
        }
        if (details != null && !details.isBlank()) {
            callLog.record(session.callId(), CallDtos.LineToStore.system(
                    details.trim(), session.language().code(), session.mode().name()));
        }
        return result(true, "mode", CallMode.COMPLEX_REQUEST.name(),
                "alreadyHandedOver", alreadyHandedOver);
    }

    /**
     * The floor under what a colleague is owed: a sentence about the problem.
     *
     * Words rather than characters, because "manager" and "urgent" are each a
     * detail field that says nothing. It is a floor and not a judgement of
     * quality — a model determined to write four empty words can still do so —
     * but it costs the model a turn to get past, and that turn is spent asking
     * the caller, which is the whole point.
     */
    private static boolean saysWhatTheMatterIs(String details) {
        return details != null && details.strip().split("\\s+").length >= MEANINGFUL_DETAIL_WORDS;
    }

    private String endCall(CallSession session, String reason) {
        session.requestHangup(reason == null || reason.isBlank() ? "agent_ended" : reason,
                spoken(session, "voice.goodbye"));
        return result(true, "callIsEnding", true);
    }

    // ------------------------------------------------------------ internals --

    private static String spoken(CallSession session, String stringKey) {
        return Lang.ui(session.language().code()).get(stringKey);
    }

    private static CallMode modeOf(String raw) {
        if (raw == null) return null;
        try {
            return CallMode.valueOf(raw.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException notAMode) {
            return null;
        }
    }

    private static JsonObject parse(String argumentsJson) {
        try {
            var parsed = JsonParser.parseString(argumentsJson == null ? "{}" : argumentsJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException notJson) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject arguments, String key) {
        var value = arguments.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    /** A refusal the model can read, phrased for it rather than for a person. */
    private static String refused(String why) {
        return result(false, "error", why);
    }

    private static String result(boolean ok, Object... keysAndValues) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", ok);
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            String key = String.valueOf(keysAndValues[i]);
            Object value = keysAndValues[i + 1];
            if (value instanceof Boolean flag) body.addProperty(key, flag);
            else body.addProperty(key, String.valueOf(value));
        }
        return body.toString();
    }
}
