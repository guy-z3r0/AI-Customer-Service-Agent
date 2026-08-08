package com.ulab.agent.utils;

import java.util.Map;

/**
 * The rest of the panel's vocabulary: one method per screen.
 *
 * This is the same catalogue as {@link Lang} and is reached only through it —
 * nothing outside this package can see these methods. It is a second file
 * because a file may not pass five hundred lines and three hundred bilingual
 * strings will not fit in one, not because the strings live anywhere else.
 * Every entry still carries both languages, and a wording is still changed in
 * exactly one place.
 */
final class LangPages {

    private LangPages() {
        // Static builders only; nothing to construct.
    }

    /** Adds every page's strings to the catalogue being built. */
    static void addAll(Map<String, String[]> m) {
        addLiveCallStrings(m);
        addEditorStrings(m);
        addClientStrings(m);
        addDashboardStrings(m);
        addHistoryStrings(m);
        addEmailStrings(m);
        addSettingLabels(m);
    }
    /**
     * The call history page, and the text file one call can be downloaded as.
     *
     * The file is written by the server rather than the browser, so its
     * headings come from here in the language the call was held in — a Bangla
     * call downloads as a Bangla document.
     */
    private static void addHistoryStrings(Map<String, String[]> m) {
        m.put("history.title", pair("Call history", "কল ইতিহাস"));
        m.put("history.note", pair(
                "Every call the agent has taken: what was said, what it made of it, and what it left for somebody to do. Nothing here can be edited — a record that can be changed is not a record.",
                "এজেন্ট যত কল নিয়েছে সবই এখানে: কী বলা হয়েছে, সে কী বুঝেছে, আর কার জন্য কী কাজ রেখে গেছে। এখানে কিছু সম্পাদনা করা যায় না — যে রেকর্ড বদলানো যায় সেটি রেকর্ড নয়।"));
        m.put("history.all_businesses", pair("Every business", "সব ব্যবসা"));
        m.put("history.col_started", pair("Started", "শুরু"));
        m.put("history.col_business", pair("Business", "ব্যবসা"));
        m.put("history.col_caller", pair("Caller", "কলার"));
        m.put("history.col_mode", pair("Outcome", "ফলাফল"));
        m.put("history.col_turns", pair("Turns", "পালা"));
        m.put("history.col_length", pair("Length", "দৈর্ঘ্য"));
        m.put("history.col_summary", pair("Written up", "লেখা হয়েছে"));
        m.put("history.empty", pair("No calls yet — place one from the Live call page",
                "এখনও কোনো কল হয়নি — লাইভ কল পেজ থেকে একটি করুন"));
        m.put("history.open", pair("Open", "খুলুন"));
        m.put("history.export", pair("Download as text", "টেক্সট ফাইলে নামান"));
        m.put("history.back", pair("Back to the list", "তালিকায় ফিরুন"));
        m.put("history.detail_title", pair("One call", "একটি কল"));
        m.put("history.facts", pair("The call", "কলটি"));
        m.put("history.summary", pair("What the call was about", "কলটি কী নিয়ে ছিল"));
        m.put("history.structured", pair("What the model made of it", "মডেল যা বুঝেছে"));
        m.put("history.actions", pair("Follow-up", "পরবর্তী কাজ"));
        m.put("history.no_actions", pair("Nothing to follow up", "করার কিছু নেই"));
        m.put("history.transcript", pair("Transcript", "কথোপকথন"));
        m.put("history.screening", pair("How it was screened", "কীভাবে স্ক্রিন করা হয়েছে"));
        m.put("history.no_summary", pair("This call has no written summary.",
                "এই কলের কোনো লিখিত সারসংক্ষেপ নেই।"));
        m.put("history.reason", pair("Ended because", "শেষ হওয়ার কারণ"));
        m.put("history.summarised", pair("Yes", "হ্যাঁ"));
        m.put("history.not_summarised", pair("Not yet", "এখনও নয়"));

        // Said in the panel, in the downloaded file and in the escalation email,
        // which is why these two are not under any one of those headings.
        // How a call is described once it is over. A call nobody spoke on is
        // reported as that rather than as whatever screening mode it opened in,
        // which would read as a conversation that never happened.
        m.put("outcome.no_answer", pair("No answer", "কেউ কথা বলেনি"));
        m.put("outcome.known_no_answer", pair("Known customer — no answer",
                "পরিচিত গ্রাহক — কেউ কথা বলেনি"));
        m.put("call.not_recognised", pair("not recognised", "চেনা যায়নি"));
        m.put("call.still_running", pair("still running", "চলছে"));

        m.put("export.title", pair("Call transcript", "কল কথোপকথন"));
        m.put("export.business", pair("Business", "ব্যবসা"));
        m.put("export.caller", pair("Caller", "কলার"));
        m.put("export.started", pair("Started", "শুরু"));
        m.put("export.length", pair("Length", "দৈর্ঘ্য"));
        m.put("export.mode", pair("Outcome", "ফলাফল"));
        m.put("export.turns", pair("Turns", "পালা"));
        m.put("export.summary", pair("Summary", "সারসংক্ষেপ"));
        m.put("export.actions", pair("Follow-up", "পরবর্তী কাজ"));
        m.put("export.transcript", pair("Transcript", "কথোপকথন"));

        m.put("summary.unwritten", pair(
                "This call was not written up: no language model was reachable when it ended. The transcript is the whole record.",
                "এই কলের সারসংক্ষেপ লেখা যায়নি: কল শেষ হওয়ার সময় কোনো ভাষা মডেল পাওয়া যায়নি। কথোপকথনটিই পুরো রেকর্ড।"));
    }

