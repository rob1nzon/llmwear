package dev.veedo.llmwear.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LlmApiService extends Service {
    public static final String ACTION_START = "dev.veedo.llmwear.mobile.START";
    public static final int PORT = 8765;
    public static final long MODEL_IDLE_MILLIS = 120_000;
    private static final String CHANNEL_ID = "llm_api";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean active;
    private static volatile boolean running;
    private static volatile String status = "Stopped";
    private static volatile String lastError = "";
    private static volatile LlmEngine currentEngine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile SimpleHttpServer server;
    private volatile boolean destroyed;
    private boolean started;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static String lastError() {
        LlmEngine current = currentEngine;
        return current != null && !current.lastError().isEmpty() ? current.lastError() : lastError;
    }

    public static String modelState() {
        LlmEngine current = currentEngine;
        return current == null ? "sleeping" : current.modelState();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "LLM API", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) {
            return START_NOT_STICKY;
        }
        started = true;
        Notification notification = buildNotification("Запуск API");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
        active = true;
        status = "Starting API...";
        lastError = "";
        worker.execute(() -> {
            LlmEngine engine = null;
            try {
                if (ModelStore.isImporting()) {
                    throw new IllegalStateException("Wait for model import to finish");
                }
                boolean gpu = ModelStore.useGpu(this);
                LlmEngine lazy = new LazyLlmEngine(ModelStore.modelName(this),
                        () -> new LiteRtLlmEngine(getApplicationContext(), gpu), MODEL_IDLE_MILLIS,
                        task -> new Thread(() -> {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                            task.run();
                        }, "llm-model"));
                engine = new SearchLlmEngine(lazy, McpWebSearchClient::searchExa,
                        () -> getSharedPreferences("web_search", MODE_PRIVATE).getBoolean("enabled", true));
                if (destroyed) {
                    engine.close();
                    return;
                }
                SimpleHttpServer candidate = new SimpleHttpServer(PORT, engine,
                        tomorrow -> WeatherClient.forecast(getApplicationContext(), tomorrow));
                candidate.start(300_000, true);
                server = candidate;
                currentEngine = engine;
                handler.post(() -> {
                    if (!destroyed) {
                        running = true;
                        status = "Ready: " + ModelStore.modelName(this);
                        getSystemService(NotificationManager.class).notify(1,
                                buildNotification("API на порту " + PORT + " · модель по запросу"));
                    }
                });
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                if (engine != null) {
                    engine.close();
                }
                Log.e("LlmApiService", "Model load failed", e);
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                handler.post(() -> {
                    if (!destroyed) {
                        stopSelf();
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        status = "Stopping...";
        SimpleHttpServer existing = server;
        if (existing != null) {
            existing.stop();
        }
        worker.execute(() -> {
            try {
                if (server != null) {
                    server.closeEngine();
                    server = null;
                }
            } finally {
                active = false;
                currentEngine = null;
                status = lastError.isEmpty() ? "Stopped" : "Model error";
            }
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LLM Wear Host")
                .setContentText(detail)
                .setContentIntent(open)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }
}
