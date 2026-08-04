package com.ulab.agent.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every word a person reads, in one place.
 *
 * Two groups live here:
 *
 *  - Plain constants, used by Java code that prints or logs. Format markers are
 *    %s / %d, for use with String.format.
 *  - The UI catalogue, which the web panel fetches from GET /api/lang. The panel
 *    itself contains no English or Bangla text at all, so a wording change is a
 *    change to this file and nothing else.
 *
 * Every UI entry carries both languages. When a Bangla wording is still missing
 * the English is repeated, which reads badly but never leaves a blank screen.
 */
public final class Lang {

    private Lang() {
        // Constants only; nothing to construct.
    }

    // ------------------------------------------------- file & directory ops --
    public static final String DIR_CREATE_SUCCESS = "Directory created successfully.";
    public static final String DIR_CREATE_FAIL = "Failed to create directory.";
    public static final String FILE_READ_SUCCESS = "File read successfully.";
    public static final String FILE_READ_FAIL = "Failed to read file.";
    public static final String FILE_WRITE_SUCCESS = "File written successfully.";
    public static final String FILE_WRITE_FAIL = "Failed to write to file.";
    public static final String FILE_NOT_FOUND = "File not found.";
    public static final String FILE_LOADED = "Loaded file %s.";

    // ---------------------------------------------------- api error messages --
    public static final String ERR_BUSINESS_NOT_FOUND = "No business with that id.";
    public static final String ERR_CALL_NOT_FOUND = "No call with that id.";
    public static final String ERR_NO_ACTIVE_BUSINESS =
            "Choose an active business before starting a call.";
    public static final String ERR_CLIENT_NOT_FOUND = "No customer with that id.";
    public static final String ERR_CLIENT_CODE_TAKEN =
            "Another customer of this business already uses that code.";
    public static final String ERR_KB_NOT_FOUND = "No knowledge entry with that id.";
    public static final String ERR_CONTACT_NOT_FOUND = "No escalation contact with that id.";
    public static final String ERR_CALL_NOT_LIVE = "That call is not running.";
    public static final String ERR_MODE_NOT_ALLOWED =
            "A call cannot move to that screening mode from where it is now.";
    public static final String ERR_VALIDATION = "Some fields need fixing.";
    public static final String ERR_CONFLICT = "That change clashes with something already saved.";
    public static final String ERR_UNEXPECTED = "Something went wrong on the server.";

    // ------------------------------------- defaults for newly created records --
    public static final String DEFAULT_PERSONA_NAME = "Agent";
    public static final String DEFAULT_ROLE_DESCRIPTION =
            "You answer the phone for this business. Help callers using only the knowledge given to you.";
    public static final String DEFAULT_REPLY_STYLE =
            "Warm, short and clear. One or two sentences per reply. Never invent a price or a policy.";
    public static final String DEFAULT_GREETING_EN =
            "Hello, you have reached %s. This call is answered by an AI assistant. How can I help you today?";
    public static final String DEFAULT_GREETING_BN =
            "হ্যালো, আপনি %s-এ কল করেছেন। এই কলটি একজন এআই সহকারী গ্রহণ করছে। আমি কীভাবে সাহায্য করতে পারি?";

    // ------------------------------------------------------------ UI catalogue --

    private static final Map<String, String[]> UI = buildUi();

    /** All panel strings in one language. "bn" gives Bangla, anything else English. */
    public static Map<String, String> ui(String language) {
        int index = "bn".equalsIgnoreCase(language) ? 1 : 0;
        Map<String, String> result = new LinkedHashMap<>();
        UI.forEach((key, pair) -> result.put(key, pair[index]));
        return Collections.unmodifiableMap(result);
    }

    /**
     * One string in both languages, one after the other.
     *
     * There is exactly one moment in a call when this is the right thing to say:
     * the greeting, before anybody knows which language the caller wants. Asking
     * that question in only one of them defeats the point of asking.
     */
    public static String bilingual(String key) {
        String[] pair = UI.get(key);
        return pair == null ? "" : pair[0] + " " + pair[1];
    }

