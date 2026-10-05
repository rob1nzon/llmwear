package dev.veedo.llmwear.mobile;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

final class SimpleHttpServer extends NanoHTTPD {
    private static final String JSON = "application/json; charset=utf-8";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final LlmEngine engine;
    private final Forecast forecast;
    private final ReentrantLock generation = new ReentrantLock();
    private volatile boolean stopping;

    SimpleHttpServer(int port, LlmEngine engine) {
        this(port, engine, null);
    }

    interface Forecast {
        String get(boolean tomorrow) throws Exception;
    }

    SimpleHttpServer(int port, LlmEngine engine, Forecast forecast) {
        super(port);
        this.engine = engine;
        this.forecast = forecast;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (session.getMethod() == Method.OPTIONS) {
                return response(Response.Status.NO_CONTENT, "");
            }
            if ("/weather".equals(session.getUri())) {
                if (session.getMethod() != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "Use GET");
                if (forecast == null) return error(Response.Status.SERVICE_UNAVAILABLE, "Weather is unavailable");
                String tomorrow = session.getParms().getOrDefault("tomorrow", "false");
                if (!"true".equals(tomorrow) && !"false".equals(tomorrow)) {
                    return error(Response.Status.BAD_REQUEST, "tomorrow must be true or false");
                }
                return json(Response.Status.OK, new JSONObject().put("text", forecast.get(Boolean.parseBoolean(tomorrow))));
            }
            if (session.getMethod() == Method.GET && "/health".equals(session.getUri())) {
                return json(Response.Status.OK, new JSONObject().put("status", "ready")
                        .put("model", engine.modelName()).put("port", getListeningPort()));
            }
            if (session.getMethod() == Method.GET && "/v1/models".equals(session.getUri())) {
                return json(Response.Status.OK, new JSONObject().put("object", "list")
                        .put("data", new JSONArray().put(new JSONObject().put("id", engine.modelName())
                                .put("object", "model").put("owned_by", "local"))));
            }
            boolean chat = "/v1/chat/completions".equals(session.getUri());
            if (!chat && !"/generate".equals(session.getUri())) {
                return error(Response.Status.NOT_FOUND, "Not found");
            }
            if (session.getMethod() != Method.POST) {
                return error(Response.Status.METHOD_NOT_ALLOWED, "Use POST");
            }
            String length = session.getHeaders().get("content-length");
            if (length == null) {
                return error(Response.Status.LENGTH_REQUIRED, "Content-Length is required");
            }
            long size = Long.parseLong(length);
            if (size < 0 || size > MAX_BODY_BYTES) {
                return error(Response.Status.PAYLOAD_TOO_LARGE, "Request body is too large");
            }
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            JSONObject request = new JSONObject(files.getOrDefault("postData", ""));
            if (request.optBoolean("stream", false)) {
                return error(Response.Status.BAD_REQUEST, "Streaming is not supported; use stream=false");
            }
            if (request.has("max_tokens") || request.has("temperature")) {
                return error(Response.Status.BAD_REQUEST,
                        "Per-request sampling is not supported; omit max_tokens and temperature");
            }
            JSONArray messages = null;
            String prompt = null;
            if (chat) {
                messages = request.getJSONArray("messages");
                validateMessages(messages);
            } else {
                prompt = request.getString("prompt");
                if (prompt.trim().isEmpty()) {
                    return error(Response.Status.BAD_REQUEST, "Prompt is empty");
                }
            }
            if (!generation.tryLock()) {
                return error(Response.Status.SERVICE_UNAVAILABLE, "Model is busy; retry after the current answer");
            }
            try {
                if (stopping) {
                    return error(Response.Status.SERVICE_UNAVAILABLE, "Server is stopping");
                }
                String text = chat ? engine.chat(messages) : engine.generate(prompt);
                if (!chat) {
                    return json(Response.Status.OK, new JSONObject().put("model", engine.modelName()).put("text", text));
                }
                JSONObject message = new JSONObject().put("role", "assistant").put("content", text);
                JSONObject choice = new JSONObject().put("index", 0).put("message", message).put("finish_reason", "stop");
                return json(Response.Status.OK, new JSONObject().put("id", "chatcmpl-" + UUID.randomUUID())
                        .put("created", System.currentTimeMillis() / 1000).put("object", "chat.completion")
                        .put("model", engine.modelName()).put("choices", new JSONArray().put(choice)));
            } finally {
                generation.unlock();
            }
        } catch (JSONException | IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private static void validateMessages(JSONArray messages) throws JSONException {
        if (messages.length() == 0) {
            throw new IllegalArgumentException("Messages are empty");
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                throw new IllegalArgumentException("Supported roles: system, user, assistant");
            }
            if ("system".equals(role) && i != 0) {
                throw new IllegalArgumentException("System instruction must be the first message");
            }
            if (!(message.get("content") instanceof String) || message.getString("content").trim().isEmpty()) {
                throw new IllegalArgumentException("Content must be non-empty text");
            }
        }
        if (!"user".equals(messages.getJSONObject(messages.length() - 1).getString("role"))) {
            throw new IllegalArgumentException("The last message must be a user message");
        }
    }

    void closeEngine() {
        stopping = true;
        stop();
        // Native inference must finish before the engine is released, off the UI thread.
        generation.lock();
        try {
            engine.close();
        } finally {
            generation.unlock();
        }
    }

    private static Response error(Response.Status status, String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("error", message == null ? status.getDescription() : message);
        } catch (JSONException ignored) {
        }
        return json(status, body);
    }

    private static Response json(Response.Status status, JSONObject body) {
        return response(status, body.toString());
    }

    private static Response response(Response.Status status, String body) {
        Response response = newFixedLengthResponse(status, JSON, body);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        return response;
    }
}
