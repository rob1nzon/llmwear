package dev.veedo.llmwear.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SearchLlmEngineTest {
    private static final class FakeEngine implements LlmEngine {
        int calls;
        boolean closed;
        String prompt;
        JSONArray messages;
        @Override public String modelName() { return "local"; }
        @Override public String modelState() { return "sleeping"; }
        @Override public long idleTimeoutSeconds() { return 120; }
        @Override public String lastError() { return "native error"; }
        @Override public String generate(String value) { calls++; prompt = value; return "Answer [1]"; }
        @Override public String chat(JSONArray value) { calls++; messages = value; return "Answer [1]"; }
        @Override public void close() { closed = true; }
    }

    @Test public void localRequestsAndMetadataDoNotCallMcp() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        SearchLlmEngine engine = new SearchLlmEngine(nativeEngine, query -> { fail("No external request expected"); return null; }, () -> true);
        assertEquals("local", engine.modelName());
        assertEquals("sleeping", engine.modelState());
        assertEquals("native error", engine.lastError());
        assertEquals(120, engine.idleTimeoutSeconds());
        engine.generate("private local message");
        assertEquals("private local message", nativeEngine.prompt);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"));
        engine.chat(messages);
        assertSame(messages, nativeEngine.messages);
        engine.close();
        assertTrue(nativeEngine.closed);
    }

    @Test public void explicitSearchFetchesBeforeInferenceAndAppendsSources() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        SearchLlmEngine[] holder = new SearchLlmEngine[1];
        holder[0] = new SearchLlmEngine(nativeEngine, query -> {
            assertEquals("Android docs", query);
            assertEquals(0, nativeEngine.calls);
            assertEquals("searching", holder[0].modelState());
            return WebSearchResultTest.sample();
        }, () -> true);
        String answer = holder[0].generate("web search Android docs");
        assertTrue(nativeEngine.prompt.contains("Android documentation"));
        assertTrue(answer.contains("https://developer.android.com/"));
        assertEquals("sleeping", holder[0].modelState());
    }

    @Test public void groundedChatPreservesHistoryAndDoesNotMutateInput() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        SearchLlmEngine engine = new SearchLlmEngine(nativeEngine, query -> WebSearchResultTest.sample(), () -> true);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", "Be concise"))
                .put(new JSONObject().put("role", "user").put("content", "web search Android docs"));
        engine.chat(messages);
        assertEquals("Be concise", nativeEngine.messages.getJSONObject(0).getString("content"));
        assertEquals("web search Android docs", messages.getJSONObject(1).getString("content"));
        assertTrue(nativeEngine.messages.getJSONObject(1).getString("content").contains("Search excerpts"));
    }

    @Test public void disabledAndEmptyQueriesNeverReachNetworkOrModel() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        AtomicInteger searches = new AtomicInteger();
        SearchLlmEngine engine = new SearchLlmEngine(nativeEngine, query -> { searches.incrementAndGet(); return WebSearchResultTest.sample(); }, () -> false);
        try { engine.generate("web search docs"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("disabled")); }
        engine = new SearchLlmEngine(nativeEngine, query -> { searches.incrementAndGet(); return WebSearchResultTest.sample(); }, () -> true);
        try { engine.generate("web search"); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, searches.get());
        assertEquals(0, nativeEngine.calls);
    }

    @Test public void failedSearchDoesNotLoadSleepingModelOrInventAnAnswer() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        LlmEngine lazy = new LazyLlmEngine("local", () -> { loads.incrementAndGet(); return new FakeEngine(); }, 120000, Thread::new);
        SearchLlmEngine engine = new SearchLlmEngine(lazy, query -> { throw new IOException("No network"); }, () -> true);
        try {
            try { engine.generate("web search docs"); fail(); }
            catch (IOException expected) { assertEquals("No network", expected.getMessage()); }
            assertEquals(0, loads.get());
            assertEquals("sleeping", engine.modelState());
        } finally { engine.close(); }
    }

    @Test public void russianCommandKeepsRussianAnswerEvenForAnEnglishProductName() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        SearchLlmEngine engine = new SearchLlmEngine(nativeEngine, query -> {
            assertEquals("Android docs", query);
            return WebSearchResultTest.sample();
        }, () -> true);
        engine.generate("\u043d\u0430\u0439\u0434\u0438 \u0432 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442\u0435 Android docs");
        assertTrue(nativeEngine.prompt.contains("Answer briefly in Russian"));
    }
}