    private static Map<String, String[]> buildUi() {
        Map<String, String[]> m = new LinkedHashMap<>();

        // shell
        m.put("app.title", pair("AI Customer Service Agent", "এআই কাস্টমার সার্ভিস এজেন্ট"));
        m.put("nav.dashboard", pair("Dashboard", "ড্যাশবোর্ড"));
        m.put("nav.live_call", pair("Live call", "লাইভ কল"));
        m.put("nav.businesses", pair("Businesses", "ব্যবসা"));
        m.put("nav.clients", pair("Clients", "ক্লায়েন্ট"));
        m.put("nav.history", pair("Call history", "কল ইতিহাস"));
        m.put("nav.settings", pair("Settings", "সেটিংস"));
        m.put("nav.business_editor", pair("Business", "ব্যবসা"));
        m.put("shell.active_business", pair("Active business", "সক্রিয় ব্যবসা"));
        m.put("shell.no_active_business", pair("No active business", "কোনো সক্রিয় ব্যবসা নেই"));

        // status bar
        m.put("status.database", pair("Database", "ডেটাবেস"));
        m.put("status.voice", pair("Voice server", "ভয়েস সার্ভার"));
        m.put("status.model", pair("Language model", "ভাষা মডেল"));
        m.put("status.up", pair("up", "চালু"));
        m.put("status.down", pair("down", "বন্ধ"));
        m.put("status.ready", pair("ready", "প্রস্তুত"));
        m.put("status.needs_key", pair("needs a key", "একটি কী দরকার"));
        m.put("status.placeholders_left", pair("%s setting(s) still on placeholder values",
                "%s টি সেটিংস এখনও প্লেসহোল্ডার মানে আছে"));

        // shared words
        m.put("common.save", pair("Save", "সেভ করুন"));
        m.put("common.cancel", pair("Cancel", "বাতিল"));
        m.put("common.close", pair("Close", "বন্ধ করুন"));
        m.put("common.add", pair("Add", "যোগ করুন"));
        m.put("common.edit", pair("Edit", "সম্পাদনা"));
        m.put("common.delete", pair("Delete", "মুছুন"));
        m.put("common.back", pair("Back", "ফিরে যান"));
        m.put("common.up", pair("Move up", "উপরে"));
        m.put("common.down", pair("Move down", "নিচে"));
        m.put("common.saved", pair("Saved", "সেভ হয়েছে"));
        m.put("common.deleted", pair("Deleted", "মুছে ফেলা হয়েছে"));
        m.put("common.optional", pair("optional", "ঐচ্ছিক"));
        m.put("common.loading", pair("Loading", "লোড হচ্ছে"));
        m.put("common.yes", pair("Yes", "হ্যাঁ"));
        m.put("common.no", pair("No", "না"));
        m.put("error.network", pair("Could not reach the server.", "সার্ভারে পৌঁছানো যায়নি।"));
        m.put("error.load_failed", pair("Could not load this page.", "এই পেজটি লোড করা যায়নি।"));

        // businesses page
        m.put("businesses.title", pair("Businesses", "ব্যবসা"));
        m.put("businesses.note", pair(
                "Everything a call knows comes from here. Open a business to edit what it says, who it is, and who it hands a call to.",
                "একটি কল যা কিছু জানে তার সবই এখান থেকে আসে। কী বলবে, কে হবে, আর কার কাছে কল পাঠাবে — সম্পাদনা করতে ব্যবসাটি খুলুন।"));
        m.put("businesses.col_name", pair("Name", "নাম"));
        m.put("businesses.col_slug", pair("Handle", "হ্যান্ডেল"));
        m.put("businesses.col_contact", pair("Contact", "যোগাযোগ"));
        m.put("businesses.col_kb", pair("Knowledge", "জ্ঞানভাণ্ডার"));
        m.put("businesses.col_clients", pair("Clients", "ক্লায়েন্ট"));
        m.put("businesses.col_state", pair("State", "অবস্থা"));
        m.put("businesses.badge_active", pair("Active", "সক্রিয়"));
        m.put("businesses.activate", pair("Make active", "সক্রিয় করুন"));
        m.put("businesses.activated", pair("%s is now the active business", "%s এখন সক্রিয় ব্যবসা"));
        m.put("businesses.import", pair("Import old JSON data", "পুরোনো JSON ডেটা আমদানি করুন"));
        m.put("businesses.import_done", pair("Imported %s, skipped %s", "%s আমদানি হয়েছে, %s বাদ পড়েছে"));
        m.put("businesses.empty", pair("No businesses in the database", "ডেটাবেসে কোনো ব্যবসা নেই"));
        m.put("businesses.col_actions", pair("Actions", "কাজ"));
        m.put("businesses.open_editor", pair("What it says", "কী বলে"));
        m.put("businesses.new", pair("Add a business", "নতুন ব্যবসা"));
        m.put("businesses.new_title", pair("New business", "নতুন ব্যবসা"));
        m.put("businesses.edit_title", pair("Edit business", "ব্যবসা সম্পাদনা"));
        m.put("businesses.created", pair("%s added", "%s যোগ হয়েছে"));
        m.put("businesses.saved", pair("%s saved", "%s সেভ হয়েছে"));
        m.put("businesses.delete_title", pair("Delete this business", "এই ব্যবসাটি মুছুন"));
        m.put("businesses.delete_line", pair(
                "Deleting %s also deletes its knowledge, its customers and every call it has ever taken.",
                "%s মুছলে এর জ্ঞানভাণ্ডার, গ্রাহক এবং এ পর্যন্ত নেওয়া সব কল একসাথে মুছে যাবে।"));
        m.put("businesses.field_name", pair("Name", "নাম"));
        m.put("businesses.field_phone", pair("Phone", "ফোন"));
        m.put("businesses.field_email", pair("Email", "ইমেইল"));
        m.put("businesses.field_address", pair("Address", "ঠিকানা"));
        m.put("businesses.field_timezone", pair("Time zone", "টাইম জোন"));

        // settings page
        m.put("settings.title", pair("Settings", "সেটিংস"));
        m.put("settings.note", pair(
                "Everything here can change while the app is running. A key marked PLACEHOLDER is not set yet, and the feature that needs it stays switched off until you fill it in.",
                "এখানের সবকিছু অ্যাপ চলা অবস্থাতেই বদলানো যায়। PLACEHOLDER চিহ্নিত কী এখনও সেট করা হয়নি, এবং সেটির উপর নির্ভরশীল ফিচার ততক্ষণ বন্ধ থাকে।"));
        m.put("settings.save", pair("Save settings", "সেটিংস সেভ করুন"));
        m.put("settings.saved", pair("%s setting(s) saved", "%s টি সেটিংস সেভ হয়েছে"));
        m.put("settings.no_changes", pair("Nothing changed", "কিছু বদলায়নি"));
        m.put("settings.badge_placeholder", pair("PLACEHOLDER", "PLACEHOLDER"));
        m.put("settings.secret_hint", pair("Leave blank to keep the stored value",
                "সংরক্ষিত মান রাখতে ফাঁকা রাখুন"));
        m.put("settings.group.llm", pair("Language model", "ভাষা মডেল"));
        m.put("settings.group.voice", pair("Speech", "কণ্ঠস্বর"));
        m.put("settings.group.call", pair("Call behaviour", "কলের আচরণ"));
        m.put("settings.group.twilio", pair("Telephony (optional)", "টেলিফোনি (ঐচ্ছিক)"));
        m.put("settings.group.email", pair("Escalation email (optional)", "এসকেলেশন ইমেইল (ঐচ্ছিক)"));
        m.put("settings.group.other", pair("Other", "অন্যান্য"));

        addLiveCallStrings(m);
        addEditorStrings(m);
        addClientStrings(m);

        // pages that arrive in later phases
        m.put("soon.dashboard", pair("The dashboard arrives in Phase 6", "ড্যাশবোর্ড ফেজ ৬-এ আসছে"));
        m.put("soon.history", pair("Call history arrives in Phase 6", "কল ইতিহাস ফেজ ৬-এ আসছে"));
        m.put("soon.action", pair("Go to Businesses", "ব্যবসায় যান"));

        addSettingLabels(m);
        return Collections.unmodifiableMap(m);
    }

