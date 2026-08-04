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
 *    change to the catalogue and nothing else.
 *
 * The catalogue outgrew one file at three hundred entries. The per-page half of
 * it is in {@link LangPages}, which is package-private and reached only from
 * here: still one catalogue, still one place a wording is changed, in two files
 * because a file may not pass five hundred lines.
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

        m.put("soon.action", pair("Go to Businesses", "ব্যবসায় যান"));

        LangPages.addAll(m);
        return Collections.unmodifiableMap(m);
    }

    private static String[] pair(String english, String bangla) {
        return new String[]{english, bangla};
    }
}
