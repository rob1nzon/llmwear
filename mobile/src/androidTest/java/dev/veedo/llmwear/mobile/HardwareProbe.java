package dev.veedo.llmwear.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Opt-in device smoke test; lives only in the separate instrumentation APK. */
public final class HardwareProbe extends Instrumentation {
    private boolean gpu;
    private boolean weather;
    private String networkUrl;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        gpu = Boolean.parseBoolean(arguments.getString("gpu", "false"));
        weather = "weather".equals(arguments.getString("mode"));
        if ("network".equals(arguments.getString("mode"))) networkUrl = arguments.getString("url");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        result.putString("backend", gpu ? "GPU" : "CPU");
        int status = Activity.RESULT_CANCELED;
        LiteRtLlmEngine engine = null;
        try {
            if (networkUrl != null) {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(networkUrl).openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "LLMWear/0.1 (Android personal weather client)");
                try {
                    result.putInt("http", connection.getResponseCode());
                    try (java.io.InputStream input = connection.getInputStream()) {
                        byte[] sample = new byte[300];
                        int size = input.read(sample);
                        result.putString("sample", size > 0 ? new String(sample, 0, size, java.nio.charset.StandardCharsets.UTF_8) : "");
                    }
                    status = Activity.RESULT_OK;
                } finally { connection.disconnect(); }
                return;
            }
            if (weather) {
                result.putString("city", WeatherClient.city(getTargetContext()));
                result.putString("current", WeatherClient.forecast(getTargetContext(), false));
                result.putString("tomorrow", WeatherClient.forecast(getTargetContext(), true));
                status = Activity.RESULT_OK;
                return;
            }
            engine = new LiteRtLlmEngine(getTargetContext(), gpu);
            result.putString("model", engine.modelName());
            String math = engine.generate("What is 2 + 2? Answer with one number.");
            result.putString("math", math);
            result.putString("russian", engine.generate("Ответь одним словом: привет."));
            result.putString("identity", engine.generate("Кто ты? Ответь кратко на русском."));
            result.putBoolean("math_correct", math.trim().replace(".", "").equals("4"));
            if (result.getBoolean("math_correct")) status = Activity.RESULT_OK;
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            result.putString("error", e.toString());
            if (e.getCause() != null) result.putString("cause", e.getCause().toString());
        } finally {
            if (engine != null) engine.close();
            finish(status, result);
        }
    }
}