    /** The six-tab business editor: knowledge, persona, hours and escalation. */
    private static void addEditorStrings(Map<String, String[]> m) {
        m.put("editor.note", pair(
                "Everything on these tabs reaches the next call with no restart. Nothing is copied anywhere; the call reads this.",
                "এই ট্যাবগুলোর সবকিছু পরের কলেই কার্যকর হয়, রিস্টার্ট লাগে না। কিছু কোথাও কপি হয় না; কল সরাসরি এখান থেকেই পড়ে।"));
        m.put("editor.tab_about", pair("About", "পরিচিতি"));
        m.put("editor.tab_service", pair("Services", "সেবা"));
        m.put("editor.tab_policy", pair("Policies", "নীতিমালা"));
        m.put("editor.tab_faq", pair("Questions", "প্রশ্ন"));
        m.put("editor.tab_persona", pair("Persona", "পরিচয়"));
        m.put("editor.tab_hours", pair("Hours & handover", "সময় ও হস্তান্তর"));

        m.put("editor.add_entry", pair("Add an entry", "একটি এন্ট্রি যোগ করুন"));
        m.put("editor.entry_title", pair("Knowledge entry", "জ্ঞান এন্ট্রি"));
        m.put("editor.field_question", pair("The question a customer asks", "গ্রাহক যে প্রশ্নটি করে"));
        m.put("editor.field_content", pair("What the agent may say", "এজেন্ট যা বলতে পারে"));
        m.put("editor.entry_delete_line", pair(
                "This entry is removed from what the agent may say. Calls after this will not know it.",
                "এই এন্ট্রিটি এজেন্ট যা বলতে পারে তার থেকে সরে যাবে। এরপরের কলগুলো এটি জানবে না।"));
        m.put("editor.section_empty", pair("Nothing here yet", "এখানে এখনও কিছু নেই"));

        m.put("editor.persona_name", pair("The name the agent gives", "এজেন্ট যে নাম বলে"));
        m.put("editor.persona_role", pair("What its job is", "এর কাজ কী"));
        m.put("editor.persona_style", pair("How it should sound", "কীভাবে কথা বলবে"));
        m.put("editor.persona_greeting_en", pair("Greeting in English", "ইংরেজি অভ্যর্থনা"));
        m.put("editor.persona_greeting_bn", pair("Greeting in Bangla", "বাংলা অভ্যর্থনা"));
        m.put("editor.persona_provider", pair("Use a different provider", "ভিন্ন প্রোভাইডার ব্যবহার করুন"));
        m.put("editor.persona_model", pair("Use a different model", "ভিন্ন মডেল ব্যবহার করুন"));
        m.put("editor.persona_temperature", pair("How freely it words things", "কতটা স্বাধীনভাবে বলবে"));
        m.put("editor.persona_history", pair("Turns of history it remembers", "কত পালা মনে রাখবে"));
        m.put("editor.override_hint", pair(
                "Leave blank to use whatever Settings says",
                "সেটিংসে যা আছে তা ব্যবহার করতে ফাঁকা রাখুন"));

        m.put("editor.hours_title", pair("Opening hours", "খোলার সময়"));
        m.put("editor.hours_hint", pair(
                "A day left blank is told to callers as closed.",
                "ফাঁকা রাখা দিনটি কলারদের বন্ধ বলে জানানো হয়।"));
        m.put("editor.hours_open", pair("Opens", "খোলে"));
        m.put("editor.hours_close", pair("Closes", "বন্ধ হয়"));
        m.put("editor.day_sat", pair("Saturday", "শনিবার"));
        m.put("editor.day_sun", pair("Sunday", "রবিবার"));
        m.put("editor.day_mon", pair("Monday", "সোমবার"));
        m.put("editor.day_tue", pair("Tuesday", "মঙ্গলবার"));
        m.put("editor.day_wed", pair("Wednesday", "বুধবার"));
        m.put("editor.day_thu", pair("Thursday", "বৃহস্পতিবার"));
        m.put("editor.day_fri", pair("Friday", "শুক্রবার"));

        m.put("editor.escalation_title", pair("Who takes a call the agent cannot",
                "এজেন্ট যা পারে না তা কে নেবে"));
        m.put("editor.escalation_add", pair("Add a person", "একজনকে যোগ করুন"));
        m.put("editor.escalation_name", pair("Name", "নাম"));
        m.put("editor.escalation_email", pair("Email", "ইমেইল"));
        m.put("editor.escalation_priority", pair("Told first (1 is first)", "আগে জানানো হবে (১ = প্রথম)"));
        m.put("editor.escalation_empty", pair("Nobody is listed yet", "এখনও কাউকে যোগ করা হয়নি"));
    }

