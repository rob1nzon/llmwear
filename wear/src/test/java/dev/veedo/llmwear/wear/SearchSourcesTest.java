package dev.veedo.llmwear.wear;

import dev.veedo.llmwear.commands.SearchSources;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchSourcesTest {
    @Test public void searchUrlsStayVisibleButAreNotSpoken() {
        String answer = "Answer [1]" + SearchSources.HEADER + "1. https://example.com/\n2. https://other.example/";
        assertEquals("Answer [1]", SearchSources.forSpeech(answer));
    }
    @Test public void ordinaryAnswersAreUnchanged() {
        assertEquals("Hello", SearchSources.forSpeech("Hello"));
        assertNull(SearchSources.forSpeech(null));
    }
    @Test public void nonUrlSourceTextIsNotRemoved() {
        String answer = "Answer" + SearchSources.HEADER + "A printed book";
        assertEquals(answer, SearchSources.forSpeech(answer));
    }
}
