package com.ulab.agent.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The splitter is what decides how soon a caller hears the first word, so what
 * matters here is that a sentence comes out as soon as it is finished and not
 * one character sooner.
 */
class SentenceSplitterTest {

    @Test
    void aSentenceComesOutAsSoonAsTheNextCharacterArrives() {
        SentenceSplitter splitter = new SentenceSplitter();

        assertTrue(splitter.push("We are open").isEmpty());
        assertTrue(splitter.push(" until eight.").isEmpty(), "a full stop alone is not an ending yet");
        assertEquals(List.of("We are open until eight."), splitter.push(" Anything else?"));
    }

    @Test
    void aPriceIsNotMistakenForTheEndOfASentence() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of(), feed(splitter, "That one is 500.00 BDT"));
        assertEquals("That one is 500.00 BDT", splitter.drain());
    }

    @Test
    void aNewlineEndsASentenceOnItsOwn() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("Sure"), splitter.push("Sure\n"));
    }

    @Test
    void theBanglaDariEndsASentence() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("আমরা খোলা আছি।"), splitter.push("আমরা খোলা আছি। "));
    }

    @Test
    void whatIsLeftOverComesOutOnDrain() {
        SentenceSplitter splitter = new SentenceSplitter();
        splitter.push("One. Two.");
        assertEquals("Two.", splitter.drain());
        assertEquals("", splitter.drain(), "draining twice must not repeat the tail");
    }

    /** Pushes a string one character at a time, the way a stream really arrives. */
    private static List<String> feed(SentenceSplitter splitter, String text) {
        List<String> finished = new ArrayList<>();
        for (char c : text.toCharArray()) {
            finished.addAll(splitter.push(String.valueOf(c)));
        }
        return finished;
    }
}
