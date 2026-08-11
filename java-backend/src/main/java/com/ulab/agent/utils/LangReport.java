package com.ulab.agent.utils;

import java.util.Map;

/**
 * The report's own vocabulary: the page, its numbers and the dialog that asks
 * which days it should cover.
 *
 * A third file for the same reason there is a second one — a file may not pass
 * five hundred lines, and {@link LangPages} was already at four hundred and
 * forty. It is still one catalogue, still reached only through {@link Lang},
 * and a wording is still changed in exactly one place.
 */
final class LangReport {

    private LangReport() {
        // Static builders only; nothing to construct.
    }

    static void addAll(Map<String, String[]> m) {
        addPageStrings(m);
        addNumberLabels(m);
        addDialogStrings(m);
    }

    /**
     * The report as a document.
     *
     * Its headings are written to be read on paper by somebody who has never
     * seen this panel, which is why none of them is a column name — a printed
     * page has no column to point at.
     */
    private static void addPageStrings(Map<String, String[]> m) {
        m.put("nav.report", pair("Report", "রিপোর্ট"));
        m.put("report.title", pair("Call report", "কল রিপোর্ট"));
        m.put("report.note", pair(
                "Every call over one stretch of days: how many came in, how they turned out, how quickly they were answered, and what they left for somebody to do.",
                "একটি সময়সীমার সব কল একসাথে: কতগুলো এসেছে, কীভাবে শেষ হয়েছে, কত দ্রুত উত্তর গেছে, আর কার জন্য কী কাজ রেখে গেছে।"));
        m.put("report.range", pair("%s to %s", "%s থেকে %s"));
        m.put("report.generated", pair("Worked out %s", "তৈরি হয়েছে %s"));
        m.put("report.print", pair("Print or save as PDF", "ছাপুন বা PDF করে রাখুন"));
        m.put("report.back", pair("Back to call history", "কল ইতিহাসে ফিরুন"));
        m.put("report.empty", pair("No calls in this range", "এই সময়সীমায় কোনো কল নেই"));
        m.put("report.change_range", pair("Choose another range", "অন্য সময়সীমা বাছুন"));

        m.put("report.outcomes", pair("How the calls turned out", "কল কীভাবে শেষ হয়েছে"));
        m.put("report.languages", pair("Which language", "কোন ভাষায়"));
        m.put("report.by_day", pair("Calls by day", "দিন অনুযায়ী কল"));
        m.put("report.follow_ups", pair("What the calls left to do", "যে কাজ বাকি রেখে গেছে"));
        m.put("report.no_follow_ups", pair("Nothing was left to follow up.",
                "কোনো কাজ বাকি রাখা হয়নি।"));
        m.put("report.calls", pair("Every call in this range", "এই সময়সীমার সব কল"));
    }

    /**
     * The tiles across the top.
     *
     * Each is short enough to sit under a number without wrapping, which the
     * contract's stat-tile requires of its label, and says what was counted
     * rather than which field it came from.
     */
    private static void addNumberLabels(Map<String, String[]> m) {
        m.put("report.calls_total", pair("Calls", "কল"));
        m.put("report.answered", pair("Answered", "কথা হয়েছে"));
        m.put("report.no_answer", pair("Nobody spoke", "কেউ কথা বলেনি"));
        m.put("report.escalated", pair("Passed to a person", "মানুষের কাছে গেছে"));
        m.put("report.written_up", pair("Written up", "সারসংক্ষেপ লেখা"));
        m.put("report.callers", pair("Customers recognised", "চেনা গ্রাহক"));
        m.put("report.talk_time", pair("Time on calls", "কলে মোট সময়"));
        m.put("report.median_reply", pair("Usual reply", "সাধারণত উত্তর"));
        m.put("report.slowest_tenth", pair("Slowest tenth", "ধীরতম দশ ভাগের এক"));

        // Lengths are read aloud as words rather than as a clock: "2h 14m" is a
        // duration, while 2:14:00 could be a time of day.
        m.put("report.hours_minutes", pair("%sh %sm", "%s ঘণ্টা %s মিনিট"));
        m.put("report.minutes", pair("%sm", "%s মিনিট"));
    }

    /** Choosing what the report covers, before there is a report to look at. */
    private static void addDialogStrings(Map<String, String[]> m) {
        m.put("report.generate", pair("Generate report", "রিপোর্ট তৈরি করুন"));
        m.put("report.dialog_title", pair("Report on call history", "কল ইতিহাসের রিপোর্ট"));
        m.put("report.dialog_note", pair(
                "Choose which days and which business the report covers. The last day is counted in full.",
                "কোন দিনগুলো আর কোন ব্যবসা রিপোর্টে থাকবে বেছে নিন। শেষ দিনটিও পুরোপুরি ধরা হয়।"));
        m.put("report.field_from", pair("First day", "প্রথম দিন"));
        m.put("report.field_to", pair("Last day", "শেষ দিন"));
        m.put("report.field_business", pair("Which business", "কোন ব্যবসা"));
        m.put("report.make", pair("Make the report", "রিপোর্ট বানান"));
        m.put("report.bad_range", pair("The first day comes after the last day.",
                "প্রথম দিনটি শেষ দিনের পরে পড়েছে।"));
        m.put("report.needs_a_day", pair("Choose a day.", "একটি দিন বাছুন।"));
    }

    private static String[] pair(String english, String bangla) {
        return new String[]{english, bangla};
    }
}
