package dev.veedo.llmwear.mobile;

import dev.veedo.llmwear.commands.WeatherCommand;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 18, 20);
    private static final int SURFACE = Color.rgb(30, 34, 38);
    private static final int INK = Color.rgb(240, 243, 241);
    private static final int MUTED = Color.rgb(150, 160, 165);
    private static final int ACCENT = Color.rgb(145, 230, 189);
    private static final int PICK_MODEL = 20;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusView;
    private TextView addressView;
    private TextView resultView;
    private EditText promptInput;
    private TextView modelView;
    private Button importButton;
    private Switch serverSwitch;
    private boolean updatingSwitch;
    private boolean generating;
    private ImageButton sendButton;
    private TextView userView;
    private TextView modelDetails;
    private ProgressBar importProgress;
    private ScrollView contentScroll;
    private volatile HttpURLConnection activeConnection;
    private RadioButton cpuButton;
    private RadioButton gpuButton;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        setContentView(buildContentView());
        if (savedInstanceState != null) {
            promptInput.setText(savedInstanceState.getString("prompt", ""));
            userView.setText(savedInstanceState.getString("user", ""));
            userView.setVisibility(userView.getText().length() == 0 ? View.GONE : View.VISIBLE);
            resultView.setText(savedInstanceState.getString("answer", "Нет сообщений"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MODEL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            ModelStore.importModel(this, uri);
            updateStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (activeConnection != null) {
            activeConnection.disconnect();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("prompt", promptInput.getText().toString());
        outState.putString("user", userView.getText().toString());
        outState.putString("answer", resultView.getText().toString());
        super.onSaveInstanceState(outState);
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));

        LinearLayout header = row();
        TextView title = label(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText("LLM Wear");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView hostLabel = label(12);
        hostLabel.setText("Телефон");
        hostLabel.setTextColor(MUTED);
        header.addView(hostLabel);
        root.addView(header, fullWidth());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.setClipToPadding(false);
        contentScroll.addView(content);
        root.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(heading("Модель"), spaced());
        modelView = label(16);
        modelView.setMaxLines(2);
        modelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(modelView, fullWidth());
        modelDetails = label(12);
        modelDetails.setTextColor(MUTED);
        content.addView(modelDetails, fullWidth());
        importProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importProgress.setIndeterminate(true);
        importProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        importProgress.setVisibility(View.GONE);
        content.addView(importProgress, new LinearLayout.LayoutParams(-1, dp(4)));

        LinearLayout modelActions = row();
        ImageButton getModel = tool(R.drawable.ic_download, "Скачать Gemma 3 1B", false);
        getModel.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/gemma3-1b-it-int4.litertlm")));
            } catch (android.content.ActivityNotFoundException e) {
                resultView.setText("Браузер недоступен");
            }
        });
        modelActions.addView(getModel, new LinearLayout.LayoutParams(dp(48), dp(48)));
        importButton = new Button(this);
        importButton.setText("Импортировать");
        importButton.setTextSize(14);
        importButton.setAllCaps(false);
        importButton.setTextColor(INK);
        importButton.setBackground(ripple(SURFACE, 8));
        importButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MODEL));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        importParams.setMargins(dp(10), 0, 0, 0);
        modelActions.addView(importButton, importParams);
        content.addView(modelActions, spaced());

        RadioGroup backend = new RadioGroup(this);
        backend.setOrientation(LinearLayout.HORIZONTAL);
        backend.setPadding(dp(4), dp(4), dp(4), dp(4));
        backend.setBackground(shape(SURFACE, 8));
        cpuButton = new RadioButton(this);
        cpuButton.setId(View.generateViewId());
        cpuButton.setText("CPU");
        styleSegment(cpuButton);
        gpuButton = new RadioButton(this);
        gpuButton.setId(View.generateViewId());
        gpuButton.setText("GPU");
        styleSegment(gpuButton);
        backend.addView(cpuButton, weighted());
        backend.addView(gpuButton, weighted());
        backend.check(ModelStore.useGpu(this) ? gpuButton.getId() : cpuButton.getId());
        backend.setOnCheckedChangeListener((group, id) -> ModelStore.setUseGpu(this, id == gpuButton.getId()));
        content.addView(backend, fullWidth());
        divider(content);

        LinearLayout serverRow = row();
        serverRow.addView(heading("API-сервер"), new LinearLayout.LayoutParams(0, dp(44), 1));
        serverSwitch = new Switch(this);
        serverSwitch.setContentDescription("API-сервер");
        serverSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingSwitch) {
                if (checked) startApi(); else stopApi();
            }
        });
        serverRow.addView(serverSwitch, new LinearLayout.LayoutParams(dp(56), dp(44)));
        content.addView(serverRow, fullWidth());
        statusView = label(13);
        content.addView(statusView, fullWidth());
        LinearLayout addressRow = row();
        addressView = label(12);
        addressView.setTextColor(MUTED);
        addressView.setTypeface(Typeface.MONOSPACE);
        addressView.setTextIsSelectable(true);
        addressRow.addView(addressView, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton copy = tool(R.drawable.ic_copy, "Скопировать адрес API", false);
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText("LLM Wear API", addressView.getText()));
            Toast.makeText(this, "Адрес скопирован", Toast.LENGTH_SHORT).show();
        });
        addressRow.addView(copy, new LinearLayout.LayoutParams(dp(40), dp(40)));
        content.addView(addressRow, fullWidth());
        divider(content);
        content.addView(heading("Погода"), spaced());
        EditText city = new EditText(this);
        city.setHint("Город");
        city.setTextSize(16);
        city.setTextColor(INK);
        city.setHintTextColor(MUTED);
        city.setSingleLine(true);
        city.setImeOptions(EditorInfo.IME_ACTION_DONE);
        city.setText(WeatherClient.city(this));
        city.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                getSharedPreferences("weather", MODE_PRIVATE).edit().putString("city", text.toString().trim()).apply();
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        content.addView(city, fullWidth());
        divider(content);
        content.addView(heading("Диалог"), spaced());
        userView = label(14);
        userView.setTextColor(ACCENT);
        userView.setVisibility(View.GONE);
        content.addView(userView, spaced());
        resultView = label(16);
        resultView.setLineSpacing(dp(4), 1);
        resultView.setTextIsSelectable(true);
        resultView.setText("Нет сообщений");
        resultView.setTextColor(MUTED);
        content.addView(resultView, spaced());

        LinearLayout composer = row();
        composer.setPadding(dp(12), dp(4), dp(4), dp(4));
        composer.setBackground(shape(SURFACE, 8));
        promptInput = new EditText(this);
        promptInput.setTextSize(16);
        promptInput.setTextColor(INK);
        promptInput.setHintTextColor(MUTED);
        promptInput.setBackgroundColor(Color.TRANSPARENT);
        promptInput.setHint("Сообщение");
        promptInput.setSingleLine(false);
        promptInput.setMaxLines(4);
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        promptInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                testGenerate();
                return true;
            }
            return false;
        });
        composer.addView(promptInput, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = tool(R.drawable.ic_arrow_up, "Отправить", true);
        sendButton.setOnClickListener(v -> testGenerate());
        composer.addView(sendButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(composer, fullWidth());
        root.requestFocus();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                view.setPadding(dp(20) + bars.left, dp(12) + bars.top,
                        dp(20) + bars.right, dp(12) + bars.bottom);
            } else {
                view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                        dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        return root;
    }

    private void startApi() {
        if (LlmApiService.isActive() || ModelStore.isImporting()) {
            return;
        }
        Intent intent = new Intent(this, LlmApiService.class).setAction(LlmApiService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateStatus();
    }

    private void stopApi() {
        stopService(new Intent(this, LlmApiService.class));
        updateStatus();
    }

    private void updateStatus() {
        boolean running = LlmApiService.isRunning();
        boolean active = LlmApiService.isActive();
        boolean importing = ModelStore.isImporting();
        boolean hasModel = ModelStore.modelFile(this).isFile();
        String state = LlmApiService.modelState();
        String modelStatus = switch (state) {
            case "loading" -> "Загрузка модели...";
            case "generating" -> "Модель отвечает...";
            case "unloading" -> "Выгрузка модели...";
            case "ready" -> "API работает · модель загружена";
            case "error" -> "Ошибка модели · API работает";
            default -> "API работает · модель спит";
        };
        statusView.setText(running ? modelStatus : active ? "Запуск API..." : hasModel ? "Остановлен" : "Нет модели");
        statusView.setTextColor(running ? ACCENT : MUTED);
        updatingSwitch = true;
        serverSwitch.setChecked(active);
        serverSwitch.setEnabled(active || (!importing && hasModel));
        updatingSwitch = false;
        importButton.setEnabled(!active && !importing);
        importButton.setAlpha(!active && !importing ? 1 : 0.4f);
        sendButton.setEnabled(!generating);
        sendButton.setAlpha(!generating ? 1 : 0.35f);
        cpuButton.setEnabled(!active && !importing);
        gpuButton.setEnabled(!active && !importing);
        modelView.setText(hasModel ? ModelStore.modelName(this) : "Модель не выбрана");
        modelDetails.setText(importing ? ModelStore.importStatus()
                : hasModel ? (ModelStore.modelFile(this).length() / (1024 * 1024)) + " МБ · LiteRT-LM"
                : "—");
        if (ModelStore.importStatus().startsWith("Import failed:")) {
            modelDetails.setText(ModelStore.importStatus().replace("Import failed:", "Ошибка импорта:"));
            modelDetails.setTextColor(Color.rgb(255, 145, 145));
        } else {
            modelDetails.setTextColor(MUTED);
        }
        importProgress.setVisibility(importing ? View.VISIBLE : View.GONE);
        String ip = NetworkInfo.firstIpv4Address();
        addressView.setText("http://" + ip + ":" + LlmApiService.PORT);
        String error = LlmApiService.lastError();
        if (error != null && !error.isEmpty()) {
            statusView.setText(running ? "Ошибка модели · API работает" : "Ошибка запуска");
            statusView.setTextColor(Color.rgb(255, 145, 145));
            modelDetails.setText(error);
        }
    }

    private void testGenerate() {
        if (generating) {
            return;
        }
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        boolean weather = WeatherCommand.isWeather(prompt);
        if (!weather && !LlmApiService.isRunning()) {
            postResult("Запустите модель на телефоне");
            return;
        }
        generating = true;
        updateStatus();
        userView.setText(prompt);
        userView.setVisibility(View.VISIBLE);
        resultView.setTextColor(INK);
        resultView.setText(weather ? "Получение погоды..."
                : "sleeping".equals(LlmApiService.modelState()) ? "Загрузка модели..." : "Генерация...");
        promptInput.setText("");
        contentScroll.post(() -> contentScroll.fullScroll(View.FOCUS_DOWN));
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (weather) {
                    postResult(WeatherClient.forecast(this, WeatherCommand.isTomorrow(prompt)));
                    return;
                }
                URL url = new URL("http://127.0.0.1:" + LlmApiService.PORT + "/generate");
                connection = (HttpURLConnection) url.openConnection();
                activeConnection = connection;
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(300000);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                byte[] payload = new JSONObject().put("prompt", prompt).toString()
                        .getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(payload);
                }
                StringBuilder body = new StringBuilder();
                boolean success = connection.getResponseCode() == 200;
                java.io.InputStream input = success ? connection.getInputStream() : connection.getErrorStream();
                if (input == null) {
                    throw new java.io.IOException("HTTP " + connection.getResponseCode());
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
                JSONObject response = new JSONObject(body.toString());
                postResult(response.optString(success ? "text" : "error", body.toString()));
            } catch (Exception e) {
                postResult("Ошибка запроса: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                activeConnection = null;
            }
        });
    }

    private void postResult(String text) {
        handler.post(() -> {
            if (!isDestroyed()) {
                resultView.setText(text);
                generating = false;
                updateStatus();
            }
        });
    }

    private TextView label(int sp) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        view.setTextColor(INK);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setLetterSpacing(0);
        return view;
    }

    private TextView heading(String text) {
        TextView view = label(18);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setText(text);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(43, 48, 52));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(0, dp(24), 0, dp(12));
        parent.addView(line, params);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.rgb(77, 102, 91)),
                shape(color, radius), shape(Color.WHITE, radius));
    }

    private ImageButton tool(int icon, String description, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(primary ? BG : INK));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(ripple(primary ? ACCENT : Color.TRANSPARENT, 8));
        button.setContentDescription(description);
        button.setTooltipText(description);
        return button;
    }

    private void styleSegment(RadioButton button) {
        button.setButtonDrawable((android.graphics.drawable.Drawable) null);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{BG, MUTED}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_checked}, shape(ACCENT, 6));
        background.addState(new int[]{}, shape(Color.TRANSPARENT, 6));
        button.setBackground(background);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(12), 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1f);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
