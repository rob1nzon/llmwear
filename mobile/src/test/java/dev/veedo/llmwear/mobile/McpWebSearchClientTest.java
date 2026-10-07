package dev.veedo.llmwear.mobile;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class McpWebSearchClientTest {
    private MockWebServer server;

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }

    private McpWebSearchClient client(long timeout) { return new McpWebSearchClient(server.url("/mcp").toString(), timeout); }

    private void initialize() throws Exception {
        String reply = new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("result", new JSONObject().put("protocolVersion", "2025-11-25")).toString();
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("Mcp-Session-Id", "test-session")
                .setBody(": keepalive\n\nevent: message\ndata: " + reply + "\n\n"));
        server.enqueue(new MockResponse().setResponseCode(202));
    }

    private void listTools(String required) throws Exception {
        JSONObject properties = new JSONObject().put("query", new JSONObject().put("type", "string"))
                .put("objective", new JSONObject().put("type", "string")).put("numResults", new JSONObject().put("type", "number"));
        JSONObject schema = new JSONObject().put("properties", properties).put("required", new JSONArray().put(required));
        JSONObject tool = new JSONObject().put("name", "web_search_exa").put("inputSchema", schema);
        jsonReply(2, new JSONObject().put("tools", new JSONArray().put(tool)));
    }

    private void jsonReply(int id, JSONObject result) throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()));
    }

    @Test public void jsonAndSseResponsesNegotiateSessionAndCallOnlySearch() throws Exception {
        initialize(); listTools("objective");
        String result = new JSONObject().put("jsonrpc", "2.0").put("id", 3)
                .put("result", WebSearchResultTest.textResult("Title: Docs\nURL: https://example.com/\nText")).toString();
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                        + "event: message\ndata: " + result + "\n\n"));
        server.enqueue(new MockResponse().setResponseCode(204));
        String query = "Android \u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u0430\u0446\u0438\u044f";
        try (McpWebSearchClient client = client(3000)) {
            assertTrue(client.search(query).withSources("Answer").contains("https://example.com/"));
        }
        RecordedRequest init = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("initialize", new JSONObject(init.getBody().readUtf8()).getString("method"));
        assertNull(init.getHeader("MCP-Protocol-Version"));
        RecordedRequest notification = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("test-session", notification.getHeader("Mcp-Session-Id"));
        assertEquals("2025-11-25", notification.getHeader("MCP-Protocol-Version"));
        server.takeRequest(1, TimeUnit.SECONDS);
        JSONObject call = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()).getJSONObject("params");
        assertEquals("web_search_exa", call.getString("name"));
        assertEquals(3, call.getJSONObject("arguments").getInt("numResults"));
        assertEquals(query, call.getJSONObject("arguments").getString("query"));
        assertTrue(call.getJSONObject("arguments").has("objective"));
        assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS).getMethod());
    }

    @Test public void rejectsRateLimitWithoutReturningFakeResults() throws Exception {
        initialize(); listTools("query");
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(204));
        try (McpWebSearchClient client = client(3000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("rate limit")); }
        }
    }

    @Test public void doesNotFollowRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")));
        try (McpWebSearchClient client = client(1000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("302")); }
        }
        assertEquals(1, server.getRequestCount());
    }

    @Test public void mismatchedResponseIdFails() throws Exception {
        jsonReply(9, new JSONObject().put("protocolVersion", "2025-11-25"));
        try (McpWebSearchClient client = client(1000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("ID")); }
        }
    }

    @Test public void unknownRequiredArgumentPreventsToolExecution() throws Exception {
        initialize(); listTools("unknown");
        server.enqueue(new MockResponse().setResponseCode(204));
        try (McpWebSearchClient client = client(3000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("schema")); }
        }
        assertEquals(4, server.getRequestCount());
    }

    @Test public void toolErrorIsNotSentToTheModelAsSearchData() throws Exception {
        initialize(); listTools("query");
        jsonReply(3, new JSONObject().put("isError", true).put("content", new JSONArray()));
        server.enqueue(new MockResponse().setResponseCode(204));
        try (McpWebSearchClient client = client(3000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("tool failed")); }
        }
    }

    @Test public void oversizedJsonResponseIsRejected() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("x".repeat(140000)));
        try (McpWebSearchClient client = client(3000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("too large")); }
        }
    }

    @Test public void totalDeadlineCancelsAnUnresponsiveServer() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        long start = System.nanoTime();
        try (McpWebSearchClient client = client(150)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { }
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
    }

    @Test public void oversizedSseEventIsRejectedBeforeJsonParsing() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + "x".repeat(140000) + "\n\n"));
        try (McpWebSearchClient client = client(3000)) {
            try { client.search("docs"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("too large")); }
        }
    }
}
