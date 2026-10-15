package com.example.signer.ui.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for the Back-button navigation history used by the app shell. */
class NavigationHistoryTests {

    @Test
    void backReturnsThePreviouslyVisitedScreen() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.push("selection");
        history.push("main");
        history.push("remote");

        assertEquals("main", history.back());      // Back from the remote screen
        assertEquals("selection", history.back()); // Back from the main window
        assertNull(history.back());                // first screen: nothing before it
    }

    @Test
    void consecutiveDuplicatesCollapse() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.push("selection");
        history.push("selection"); // re-shown after a dialog on top closed
        history.push("main");
        history.push("main");

        assertEquals("selection", history.back());
        assertNull(history.back());
    }

    @Test
    void popRemovesTheCurrentScreenWithoutNavigating() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.push("selection");
        history.push("main");
        history.pop(); // dialog left without a replacement

        assertEquals("selection", history.current());
        history.push("selection"); // the loop re-shows the selection screen
        assertEquals("selection", history.current());
        history.push("main");
        assertEquals("selection", history.back()); // Back from main → selection
    }

    @Test
    void clearForgetsEverything() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.push("selection");
        history.push("main");
        history.clear();

        assertNull(history.current());
        assertNull(history.back());
    }

    @Test
    void pushIgnoresNull() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.push(null);

        assertNull(history.current());
        assertNull(history.back());
    }
}