    /** The customer list, and the record a call reads and writes. */
    private static void addClientStrings(Map<String, String[]> m) {
        m.put("clients.title", pair("Customers", "গ্রাহক"));
        m.put("clients.note", pair(
                "A caller the agent recognises is greeted by name and answered against what is on this page. Phone numbers and email addresses are encrypted in the database.",
                "এজেন্ট যাকে চিনতে পারে তাকে নাম ধরে ডাকে এবং এই পাতার তথ্য অনুযায়ী উত্তর দেয়। ফোন নম্বর ও ইমেইল ডেটাবেসে এনক্রিপ্ট করা থাকে।"));
        m.put("clients.col_code", pair("Code", "কোড"));
        m.put("clients.col_name", pair("Name", "নাম"));
        m.put("clients.col_phone", pair("Phone", "ফোন"));
        m.put("clients.col_email", pair("Email", "ইমেইল"));
        m.put("clients.col_issues", pair("On record", "রেকর্ডে"));
        m.put("clients.new", pair("Add a customer", "নতুন গ্রাহক"));
        m.put("clients.new_title", pair("New customer", "নতুন গ্রাহক"));
        m.put("clients.edit_title", pair("Edit customer", "গ্রাহক সম্পাদনা"));
        m.put("clients.empty", pair("This business has no customers yet",
                "এই ব্যবসার এখনও কোনো গ্রাহক নেই"));
        m.put("clients.no_business", pair("Choose an active business first",
                "প্রথমে একটি সক্রিয় ব্যবসা বেছে নিন"));
        m.put("clients.field_code", pair("Customer code", "গ্রাহক কোড"));
        m.put("clients.field_name", pair("Name", "নাম"));
        m.put("clients.field_phone", pair("Phone", "ফোন"));
        m.put("clients.field_email", pair("Email", "ইমেইল"));
        m.put("clients.field_notes", pair("Notes the agent should have", "এজেন্টের যা জানা দরকার"));
        m.put("clients.field_issues", pair("What they have needed before", "আগে যা যা দরকার হয়েছে"));
        m.put("clients.issues_hint", pair("One per line, oldest first",
                "প্রতি লাইনে একটি, পুরোনোটি আগে"));
        m.put("clients.delete_line", pair(
                "%s and everything on record about them is removed. Calls already taken keep their transcripts.",
                "%s এবং তাদের সম্পর্কে রেকর্ড করা সবকিছু মুছে যাবে। ইতিমধ্যে নেওয়া কলের কথোপকথন থেকে যাবে।"));
        m.put("clients.contact_hidden", pair(
                "Written under a different PII key — the contact details cannot be read",
                "ভিন্ন PII কী দিয়ে লেখা — যোগাযোগের তথ্য পড়া যাচ্ছে না"));
    }

