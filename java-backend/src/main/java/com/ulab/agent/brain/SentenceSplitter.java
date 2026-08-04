package com.ulab.agent.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a stream of model fragments into whole sentences.
 *
 * This exists for one reason: the voice can only start speaking once it has a
 * complete sentence, and waiting for the whole reply instead would add a second
 * or more to every turn. Fragments arrive a few characters at a time and never
 * line up with anything, so they are collected here until a sentence closes.
 */
final class SentenceSplitter {

    /** Full stop, exclamation, question mark, and the Bangla dari. */
    private static final String ENDINGS = ".!?।";

    private final StringBuilder buffer = new StringBuilder();

    /** @return the sentences that closed with this fragment, usually none */
    List<String> push(String fragment) {
        if (fragment != null) buffer.append(fragment);

        List<String> finished = new ArrayList<>();
        int cut;
        while ((cut = firstBoundary()) > 0) {
            String sentence = buffer.substring(0, cut).strip();
            buffer.delete(0, cut);
            if (!sentence.isEmpty()) finished.add(sentence);
        }
        return finished;
    }

    /** Whatever is left when the model stops, which is usually the last sentence. */
    String drain() {
        String rest = buffer.toString().strip();
        buffer.setLength(0);
        return rest;
    }

    /**
     * Where the first finished sentence ends, or -1.
     *
     * A full stop only counts once something follows it, because "500." in the
     * middle of "500.00 BDT" is not the end of anything, and while the reply is
     * still arriving there is no way to tell those apart yet.
     */
    private int firstBoundary() {
        for (int i = 0; i < buffer.length(); i++) {
            char here = buffer.charAt(i);
            if (here == '\n') return i + 1;
            if (ENDINGS.indexOf(here) < 0) continue;
            if (i + 1 < buffer.length() && Character.isWhitespace(buffer.charAt(i + 1))) return i + 1;
        }
        return -1;
    }
}
