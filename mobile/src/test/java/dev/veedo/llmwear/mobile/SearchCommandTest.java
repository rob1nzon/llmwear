package dev.veedo.llmwear.mobile;

import dev.veedo.llmwear.commands.SearchCommand;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchCommandTest {
    @Test public void explicitCommandsPreserveQuery() {
        assertEquals("Android 16", SearchCommand.query("  Search the web for Android 16 "));
        assertEquals("Android 16", SearchCommand.query("web search: Android 16"));
        assertEquals("Android 16", SearchCommand.query(
                "\u041d\u0430\u0439\u0434\u0438 \u0432 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442\u0435 Android 16"));
        assertEquals("Android", SearchCommand.query(
                "\u043f\u043e\u0438\u0449\u0438 \u0432 \u0441\u0435\u0442\u0438 Android"));
    }

    @Test public void localAndAmbiguousPromptsNeverSearch() {
        assertNull(SearchCommand.query(null));
        assertNull(SearchCommand.query("Find a bug in this code"));
        assertNull(SearchCommand.query("Explain how to search the web"));
        assertNull(SearchCommand.query("web searcher"));
        assertNull(SearchCommand.query("\u043d\u0430\u0439\u0434\u0438 \u043e\u0448\u0438\u0431\u043a\u0443 \u0432 \u043a\u043e\u0434\u0435"));
    }

    @Test public void incompleteSearchIsNotPassedToTheLocalModel() {
        assertEquals("", SearchCommand.query("web search"));
        assertEquals("", SearchCommand.query("search the web for:"));
    }
}
