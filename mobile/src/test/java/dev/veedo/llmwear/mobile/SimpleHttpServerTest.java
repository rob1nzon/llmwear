package dev.veedo.llmwear.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class SimpleHttpServerTest {
    private final FakeEngine engine = new FakeEngine();
    private SimpleHttpServer server;

    @Before
    public void start() throws Exception {
        server = new SimpleHttpServer(0, engine, tomorrow -> tomorrow ? "tomorrow forecast" : "current weather");
        server.start(2000, true);
    }

    @After
    public void stop() {
        engine.release.countDown();
        server.closeEngine();
        assertTrue(engine.closed);
    }

    @Test
    public void weatherUsesProviderWithoutInference() throws Exception {
        engine.fail = true;
        assertEquals("current weather", request("GET", "/weather", null).body.getString("text"));
        assertEquals("tomorrow forecast", request("GET", "/weather?tomorrow=true", null).body.getString("text"));
        assertEquals(400, request("GET", "/weather?tomorrow=maybe", null).status);
        assertEquals(405, request("POST", "/weather", "{}").status);
    }

    @Test
    public void unicodePromptUsesUtf8Bytes() throws Exception {
        String prompt = "\u041f\u0440\u0438\u0432\u0435\u0442 \ud83d\udc4b";
        Reply response = request("POST", "/generate", new JSONObject().put("prompt", prompt).toString());
        assertEquals(200, response.status);
        assertEquals("answer:" + prompt, response.body.getString("text"));
    }

    @Test
    public void invalidInputsReturnJsonErrors() throws Exception {
        assertEquals(400, request("POST", "/generate", "{").status);
        assertEquals(400, request("POST", "/generate", "{\"prompt\":\" \"}").status);
        assertEquals(400, request("POST", "/v1/chat/completions", "{\"messages\":[]}").status);
        assertEquals(405, request("GET", "/generate", null).status);
    }

    @Test
    public void chatKeepsFullHistoryAndRoles() throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "Be concise"))
                .put(new JSONObject().put("role", "user").put("content", "My name is Alex"))
                .put(new JSONObject().put("role", "assistant").put("content", "Hello Alex"))
                .put(new JSONObject().put("role", "user").put("content", "What is my name?"));
        Reply response = request("POST", "/v1/chat/completions", new JSONObject().put("messages", messages).toString());
        assertEquals(200, response.status);
        assertEquals(messages.toString(), engine.messages.toString());
        assertEquals("assistant", response.body.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("role"));
    }

    @Test
    public void unsupportedOptionsAreExplicit() throws Exception {
        Reply response = request("POST", "/generate", "{\"prompt\":\"hello\",\"stream\":true}");
        assertEquals(400, response.status);
        assertTrue(response.body.getString("error").contains("Streaming"));
    }

    @Test
    public void engineFailureReturnsReadableError() throws Exception {
        engine.fail = true;
        Reply response = request("POST", "/generate", "{\"prompt\":\"hello\"}");
        assertEquals(500, response.status);
        assertEquals("Inference failed", response.body.getString("error"));
    }

    @Test
    public void modelListReturnsLoadedModel() throws Exception {
        Reply response = request("GET", "/v1/models", null);
        assertEquals(200, response.status);
        assertEquals("test-model", response.body.getJSONArray("data").getJSONObject(0).getString("id"));
    }

    @Test
    public void concurrentInferenceIsRejectedWhileHealthStillWorks() throws Exception {
        engine.block = true;
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> first = client.submit(() -> request("POST", "/generate", "{\"prompt\":\"first\"}"));
            assertTrue(engine.entered.await(2, TimeUnit.SECONDS));
            assertEquals(503, request("POST", "/generate", "{\"prompt\":\"second\"}").status);
            assertEquals(200, request("GET", "/health", null).status);
            engine.release.countDown();
            assertEquals(200, first.get(3, TimeUnit.SECONDS).status);
        } finally {
            engine.release.countDown();
            client.shutdownNow();
        }
    }

    private Reply request(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getListeningPort() + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return new Reply(status, new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class Reply {
        final int status;
        final JSONObject body;

        Reply(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class FakeEngine implements LlmEngine {
        volatile boolean closed;
        volatile boolean fail;
        volatile boolean block;
        volatile JSONArray messages;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        public String modelName() {
            return "test-model";
        }

        public String generate(String prompt) throws Exception {
            assertFalse(closed);
            if (fail) {
                throw new IllegalStateException("Inference failed");
            }
            if (block) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test timed out");
                }
            }
            return "answer:" + prompt;
        }

        public String chat(JSONArray messages) throws Exception {
            this.messages = messages;
            return generate(messages.getJSONObject(messages.length() - 1).getString("content"));
        }

        public void close() {
            closed = true;
        }
    }
}
