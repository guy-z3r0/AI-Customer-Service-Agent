package com.ulab.agent.utils;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether the caller just swore at the agent.
 *
 * The line answers customers, and a customer having a bad day is still a
 * customer — so this is deliberately not a politeness filter. It matches a
 * short list of words that carry no other meaning: nobody asks about opening
 * hours using them. Rudeness, sarcasm, raised voices and "this is useless" are
 * all left alone, because a person complaining about a service is the ordinary
 * work of a service line.
 *
 * <p><b>What "without context" means here.</b> Every word below is matched only
 * as a whole word, so a longer word that merely contains one of them does not
 * count. Beyond that the guard cannot read intent, and it does not try: it is
 * the reason the first hit is a warning rather than a hangup. A caller who is
 * quoting somebody else gets told once, and only a second one ends the call.
 */
public final class SlangGuard {

    private SlangGuard() {
        // Static matching only; nothing to construct.
    }

    /**
     * English abuse. Every entry here is an insult or an obscenity in every
     * reading of it — the borderline ones (damn, hell, stupid, rubbish) are
     * left out on purpose, because a caller is allowed to be annoyed.
     */
    private static final List<String> ENGLISH = List.of(
            "fuck", "fucks", "fucked", "fucking", "fucker", "fuckers", "motherfucker",
            "motherfuckers", "shit", "shits", "shitty", "bullshit", "bitch", "bitches",
            "bastard", "bastards", "asshole", "assholes", "arsehole", "arseholes",
            "cunt", "cunts", "wanker", "wankers", "slut", "sluts", "whore", "whores",
            "dickhead", "dickheads", "piss off", "screw you", "shut up");

    /**
     * Bangla abuse, in Bengali script and in the Latin spelling a recogniser
     * hands back when the call is being transcribed in English.
     *
     * Mild ones are left out for the same reason as above: গাধা and ছাগল are
     * what an irritated customer says, not what an abusive one does.
     */
    private static final List<String> BANGLA = List.of(
            "মাদারচোদ", "বাঞ্চোত", "বানচোদ", "খানকি", "খানকির", "মাগি", "মাগী",
            "চুদির", "চোদা", "চুদা", "বোকাচোদা", "হারামজাদা", "হারামজাদি", "বেশ্যা",
            "কুত্তার বাচ্চা", "শুয়োরের বাচ্চা", "শালার পো",
            "madarchod", "banchod", "banchot", "khanki", "khankir",
            "bokachoda", "chudir", "haramjada", "haramjadi", "beshya");

    /**
     * One pattern for both languages.
     *
     * UNICODE_CHARACTER_CLASS is what makes \b mean anything next to Bengali
     * letters: without it Java treats every one of them as a non-word character,
     * so the boundary lands in the middle of ordinary words and the Bangla half
     * of this list matches almost nothing.
     */
    private static final Pattern ABUSE = buildPattern();

    /** True when the caller used one of the words above as a word of its own. */
    public static boolean isAbusive(String text) {
        return text != null && !text.isBlank() && ABUSE.matcher(text).find();
    }

    private static Pattern buildPattern() {
        StringBuilder alternatives = new StringBuilder();
        Stream.concat(ENGLISH.stream(), BANGLA.stream()).forEach(word -> {
            if (!alternatives.isEmpty()) alternatives.append('|');
            // Quoted rather than trusted: one stray character in the lists above
            // would otherwise be read as regex and change what everything matches.
            alternatives.append(Pattern.quote(word));
        });
        return Pattern.compile("\\b(?:" + alternatives + ")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