    /**
     * The Live Call page, plus the lines the agent speaks when something has
     * gone wrong. Ordinary replies come from the language model; these are the
     * three sentences that must exist even when it cannot be reached, and the
     * voice server fetches them at the start of every call for exactly that
     * reason.
     */
    private static void addLiveCallStrings(Map<String, String[]> m) {
        m.put("voice.no_model", pair(
                "I am sorry, I cannot answer questions right now because I am not connected to a language model. Please try again later.",
                "দুঃখিত, এই মুহূর্তে আমি প্রশ্নের উত্তর দিতে পারছি না, কারণ আমি কোনো ভাষা মডেলের সাথে যুক্ত নই। অনুগ্রহ করে পরে আবার চেষ্টা করুন।"));
        m.put("voice.trouble", pair(
                "Sorry, I had trouble answering that. Could you say it again?",
                "দুঃখিত, উত্তর দিতে সমস্যা হয়েছে। আবার একটু বলবেন?"));
        m.put("voice.link_lost", pair(
                "Sorry, I have lost my connection. I have to end the call here.",
                "দুঃখিত, আমার সংযোগ চলে গেছে। কলটি এখানেই শেষ করতে হচ্ছে।"));
        m.put("voice.language_question", pair(
                "Would you like to carry on in English, or in Bangla?",
                "আপনি কি ইংরেজিতে চালিয়ে যেতে চান, নাকি বাংলায়?"));
        m.put("voice.not_understood", pair(
                "Sorry, I could not make that out. Could you say it once more, a little slower?",
                "দুঃখিত, আমি বুঝতে পারিনি। একটু ধীরে আরেকবার বলবেন?"));
        m.put("voice.still_there", pair(
                "Are you still there?",
                "আপনি কি এখনও আছেন?"));
        m.put("voice.inactivity_farewell", pair(
                "I cannot hear anything, so I will end the call here. Please call back any time. Goodbye.",
                "কিছু শুনতে পাচ্ছি না, তাই কলটি এখানেই শেষ করছি। যেকোনো সময় আবার কল করবেন। বিদায়।"));
        m.put("voice.wrong_number_farewell", pair(
                "It sounds like this may be the wrong number. Thank you for calling, and goodbye.",
                "মনে হচ্ছে নম্বরটি ভুল হয়েছে। কল করার জন্য ধন্যবাদ, বিদায়।"));
        m.put("voice.goodbye", pair(
                "Thank you for calling. Goodbye.",
                "কল করার জন্য ধন্যবাদ। বিদায়।"));

        m.put("livecall.title", pair("Live call", "লাইভ কল"));
        m.put("livecall.note", pair(
                "The agent asks which language you want, then answers from the active business's knowledge base. It screens the call as it goes; you can overrule it below at any time.",
                "এজেন্ট প্রথমে জিজ্ঞেস করবে আপনি কোন ভাষা চান, তারপর সক্রিয় ব্যবসার জ্ঞানভাণ্ডার থেকে উত্তর দেবে। কথা বলার সাথে সাথে সে কলটি স্ক্রিন করে; আপনি যেকোনো সময় নিচে থেকে তা বদলে দিতে পারেন।"));
        m.put("livecall.start", pair("Start browser call", "ব্রাউজার কল শুরু করুন"));
        m.put("livecall.start_twilio", pair("Twilio call", "টুইলিও কল"));
        m.put("livecall.end", pair("End call", "কল শেষ করুন"));
        m.put("livecall.transcript", pair("Transcript", "কথোপকথন"));

        m.put("livecall.state", pair("State", "অবস্থা"));
        m.put("livecall.state_idle", pair("Not on a call", "কোনো কল চলছে না"));
        m.put("livecall.state_connecting", pair("Connecting", "সংযোগ হচ্ছে"));
        m.put("livecall.state_listening", pair("Listening", "শুনছে"));
        m.put("livecall.state_speaking", pair("Agent speaking", "এজেন্ট বলছে"));
        m.put("livecall.state_ended", pair("Call ended", "কল শেষ"));

        m.put("livecall.business", pair("Business", "ব্যবসা"));
        m.put("livecall.language", pair("Language", "ভাষা"));
        m.put("livecall.call_id", pair("Call id", "কল আইডি"));
        m.put("livecall.turns", pair("Turns", "পালা"));
        m.put("livecall.median_latency", pair("Median reply time", "গড় উত্তরের সময়"));
        m.put("livecall.model", pair("Answering model", "উত্তরদাতা মডেল"));
        m.put("livecall.dial_as", pair("Dial as", "যার হয়ে কল"));
        m.put("livecall.dial_as_stranger", pair("Somebody not on the records",
                "রেকর্ডে নেই এমন কেউ"));
        m.put("livecall.caller", pair("Caller", "কলার"));
        m.put("livecall.caller_unknown", pair("Not recognised yet", "এখনও চেনা যায়নি"));
        m.put("livecall.caller_found", pair("Recognised: %s", "চেনা গেছে: %s"));
        m.put("livecall.mode", pair("Screening", "স্ক্রিনিং"));
        m.put("livecall.override_mode", pair("Change it by hand", "নিজে হাতে বদলান"));
        m.put("livecall.mode_changed", pair("Screening is now: %s", "স্ক্রিনিং এখন: %s"));
        m.put("livecall.mode_refused", pair(
                "A call cannot move to that from where it is now.",
                "কলটি এখন যেখানে আছে সেখান থেকে ওখানে যাওয়া যায় না।"));
        m.put("livecall.language_changed", pair("The call switched to %s", "কলটি %s-এ বদলেছে"));

        m.put("mode.new_customer", pair("New caller", "নতুন কলার"));
        m.put("mode.existing_customer", pair("Known customer", "পরিচিত গ্রাহক"));
        m.put("mode.wrong_number", pair("Wrong number", "ভুল নম্বর"));
        m.put("mode.complex_request", pair("Needs a person", "একজন মানুষ দরকার"));

        m.put("language.en", pair("English", "ইংরেজি"));
        m.put("language.bn", pair("Bangla", "বাংলা"));
        m.put("livecall.stages", pair("Model %s ms, voice %s ms", "মডেল %s মিলিসেকেন্ড, কণ্ঠ %s মিলিসেকেন্ড"));
        m.put("livecall.no_model", pair(
                "No language model key is set, so the agent can only apologise. Add one in Settings.",
                "কোনো ভাষা মডেল কী সেট করা নেই, তাই এজেন্ট শুধু দুঃখ প্রকাশ করতে পারবে। সেটিংসে একটি যোগ করুন।"));

        m.put("livecall.role_caller", pair("Caller", "কলার"));
        m.put("livecall.role_agent", pair("Agent", "এজেন্ট"));
        m.put("livecall.role_system", pair("System", "সিস্টেম"));
        m.put("livecall.hearing", pair("hearing…", "শুনছে…"));

        m.put("livecall.empty", pair("Nothing has been said yet", "এখনও কিছু বলা হয়নি"));
        m.put("livecall.mic_denied", pair(
                "The browser would not give access to the microphone.",
                "ব্রাউজার মাইক্রোফোন ব্যবহারের অনুমতি দেয়নি।"));
        m.put("livecall.unsupported", pair(
                "This browser cannot capture microphone audio.",
                "এই ব্রাউজার মাইক্রোফোনের অডিও নিতে পারে না।"));
        m.put("livecall.twilio_disabled", pair(
                "Add Twilio credentials in Settings to enable this (Phase 7).",
                "এটি চালু করতে সেটিংসে টুইলিও ক্রেডেনশিয়াল দিন (ফেজ ৭)।"));
        m.put("livecall.voice_down", pair(
                "The voice server is not answering. Start it and try again.",
                "ভয়েস সার্ভার সাড়া দিচ্ছে না। এটি চালু করে আবার চেষ্টা করুন।"));
        m.put("livecall.disconnected", pair("The call was cut off.", "কলটি কেটে গেছে।"));
    }

