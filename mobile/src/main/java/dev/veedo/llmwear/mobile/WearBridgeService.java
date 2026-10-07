package dev.veedo.llmwear.mobile;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WearBridgeService extends WearableListenerService {
    private static final java.util.concurrent.ExecutorService weatherWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));

    @Override
    public Task<byte[]> onRequest(String node, String path, byte[] request) {
        if ("/llmwear/weather".equals(path)) {
            return Tasks.call(weatherWorker, () -> {
                JSONObject response = new JSONObject();
                try {
                    JSONObject options = new JSONObject(new String(request, StandardCharsets.UTF_8));
                    response.put("text", WeatherClient.forecast(this, options.optBoolean("tomorrow")));
                } catch (Exception e) {
                    response.put("error", e.getMessage() == null ? "Погода недоступна" : e.getMessage());
                }
                return response.toString().getBytes(StandardCharsets.UTF_8);
            });
        }
        if (!"/llmwear/status".equals(path)) return null;
        JSONObject status = new JSONObject();
        try {
            status.put("running", LlmApiService.isRunning());
            status.put("model_state", LlmApiService.modelState());
            status.put("model_available", ModelStore.modelFile(this).isFile());
        } catch (org.json.JSONException ignored) {
        }
        return Tasks.forResult(status.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!"/llmwear/generate".equals(event.getPath()) || event.getData().length > 65536) return;
        try {
            JSONObject request = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
            String id = request.getString("id");
            String prompt = request.getString("prompt").trim();
            if (id.length() > 64 || prompt.isEmpty()) {
                reply(event.getSourceNodeId(), id, "Некорректный запрос");
                return;
            }
            try {
                worker.execute(() -> generate(event.getSourceNodeId(), id, prompt));
            } catch (RejectedExecutionException e) {
                reply(event.getSourceNodeId(), id, "Модель занята. Повторите позже.");
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    private void generate(String node, String id, String prompt) {
        if (!LlmApiService.isRunning()) {
            reply(node, id, "Запустите модель на телефоне");
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:"
                    + LlmApiService.PORT + "/generate").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = new JSONObject().put("prompt", prompt).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            boolean success = connection.getResponseCode() == 200;
            InputStream input = success ? connection.getInputStream() : connection.getErrorStream();
            if (input == null) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            JSONObject response;
            try (InputStream stream = input) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                response = new JSONObject(body.toString());
            }
            response.put("id", id);
            Wearable.getMessageClient(this).sendMessage(node, "/llmwear/response",
                    response.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            reply(node, id, "Ошибка модели: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void reply(String node, String id, String error) {
        try {
            byte[] response = new JSONObject().put("id", id).put("error", error)
                    .toString().getBytes(StandardCharsets.UTF_8);
            Wearable.getMessageClient(this).sendMessage(node, "/llmwear/response", response);
        } catch (org.json.JSONException ignored) {
        }
    }
}