    /**
     * The one email this app sends: the note to a colleague saying a caller was
     * promised a person. It is written in the language the call was held in,
     * because whoever picks the call up is going to speak that language to them.
     */
    private static void addEmailStrings(Map<String, String[]> m) {
        m.put("email.escalation_subject", pair("A caller needs a person — %s",
                "একজন কলারের জন্য মানুষ দরকার — %s"));
        m.put("email.escalation_intro", pair(
                "The AI agent could not settle this call on its own, and told the caller that a colleague would follow it up.",
                "এআই এজেন্ট এই কলটি নিজে শেষ করতে পারেনি, এবং কলারকে বলেছে একজন সহকর্মী বিষয়টি দেখবেন।"));
        m.put("email.when", pair("When", "কখন"));
        m.put("email.caller", pair("Caller", "কলার"));
        m.put("email.reason", pair("Why it was passed on", "কেন হস্তান্তর করা হলো"));
        m.put("email.summary", pair("What the call was about", "কলটি কী নিয়ে ছিল"));
        m.put("email.actions", pair("Follow-up", "পরবর্তী কাজ"));
        m.put("email.transcript", pair("The call, line by line", "কল, লাইন ধরে ধরে"));
        m.put("email.footer", pair(
                "ID numbers, phone numbers, email addresses and amounts have been masked in this message.",
                "এই বার্তায় এনআইডি, ফোন নম্বর, ইমেইল ঠিকানা ও টাকার অঙ্ক ঢেকে দেওয়া হয়েছে।"));
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
        m.put("livecall.duration", pair("Call length", "কলের দৈর্ঘ্য"));
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
                "Fill in the Twilio settings and the public media URL to enable this.",
                "এটি চালু করতে টুইলিও সেটিংস ও পাবলিক মিডিয়া URL পূরণ করুন।"));
        m.put("livecall.twilio_failed", pair(
                "The telephone call could not be placed.",
                "টেলিফোন কলটি করা যায়নি।"));
        m.put("livecall.twilio_no_sdk", pair(
                "Could not load the telephone library. Check the internet connection.",
                "টেলিফোন লাইব্রেরিটি লোড করা যায়নি। ইন্টারনেট সংযোগ দেখুন।"));
        m.put("livecall.twilio_unsupported", pair(
                "This browser cannot place a telephone call.",
                "এই ব্রাউজার টেলিফোন কল করতে পারে না।"));
        m.put("livecall.voice_down", pair(
                "The voice server is not answering. Start it and try again.",
                "ভয়েস সার্ভার সাড়া দিচ্ছে না। এটি চালু করে আবার চেষ্টা করুন।"));
        m.put("livecall.disconnected", pair("The call was cut off.", "কলটি কেটে গেছে।"));
    }

    /**
     * The Dashboard: what the system holds, what it can do right now, and what
     * it has been doing. The capability lines are deliberately blunt about what
     * is switched off — a demo should never be the place someone finds out.
     */
    private static void addDashboardStrings(Map<String, String[]> m) {
        m.put("dash.title", pair("Overview", "সারসংক্ষেপ"));
        m.put("dash.note", pair(
                "Everything the agent knows, and everything it can do right now.",
                "এজেন্ট যা কিছু জানে, এবং এই মুহূর্তে যা কিছু করতে পারে।"));

        m.put("dash.businesses", pair("Businesses", "ব্যবসা"));
        m.put("dash.knowledge", pair("Knowledge entries", "জ্ঞানের এন্ট্রি"));
        m.put("dash.customers", pair("Customers", "গ্রাহক"));
        m.put("dash.calls_total", pair("Calls", "কল"));
        m.put("dash.calls_today", pair("Calls today", "আজকের কল"));
        m.put("dash.median_reply", pair("Median reply", "গড় উত্তর"));
        m.put("dash.slowest_tenth", pair("Slowest one in ten", "দশটির মধ্যে সবচেয়ে ধীর"));
        m.put("dash.no_calls_yet", pair("no calls yet", "এখনও কোনো কল হয়নি"));
        m.put("dash.modes", pair("How calls end up", "কল কীভাবে শেষ হয়"));

        m.put("dash.capabilities", pair("What works right now", "এখন যা কাজ করছে"));
        m.put("dash.state_ready", pair("Ready", "প্রস্তুত"));
        m.put("dash.state_degraded", pair("Free fallback", "বিনামূল্যের বিকল্প"));
        // "Off" rather than "Needs a key": the voice server is off because
        // nobody started it, and the line beside each one says which it is.
        m.put("dash.state_off", pair("Off", "বন্ধ"));

        m.put("dash.cap.database", pair("Database and knowledge base", "ডেটাবেস ও জ্ঞানভাণ্ডার"));
        m.put("dash.cap.voice", pair("Voice server", "ভয়েস সার্ভার"));
        m.put("dash.cap.model", pair("Conversation", "কথোপকথন"));
        m.put("dash.cap.speech", pair("Speech recognition and voice", "স্পিচ রিকগনিশন ও কণ্ঠ"));
        m.put("dash.cap.telephony", pair("Phone calls (Twilio)", "ফোন কল (টুইলিও)"));
        m.put("dash.cap.email", pair("Escalation email", "এসকেলেশন ইমেইল"));

        m.put("dash.hint.voice", pair(
                "Not running. Start it before placing a call.",
                "চলছে না। কল করার আগে এটি চালু করুন।"));
        m.put("dash.hint.model", pair(
                "Paste a key in Settings and the agent starts answering.",
                "সেটিংসে একটি কী দিন, এজেন্ট উত্তর দেওয়া শুরু করবে।"));
        // Blunt on purpose. The credential-free recogniser posts the caller's
        // audio to an undocumented Google endpoint through a third-party
        // library — no contract, no retention statement, nothing to audit. An
        // operator running a customer-service line should learn that here
        // rather than from an audit.
        m.put("dash.hint.speech", pair(
                "Free fallback — the caller's audio is sent to a third-party service. "
                        + "Add Google Cloud credentials for one with an agreement behind it.",
                "বিনামূল্যের বিকল্প — কলারের অডিও একটি তৃতীয় পক্ষের সার্ভিসে পাঠানো হয়। "
                        + "চুক্তিসহ সার্ভিসের জন্য গুগল ক্লাউড ক্রেডেনশিয়াল দিন।"));
        m.put("dash.hint.optional", pair("Optional. Not needed for a call.",
                "ঐচ্ছিক। কলের জন্য দরকার নেই।"));

        m.put("dash.recent", pair("Recent calls", "সাম্প্রতিক কল"));
        m.put("dash.col_started", pair("Started", "শুরু"));
        m.put("dash.col_business", pair("Business", "ব্যবসা"));
        m.put("dash.col_mode", pair("Outcome", "ফলাফল"));
        m.put("dash.col_turns", pair("Turns", "পালা"));
        m.put("dash.col_length", pair("Length", "দৈর্ঘ্য"));
        m.put("dash.empty", pair("No calls yet — place one from the Live call page",
                "এখনও কোনো কল হয়নি — লাইভ কল পেজ থেকে একটি করুন"));
        m.put("dash.start_call", pair("Place a call", "একটি কল করুন"));
    }

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
        m.put("key.smtp_auth", pair("Log in to the relay (true/false)",
                "রিলে-তে লগ ইন করুন (true/false)"));
        m.put("key.smtp_username", pair("SMTP username", "SMTP ইউজারনেম"));
        m.put("key.smtp_password", pair("SMTP password", "SMTP পাসওয়ার্ড"));
        m.put("key.smtp_from", pair("Send from address", "প্রেরকের ঠিকানা"));
        m.put("key.log_unsent_email_body", pair("Log unsent email body (true/false)",
                "পাঠানো যায়নি এমন ইমেলের বডি লগ করুন (true/false)"));
    }
    private static String[] pair(String english, String bangla) {
        return new String[]{english, bangla};
    }
}
