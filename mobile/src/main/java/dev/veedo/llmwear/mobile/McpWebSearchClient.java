package dev.veedo.llmwear.mobile;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A restricted MCP 2025 Streamable HTTP client for Exa's read-only search tool. */
final class McpWebSearchClient implements AutoCloseable {
    static final String ENDPOINT = "https://mcp.exa.ai/mcp?tools=web_search_exa";
    private static final int MAX_REPLY = 128 * 1024;
    private final String endpoint;
    private final long deadline;
    private final OkHttpClient http;
    private volatile String session;
    private String version;
    private int nextId;

    McpWebSearchClient(String endpoint, long timeoutMillis) {
        this.endpoint = endpoint;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(chain -> limitResponse(chain.proceed(chain.request()))).build();
    }

    static WebSearchResult searchExa(String query) throws Exception {
        try (McpWebSearchClient client = new McpWebSearchClient(ENDPOINT, 30_000)) {
            return client.search(query);
        }
    }

    WebSearchResult search(String query) throws Exception {
        if (query.trim().isEmpty() || query.length() > 300) {
            throw new IllegalArgumentException("Search query must contain 1 to 300 characters");
        }
        JSONObject initialized = rpc("initialize", new JSONObject()
                .put("protocolVersion", "2025-11-25").put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "llmwear").put("version", "0.1.0")));
        version = initialized.getString("protocolVersion");
        if (!"2025-11-25".equals(version) && !"2025-06-18".equals(version) && !"2025-03-26".equals(version)) {
            throw new IOException("Unsupported MCP protocol version: " + version);
        }
        try (Response response = http.newBuilder().callTimeout(remaining(), TimeUnit.MILLISECONDS).build()
                .newCall(request(new JSONObject().put("jsonrpc", "2.0")
                        .put("method", "notifications/initialized"))).execute()) {
            if (response.code() != 202 && response.code() != 204) throw status(response.code());
        }
        JSONArray tools = rpc("tools/list", new JSONObject()).getJSONArray("tools");
        JSONObject schema = null;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if ("web_search_exa".equals(tool.optString("name"))) schema = tool.getJSONObject("inputSchema");
        }
        if (schema == null) throw new IOException("MCP server does not offer web_search_exa");
        JSONObject properties = schema.getJSONObject("properties");
        if (!properties.has("query")) throw new IOException("Unsupported web search schema");
        JSONObject arguments = new JSONObject().put("query", query);
        if (properties.has("numResults")) arguments.put("numResults", 3);
        if (properties.has("objective")) arguments.put("objective",
                "Answer the search query using reliable sources. Return concise factual excerpts and source URLs.");
        JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                if (!arguments.has(required.getString(i))) throw new IOException("Unsupported web search schema");
            }
        }
        JSONObject result = rpc("tools/call", new JSONObject()
                .put("name", "web_search_exa").put("arguments", arguments));
        if (result.optBoolean("isError", false)) throw new IOException("MCP search tool failed; retry later");
        return WebSearchResult.parse(result);
    }

    private Request request(JSONObject body) {
        Request.Builder request = new Request.Builder().url(endpoint)
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")));
        if (session != null) request.header("Mcp-Session-Id", session);
        if (version != null) request.header("MCP-Protocol-Version", version);
        return request.build();
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        int id = ++nextId;
        JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("method", method).put("params", params);
        CompletableFuture<JSONObject> reply = new CompletableFuture<>();
        OkHttpClient client = http.newBuilder().callTimeout(remaining(), TimeUnit.MILLISECONDS).build();
        EventSource events = EventSources.createFactory(client).newEventSource(request(body), new EventSourceListener() {
            @Override public void onOpen(EventSource source, Response response) {
                try { captureSession(response); }
                catch (IOException failure) {
                    reply.completeExceptionally(failure);
                    source.cancel();
                }
            }

            @Override public void onEvent(EventSource source, String eventId, String type, String data) {
                try {
                    if (data.length() > MAX_REPLY) throw new IOException("MCP response is too large");
                    JSONObject message = new JSONObject(data);
                    if (!message.has("id")) return;
                    complete(message);
                    source.cancel();
                } catch (Exception failure) {
                    reply.completeExceptionally(failure);
                    source.cancel();
                }
            }

            private void complete(JSONObject message) throws Exception {
                Object responseId = message.opt("id");
                if (!"2.0".equals(message.optString("jsonrpc"))
                        || !(responseId instanceof Number) || ((Number) responseId).doubleValue() != id) {
                    throw new IOException("Invalid MCP response ID or version");
                }
                if (message.has("error")) throw new IOException("MCP request failed: "
                        + message.getJSONObject("error").optString("message", "Unknown error"));
                reply.complete(message.getJSONObject("result"));
            }

            @Override public void onFailure(EventSource source, Throwable failure, Response response) {
                if (reply.isDone()) return;
                try {
                    if (failure instanceof IOException) throw (IOException) failure;
                    if (response == null) throw new IOException("MCP search connection failed", failure);
                    if (!response.isSuccessful()) throw status(response.code());
                    captureSession(response);
                    ResponseBody content = response.body();
                    if (content == null || content.contentType() == null
                            || !"application".equals(content.contentType().type())
                            || !"json".equals(content.contentType().subtype())) {
                        throw new IOException("Unsupported MCP response content type", failure);
                    }
                    if (content.source().request(MAX_REPLY + 1L)) throw new IOException("MCP response is too large");
                    complete(new JSONObject(content.source().readUtf8()));
                } catch (Exception error) { reply.completeExceptionally(error); }
            }

            @Override public void onClosed(EventSource source) {
                reply.completeExceptionally(new IOException("MCP stream ended without a result"));
            }
        });
        try {
            return reply.get(remaining(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("MCP request failed", cause);
        } catch (TimeoutException failure) {
            throw new IOException("MCP search timed out", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP search cancelled", failure);
        } finally { events.cancel(); }
    }

    private void captureSession(Response response) throws IOException {
        if (session == null && response.header("Mcp-Session-Id") != null) {
            String value = response.header("Mcp-Session-Id");
            if (value.isEmpty() || value.length() > 256 || !value.matches("[\\x21-\\x7E]+")) {
                throw new IOException("Invalid MCP session header");
            }
            session = value;
        }
    }

    private long remaining() throws IOException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new IOException("MCP search timed out");
        return millis;
    }

    private static IOException status(int code) {
        return new IOException(code == 429 ? "MCP search rate limit reached; retry later" : "MCP search HTTP " + code);
    }

    private static Response limitResponse(Response response) {
        ResponseBody body = response.body();
        if (body == null) return response;
        // Bound decompressed bytes before the SSE parser allocates an event.
        BufferedSource limited = Okio.buffer(new ForwardingSource(body.source()) {
            private long count;
            @Override public long read(Buffer sink, long byteCount) throws IOException {
                long read = super.read(sink, Math.min(byteCount, MAX_REPLY + 1L - count));
                if (read > 0) {
                    count += read;
                    if (count > MAX_REPLY) throw new IOException("MCP response is too large");
                }
                return read;
            }
        });
        return response.newBuilder().body(new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public BufferedSource source() { return limited; }
        }).build();
    }

    @Override public void close() {
        if (session != null) {
            try (Response ignored = http.newBuilder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
                    .newCall(new Request.Builder().url(endpoint).header("Mcp-Session-Id", session)
                            .header("MCP-Protocol-Version", version == null ? "2025-11-25" : version)
                            .delete().build()).execute()) {
                // Session cleanup is best effort, including after cancellation or expiry.
            } catch (IOException | IllegalArgumentException ignored) { }
        }
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }
}
