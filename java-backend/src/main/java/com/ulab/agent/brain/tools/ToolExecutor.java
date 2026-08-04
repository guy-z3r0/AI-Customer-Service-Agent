package com.ulab.agent.brain.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.brain.CallModeMachine;
import com.ulab.agent.brain.CallSession;
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
                    text(arguments, "clientCode"), text(arguments, "phone"));
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
    private String lookupClient(CallSession session, String clientCode, String phone) {
        UUID businessId = session.business().getId();
        Optional<ClientDtos.ClientView> found = clients.byCode(businessId, clientCode);
        if (found.isEmpty()) found = clients.byPhone(businessId, phone);
        if (found.isEmpty()) return refused("nobody on the records matches that code or number");

        ClientDtos.ClientView client = found.get();
        identify(session, client);
        modes.apply(session, CallMode.EXISTING_CUSTOMER, "the caller is on the records");

        return result(true, "name", client.name(), "clientCode", client.clientCode(),
                "pastIssueCount", String.valueOf(client.pastIssues().size()));
    }

    private String createClient(CallSession session, String name, String phone, String request) {
        if (name == null || name.isBlank()) return refused("a new record needs a name");
        if (session.client() != null) {
            return refused("this caller is already on the records as "
                    + session.client().clientCode());
        }

        ClientDtos.ClientView created = clients.create(session.business().getId(),
                new ClientDtos.ClientUpsertRequest(null, name, phone, null, null,
                        request == null || request.isBlank() ? List.of() : List.of(request)));
        identify(session, created);

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

    /** Ties the call to a customer, on the session and on the call's own row. */
    private void identify(CallSession session, ClientDtos.ClientView client) {
        session.setClient(client);
        callLog.recordClient(session.callId(), client.id(), client.name());
    }

    // ---------------------------------------------------------------- tools --

    private String setLanguage(CallSession session, String wanted) {
        if (wanted == null || !("en".equalsIgnoreCase(wanted) || "bn".equalsIgnoreCase(wanted))) {
            return refused("this call speaks English (en) and Bangla (bn), nothing else");
        }

        Language language = Language.of(wanted);
        if (language == session.language()) {
            return result(true, "language", language.code(), "changed", false);
        }

        session.setLanguage(language);
        callLog.recordLanguageChange(session.callId(), language);
        // The voice server has to change recogniser and voice to match, and only
        // it knows what that costs, so it is told rather than asked.
        session.send("set_language", "language", language.code());
        return result(true, "language", language.code(), "changed", true);
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
     */
    private String escalate(CallSession session, String reason, String details) {
        String why = reason == null || reason.isBlank() ? "the caller needs a person" : reason;
        boolean alreadyHandedOver = session.mode() == CallMode.COMPLEX_REQUEST;

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
