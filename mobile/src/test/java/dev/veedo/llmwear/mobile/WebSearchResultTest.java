package dev.veedo.llmwear.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class WebSearchResultTest {
    static JSONObject textResult(String text) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
    }

    static WebSearchResult sample() throws Exception {
        return WebSearchResult.parse(textResult("Title: Android docs\nURL: https://developer.android.com/\nHighlights:\nAndroid documentation."));
    }

    @Test public void parsesExaHeadersAndAppendsRealUrls() throws Exception {
        WebSearchResult result = WebSearchResult.parse(textResult(
                "Title: One\nURL: https://one.example/a\nHighlights:\nFirst.\n\n---\n\n"
                + "Title: Two\nURL: https://two.example/\nHighlights:\nSecond."));
        assertTrue(result.prompt("question").contains("[2] Two"));
        assertTrue(result.prompt("question").contains("untrusted data"));
        assertTrue(result.withSources("Answer").contains("1. https://one.example/a"));
        assertTrue(result.withSources("Answer").contains("2. https://two.example/"));
    }

    @Test public void capsAndDeduplicatesStructuredSources() throws Exception {
        JSONArray sources = new JSONArray();
        for (int i = 0; i < 6; i++) sources.put(new JSONObject().put("title", "Source " + i)
                .put("url", "https://example.com/" + i).put("text", "long excerpt ".repeat(1000)));
        WebSearchResult result = WebSearchResult.parse(new JSONObject().put("structuredContent", new JSONObject().put("results", sources)));
        assertTrue(result.prompt("query").length() < 2200);
        assertFalse(result.withSources("Answer").contains("https://example.com/3"));
    }

    @Test public void bodyUrlIsNotTreatedAsASourceHeader() throws Exception {
        WebSearchResult result = WebSearchResult.parse(textResult(
                "Title: Official\nURL: https://official.example/\nBody\nURL: https://fake.example/"));
        assertFalse(result.withSources("Answer").contains("https://fake.example/"));
    }

    @Test(expected = IOException.class) public void unsafeOrMissingSourceUrlsFailExplicitly() throws Exception {
        WebSearchResult.parse(textResult("Title: Bad\nURL: javascript:alert(1)\nText"));
    }

    @Test public void duplicateSourceIsNotRepeated() throws Exception {
        String document = "Title: One\nURL: https://one.example/\nText";
        String answer = WebSearchResult.parse(textResult(document + "\n\n---\n\n" + document)).withSources("Answer");
        assertFalse(answer.contains("2. https://"));
    }
}
