package com.ulab.agent.brain;

import com.ulab.agent.api.dto.ClientDtos;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.KbKind;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.domain.enums.Telephony;
import com.ulab.agent.services.KbService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grounded answer is only as good as the prompt behind it, so these check
 * that the business's own words actually reach the model — and that a day the
 * owner left blank is stated as closed rather than left for the model to guess.
 */
class PromptBuilderTest {

    private static final UUID BUSINESS_ID = UUID.randomUUID();

    @Test
    void theBusinessesOwnWordsReachTheModel() {
        String prompt = buildPrompt(Language.EN);

        assertTrue(prompt.contains("Ayesha"), "the persona names itself");
        assertTrue(prompt.contains("Example Shop"));
        assertTrue(prompt.contains("Haircut - 500 BDT"), "a service and its price");
        assertTrue(prompt.contains("Refunds within 7 days"), "a policy");
        assertTrue(prompt.contains("Are you open on Friday?"), "a question customers ask");
        assertTrue(prompt.contains("Answer in English."));
    }

    @Test
    void aDayWithNoHoursIsStatedAsClosed() {
        String prompt = buildPrompt(Language.EN);

        assertTrue(prompt.contains("Saturday: 10:00 to 20:00"));
        assertTrue(prompt.contains("Friday: closed"));
        assertTrue(prompt.contains("Sunday: closed"), "a day left out entirely is closed too");
    }

    @Test
    void aBanglaCallIsToldToAnswerInBangla() {
        String prompt = buildPrompt(Language.BN);

        assertTrue(prompt.contains("Bengali script"));
        assertFalse(prompt.contains("Answer in English."));
    }

    @Test
    void aBusinessWithNoKnowledgeSaysSoRatherThanImprovising() {
        PromptBuilder builder = new PromptBuilder(kbReturning(List.of()), modeMachine());
        String knowledge = builder.knowledgeOf(BUSINESS_ID);

        assertTrue(knowledge.contains("has not filled in its knowledge base"));
    }

    @Test
    void theCallsSituationIsInThePromptAndChangesWithIt() {
        String stranger = buildPrompt(Language.EN, CallMode.NEW_CUSTOMER);
        String nuisance = buildPrompt(Language.EN, CallMode.WRONG_NUMBER);

        assertTrue(stranger.contains("not in the customer records"));
        assertTrue(nuisance.contains("wrong number or a nuisance call"));
        assertFalse(nuisance.contains("not in the customer records"),
                "the mode replaces the standing orders, it does not stack on them");
    }

    @Test
    void aRecognisedCallerBringsTheirRecordIntoThePrompt() {
        PromptBuilder builder = new PromptBuilder(kbReturning(knowledgeBase()), modeMachine());
        CallSession session = sessionFor(builder, Language.EN, CallMode.EXISTING_CUSTOMER);
        session.setClient(new ClientDtos.ClientView(UUID.randomUUID(), BUSINESS_ID, "C001",
                "Example Customer One", "+8801711111111", null, "Prefers phone calls.",
                List.of("March: a repair, resolved."), true, Instant.now()));

        String prompt = builder.build(session);

        assertTrue(prompt.contains("Example Customer One"), "the agent can greet them by name");
        assertTrue(prompt.contains("C001"));
        assertTrue(prompt.contains("Prefers phone calls."));
        assertTrue(prompt.contains("March: a repair, resolved."), "and knows what came before");
    }

    @Test
    void anUnknownCallerAddsNothingAboutThemselvesToThePrompt() {
        assertFalse(buildPrompt(Language.EN).contains("Who is calling"));
    }

    @Test
    void theModelIsToldAboutItsActionsWithoutBeingToldToUseThem() {
        String prompt = buildPrompt(Language.EN, CallMode.NEW_CUSTOMER);

        assertTrue(prompt.contains("set_language"));
        assertTrue(prompt.contains("set_mode"));
        assertTrue(prompt.contains("end_call"));
        assertTrue(prompt.contains("words are always the better choice"));
    }

    // ------------------------------------------------------------ fixtures --

    private static String buildPrompt(Language language) {
        return buildPrompt(language, CallMode.NEW_CUSTOMER);
    }

    private static String buildPrompt(Language language, CallMode mode) {
        PromptBuilder builder = new PromptBuilder(kbReturning(knowledgeBase()), modeMachine());
        return builder.build(sessionFor(builder, language, mode));
    }

    private static CallSession sessionFor(PromptBuilder builder, Language language, CallMode mode) {
        CallSession session = new CallSession(UUID.randomUUID(), business(), aiSettings(),
                builder.knowledgeOf(BUSINESS_ID),
                new LlmRouter.Selection("gemini", "gemini-2.0-flash", 0.7),
                Telephony.BROWSER, language, new Object(), message -> { });
        session.setMode(mode);
        return session;
    }

    private static CallModeMachine modeMachine() {
        return new CallModeMachine(new TestCalls.Recorder());
    }

    /** A knowledge base with no database behind it. */
    private static KbService kbReturning(List<KbEntry> entries) {
        return new KbService(null) {
            @Override
            public List<KbEntry> forBusiness(UUID businessId) {
                return entries;
            }
        };
    }

    private static List<KbEntry> knowledgeBase() {
        return List.of(
                entry(KbKind.ABOUT, null, "Example Shop cuts hair in Dhanmondi."),
                entry(KbKind.SERVICE, null, "Haircut - 500 BDT"),
                entry(KbKind.POLICY, null, "Refunds within 7 days with the receipt."),
                entry(KbKind.FAQ, "Are you open on Friday?", "No, we are closed on Friday."));
    }

    private static KbEntry entry(KbKind kind, String question, String content) {
        KbEntry entry = new KbEntry();
        entry.setBusinessId(BUSINESS_ID);
        entry.setKind(kind);
        entry.setQuestion(question);
        entry.setContent(content);
        return entry;
    }

    private static Business business() {
        Business business = new Business();
        business.setId(BUSINESS_ID);
        business.setSlug("example-shop");
        business.setName("Example Shop");
        business.setPhone("+8801000000000");
        business.setAddress("123 Example Road, Dhaka");
        business.setTimezone("Asia/Dhaka");
        business.setHoursJson("{\"sat\":{\"open\":\"10:00\",\"close\":\"20:00\"},\"fri\":null}");
        return business;
    }

    private static AiSettings aiSettings() {
        AiSettings settings = new AiSettings();
        settings.setBusinessId(BUSINESS_ID);
        settings.setPersonaName("Ayesha");
        settings.setRoleDescription("You answer the phone for Example Shop.");
        settings.setReplyStyle("Warm and short.");
        return settings;
    }
}