    /** One readable label per settings key, so the panel never shows raw key names. */
    private static void addSettingLabels(Map<String, String[]> m) {
        m.put("key.llm_provider", pair("Provider", "প্রোভাইডার"));
        m.put("key.llm_model", pair("Model", "মডেল"));
        m.put("key.gemini_api_key", pair("Gemini API key", "জেমিনি এপিআই কী"));
        m.put("key.openai_api_key", pair("OpenAI API key", "ওপেনএআই এপিআই কী"));
        m.put("key.openai_model_default", pair("OpenAI model", "ওপেনএআই মডেল"));

        m.put("key.gcp_credentials_path", pair("Google credentials file", "গুগল ক্রেডেনশিয়াল ফাইল"));
        m.put("key.stt_provider", pair("Speech-to-text provider", "স্পিচ-টু-টেক্সট প্রোভাইডার"));
        m.put("key.tts_provider", pair("Text-to-speech provider", "টেক্সট-টু-স্পিচ প্রোভাইডার"));
        m.put("key.tts_voice_en", pair("English voice", "ইংরেজি কণ্ঠ"));
        m.put("key.tts_voice_bn", pair("Bangla voice", "বাংলা কণ্ঠ"));
        m.put("key.tts_rate", pair("Speaking rate", "কথার গতি"));
        m.put("key.tts_volume", pair("Volume", "ভলিউম"));
        m.put("key.default_language", pair("Default language", "ডিফল্ট ভাষা"));

        m.put("key.inactivity_warn_s", pair("Warn after silence (seconds)", "নীরবতার পর সতর্ক করুন (সেকেন্ড)"));
        m.put("key.inactivity_hangup_s", pair("Hang up after silence (seconds)", "নীরবতার পর কল কাটুন (সেকেন্ড)"));

        m.put("key.twilio_account_sid", pair("Account SID", "অ্যাকাউন্ট SID"));
        m.put("key.twilio_auth_token", pair("Auth token", "অথ টোকেন"));
        m.put("key.twilio_api_key_sid", pair("API key SID", "এপিআই কী SID"));
        m.put("key.twilio_api_key_secret", pair("API key secret", "এপিআই কী সিক্রেট"));
        m.put("key.twilio_twiml_app_sid", pair("TwiML app SID", "TwiML অ্যাপ SID"));
        m.put("key.twilio_caller_number", pair("Caller number", "কলার নম্বর"));
        m.put("key.public_media_url", pair("Public media URL", "পাবলিক মিডিয়া URL"));

        m.put("key.smtp_host", pair("SMTP host", "SMTP হোস্ট"));
        m.put("key.smtp_port", pair("SMTP port", "SMTP পোর্ট"));
        m.put("key.smtp_username", pair("SMTP username", "SMTP ইউজারনেম"));
        m.put("key.smtp_password", pair("SMTP password", "SMTP পাসওয়ার্ড"));
        m.put("key.smtp_from", pair("Send from address", "প্রেরকের ঠিকানা"));
    }

    private static String[] pair(String english, String bangla) {
        return new String[]{english, bangla};
    }
}
