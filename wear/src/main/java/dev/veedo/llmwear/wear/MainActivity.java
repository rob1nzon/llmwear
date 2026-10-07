package dev.veedo.llmwear.wear;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import org.json.JSONObject;
import dev.veedo.llmwear.commands.WeatherCommand;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(240, 243, 241);
    private static final int MUTED = Color.rgb(150, 160, 165);
    private static final int ACCENT = Color.rgb(145, 230, 189);
    private static final String PREFS = "llm_wear";
    private static final String KEY_HOST = "host";
    private static final String KEY_SPEECH_PROVIDER = "speech_provider";
    private static final int REQUEST_SPEECH = 101;
    private static final int REQUEST_AUDIO_PERMISSION = 102;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText hostInput;
    private EditText promptInput;
    private TextView responseView;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean ttsInitialized;
    private boolean triedGoogleTts;
    private boolean resumed;
    private boolean busy;
    private String lastResponse = "";
    private String pendingSpeech = "";
    private TextView speechStatus;
    private ImageButton voiceButton;
    private ImageButton sendButton;
    private ImageButton speakButton;
    private boolean autoSpeakEnabled;
    private boolean usePhone;
    private PhoneLink phoneLink;
    private LinearLayout home;
    private LinearLayout answer;
    private TextView connectionStatus;
    private volatile HttpURLConnection activeConnection;
    private boolean pendingAssistantInput;
    private String speechProvider;

    private static boolean isAssistantIntent(Intent intent) {
        return intent != null && (Intent.ACTION_ASSIST.equals(intent.getAction())
                || Intent.ACTION_VOICE_COMMAND.equals(intent.getAction())
                || "android.intent.action.VOICE_ASSIST".equals(intent.getAction()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        phoneLink = new PhoneLink(this);
        setContentView(buildContentView());
        pendingAssistantInput = savedInstanceState == null && isAssistantIntent(getIntent());
        if (savedInstanceState != null) {
            promptInput.setText(savedInstanceState.getString("prompt", ""));
            lastResponse = savedInstanceState.getString("answer", "");
            responseView.setText(lastResponse);
            speakButton.setEnabled(!lastResponse.isEmpty());
            if (!lastResponse.isEmpty()) showAnswer();
        }
        textToSpeech = new TextToSpeech(this, status -> {
            handler.post(() -> initializeSpeech(status));
        });
    }

    private void initializeSpeech(int status) {
        if (isDestroyed()) {
            return;
        }
        ttsInitialized = true;
        if (status != TextToSpeech.SUCCESS) {
            speechStatus.setText("Озвучка недоступна");
            return;
        }
        int language = textToSpeech.setLanguage(Locale.getDefault());
        ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
        if (!ttsReady && !triedGoogleTts) {
            for (TextToSpeech.EngineInfo engine : textToSpeech.getEngines()) {
                if ("com.google.android.tts".equals(engine.name)) {
                    triedGoogleTts = true;
                    ttsInitialized = false;
                    textToSpeech.shutdown();
                    textToSpeech = new TextToSpeech(this, result -> handler.post(() -> initializeSpeech(result)), engine.name);
                    return;
                }
            }
        }
        speechStatus.setText(ttsReady ? "" : "Нет голоса для этого языка");
        textToSpeech.setSpeechRate(0.95f);
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                handler.post(() -> {
                    if (!isDestroyed()) {
                        speakButton.setImageResource(R.drawable.ic_stop);
                        speakButton.setContentDescription("Остановить озвучку");
                        speakButton.setTooltipText("Остановить озвучку");
                    }
                });
            }

            @Override
            public void onDone(String id) {
                if (id.endsWith("-last")) {
                    handler.post(() -> resetSpeakButton());
                }
            }

            @Override
            public void onError(String id) {
                handler.post(() -> {
                    if (!isDestroyed()) {
                        speechStatus.setText("Ошибка озвучки");
                        resetSpeakButton();
                    }
                });
            }
        });
        if (ttsReady && !pendingSpeech.isEmpty() && resumed) {
            String pending = pendingSpeech;
            pendingSpeech = "";
            speak(pending);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        checkConnection();
        launchAssistantInput();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingAssistantInput = isAssistantIntent(intent);
        if (resumed) launchAssistantInput();
    }

    private void launchAssistantInput() {
        if (!pendingAssistantInput) return;
        pendingAssistantInput = false;
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        handler.post(() -> {
            if (!resumed || isDestroyed()) return;
            if (busy) {
                Toast.makeText(this, "Дождитесь ответа", Toast.LENGTH_SHORT).show();
            } else {
                showHome();
                startVoiceInput();
            }
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopSpeech();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("prompt", promptInput.getText().toString());
        outState.putString("answer", lastResponse);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, hostInput.getText().toString()).apply();
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        phoneLink.close();
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK || data == null) {
            return;
        }
        ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty()) {
            post("Не удалось распознать речь", false);
            return;
        }
        promptInput.setText(matches.get(0));
        promptInput.setSelection(promptInput.getText().length());
        sendPrompt();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput();
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            Toast.makeText(this, "Нет доступа к микрофону", Toast.LENGTH_SHORT).show();
        }
    }

    private View buildContentView() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        usePhone = prefs.getBoolean("use_phone", true);
        autoSpeakEnabled = prefs.getBoolean("auto_speak", true);
        speechProvider = prefs.getString(KEY_SPEECH_PROVIDER, SpeechInputProviders.SAMSUNG);
        hostInput = new EditText(this);
        hostInput.setText(prefs.getString(KEY_HOST, ""));
        promptInput = new EditText(this);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        home = page();
        answer = page();
        answer.setVisibility(View.GONE);
        frame.addView(home, new android.widget.FrameLayout.LayoutParams(-1, -1));
        frame.addView(answer, new android.widget.FrameLayout.LayoutParams(-1, -1));

        TextView title = label("LLM Wear", 18, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        home.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));
        connectionStatus = label("Подключение...", 11, MUTED);
        connectionStatus.setMaxLines(1);
        connectionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        home.addView(connectionStatus, new LinearLayout.LayoutParams(-1, dp(18)));
        home.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        voiceButton = tool(R.drawable.ic_mic, "Говорить", true);
        voiceButton.setPadding(dp(18), dp(18), dp(18), dp(18));
        voiceButton.setOnClickListener(v -> startVoiceInput());
        home.addView(voiceButton, new LinearLayout.LayoutParams(dp(68), dp(68)));
        home.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        ImageButton keyboard = tool(R.drawable.ic_keyboard, "Ввести текст", false);
        keyboard.setOnClickListener(v -> showTextInput());
        tools.addView(keyboard, new LinearLayout.LayoutParams(dp(40), dp(44)));
        ImageButton timer = tool(R.drawable.ic_timer, "Таймер", false);
        timer.setOnClickListener(v -> showTimerPicker());
        tools.addView(timer, new LinearLayout.LayoutParams(dp(40), dp(44)));
        ImageButton weather = tool(R.drawable.ic_cloud, "Погода", false);
        weather.setOnClickListener(v -> {
            promptInput.setText("погода");
            sendPrompt();
        });
        tools.addView(weather, new LinearLayout.LayoutParams(dp(40), dp(44)));
        ImageButton settings = tool(R.drawable.ic_settings, "Настройки", false);
        settings.setOnClickListener(v -> showSettings());
        tools.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(44)));
        home.addView(tools, fullWidth());

        answer.addView(label("Ответ", 14, MUTED), new LinearLayout.LayoutParams(-1, dp(24)));
        responseView = label("", 16, INK);
        responseView.setGravity(Gravity.START);
        responseView.setLineSpacing(dp(3), 1);
        responseView.setTextIsSelectable(true);
        ScrollView responseScroll = new ScrollView(this);
        responseScroll.addView(responseView);
        answer.addView(responseScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        speechStatus = label("", 11, MUTED);
        answer.addView(speechStatus, fullWidth());
        LinearLayout answerTools = new LinearLayout(this);
        answerTools.setGravity(Gravity.CENTER);
        ImageButton back = tool(R.drawable.ic_back, "Назад", false);
        back.setOnClickListener(v -> showHome());
        answerTools.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));
        speakButton = tool(R.drawable.ic_volume, "Озвучить ответ", false);
        speakButton.setEnabled(false);
        speakButton.setOnClickListener(v -> {
            if (textToSpeech != null && textToSpeech.isSpeaking()) stopSpeech(); else speak(lastResponse);
        });
        answerTools.addView(speakButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        answer.addView(answerTools, fullWidth());
        sendButton = tool(R.drawable.ic_send, "Отправить", false);
        return frame;
    }

    private LinearLayout page() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(30), dp(28), dp(36));
        return root;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLetterSpacing(0);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void showHome() {
        stopSpeech();
        home.setVisibility(View.VISIBLE);
        answer.setVisibility(View.GONE);
        checkConnection();
    }

    private void showAnswer() {
        home.setVisibility(View.GONE);
        answer.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (answer.getVisibility() == View.VISIBLE) showHome(); else super.onBackPressed();
    }

    private void showTextInput() {
        LinearLayout root = page();
        root.addView(label("Сообщение", 16, INK), new LinearLayout.LayoutParams(-1, dp(24)));
        EditText input = new EditText(this);
        input.setTextColor(INK);
        input.setTextSize(15);
        input.setHint("Сообщение");
        input.setText(promptInput.getText());
        input.setGravity(Gravity.TOP);
        input.setMaxLines(3);
        root.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog dialog = fullDialog(root);
        root.addView(dialogTools(dialog, R.drawable.ic_send, "Отправить", () -> {
                    promptInput.setText(input.getText());
                    sendPrompt();
                }), fullWidth());
    }

    private void showSettings() {
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton phone = new RadioButton(this);
        phone.setId(View.generateViewId());
        phone.setText("Телефон");
        phone.setTextSize(12);
        styleMode(phone);
        RadioButton http = new RadioButton(this);
        http.setId(View.generateViewId());
        http.setText("HTTP");
        http.setTextSize(12);
        styleMode(http);
        mode.addView(phone, new LinearLayout.LayoutParams(0, dp(36), 1));
        mode.addView(http, new LinearLayout.LayoutParams(0, dp(36), 1));
        mode.check(usePhone ? phone.getId() : http.getId());
        settings.addView(mode, fullWidth());
        EditText address = new EditText(this);
        address.setTextSize(13);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("http://IP:8765");
        address.setText(hostInput.getText());
        address.setVisibility(usePhone ? View.GONE : View.VISIBLE);
        mode.setOnCheckedChangeListener((group, id) -> address.setVisibility(id == phone.getId() ? View.GONE : View.VISIBLE));
        settings.addView(address, fullWidth());
        settings.addView(label("Голосовой ввод", 12, MUTED), fullWidth());
        List<String> installedSpeech = installedSpeechProviders();
        List<String> speechProviders = SpeechInputProviders.available(installedSpeech);
        List<String> speechLabels = new ArrayList<>();
        for (String provider : speechProviders) {
            speechLabels.add(SpeechInputProviders.SAMSUNG.equals(provider) ? "Samsung"
                    : SpeechInputProviders.GOOGLE.equals(provider) ? "Google" : "Системный");
        }
        Spinner recognition = new Spinner(this);
        recognition.setContentDescription("Голосовой ввод");
        ArrayAdapter<String> providersAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, speechLabels);
        providersAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        recognition.setAdapter(providersAdapter);
        recognition.setSelection(speechProviders.indexOf(
                SpeechInputProviders.choose(speechProvider, installedSpeech)));
        settings.addView(recognition, new LinearLayout.LayoutParams(-1, dp(44)));
        CheckBox speech = new CheckBox(this);
        speech.setText("Озвучка");
        speech.setTextSize(13);
        speech.setChecked(autoSpeakEnabled);
        settings.addView(speech, fullWidth());
        LinearLayout assistant = new LinearLayout(this);
        assistant.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton assistantButton = tool(R.drawable.ic_mic, "Помощник по умолчанию", false);
        assistantButton.setOnClickListener(v -> chooseAssistant());
        assistant.addView(assistantButton, new LinearLayout.LayoutParams(dp(36), dp(44)));
        TextView assistantLabel = label("Помощник по умолчанию", 12, INK);
        assistantLabel.setGravity(Gravity.START);
        assistant.addView(assistantLabel, new LinearLayout.LayoutParams(0, -2, 1));
        assistant.setOnClickListener(v -> chooseAssistant());
        settings.addView(assistant, fullWidth());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(settings);
        LinearLayout root = page();
        root.addView(label("Настройки", 16, INK), new LinearLayout.LayoutParams(-1, dp(24)));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog dialog = fullDialog(root);
        root.addView(dialogTools(dialog, R.drawable.ic_check, "Сохранить", () -> {
                    usePhone = phone.isChecked();
                    autoSpeakEnabled = speech.isChecked();
                    speechProvider = speechProviders.get(recognition.getSelectedItemPosition());
                    hostInput.setText(address.getText());
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("use_phone", usePhone)
                            .putBoolean("auto_speak", autoSpeakEnabled).putString(KEY_HOST, address.getText().toString())
                            .putString(KEY_SPEECH_PROVIDER, speechProvider).apply();
                    if (!autoSpeakEnabled) stopSpeech();
                    checkConnection();
                }), fullWidth());
    }

    private void checkConnection() {
        if (busy || !usePhone) {
            if (!busy) connectionStatus.setText("HTTP / Wi-Fi");
            return;
        }
        executor.execute(() -> {
            String state;
            try {
                JSONObject status = phoneLink.status();
                state = status.optBoolean("running") ? "Телефон подключён"
                        : status.optBoolean("model_available") ? "Модель остановлена" : "Телефон подключён · без LLM";
            } catch (Exception e) {
                state = "Телефон не найден";
            }
            String message = state;
            handler.post(() -> {
                if (!isDestroyed() && !busy && usePhone) connectionStatus.setText(message);
            });
        });
    }

    private void chooseAssistant() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                        Toast.makeText(this, "LLM Wear уже выбран", Toast.LENGTH_SHORT).show();
                    } else {
                        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 103);
                    }
                    return;
                }
            }
            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            } catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "Выбор помощника недоступен в этой прошивке", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showTimerPicker() {
        LinearLayout root = page();
        root.addView(label("Таймер · минуты", 16, INK), new LinearLayout.LayoutParams(-1, dp(24)));
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        NumberPicker minutes = new NumberPicker(this);
        minutes.setMinValue(1);
        minutes.setMaxValue(180);
        minutes.setValue(5);
        minutes.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(minutes, new LinearLayout.LayoutParams(dp(100), dp(96)));
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        Dialog dialog = fullDialog(root);
        root.addView(dialogTools(dialog, R.drawable.ic_check, "Запустить таймер",
                () -> setTimer(minutes.getValue() * 60)), fullWidth());
    }

    private Dialog fullDialog(View content) {
        Dialog dialog = new Dialog(this, R.style.AppTheme);
        dialog.setContentView(content);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        return dialog;
    }

    private void styleMode(RadioButton button) {
        button.setButtonDrawable((android.graphics.drawable.Drawable) null);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.BLACK, INK}));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(ACCENT);
        selected.setCornerRadius(dp(6));
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.rgb(25, 29, 32));
        idle.setCornerRadius(dp(6));
        states.addState(new int[]{android.R.attr.state_checked}, selected);
        states.addState(new int[]{}, idle);
        button.setBackground(states);
    }

    private LinearLayout dialogTools(Dialog dialog, int icon, String label, Runnable action) {
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        ImageButton back = tool(R.drawable.ic_back, "Отмена", false);
        back.setOnClickListener(v -> dialog.dismiss());
        tools.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));
        ImageButton confirm = tool(icon, label, false);
        confirm.setImageTintList(ColorStateList.valueOf(ACCENT));
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        tools.addView(confirm, new LinearLayout.LayoutParams(dp(48), dp(44)));
        return tools;
    }

    private void setTimer(int seconds) {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "LLM Wear").putExtra(AlarmClock.EXTRA_SKIP_UI, false));
        } catch (ActivityNotFoundException e) {
            post("Системный таймер недоступен", false);
        }
    }

    private List<String> installedSpeechProviders() {
        List<String> installed = new ArrayList<>();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        for (android.content.pm.ResolveInfo provider : getPackageManager().queryIntentActivities(intent, 0)) {
            if (provider.activityInfo != null) installed.add(provider.activityInfo.packageName);
        }
        return installed;
    }

    private void startVoiceInput() {
        stopSpeech();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "LLM Wear");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        String provider = SpeechInputProviders.choose(speechProvider, installedSpeechProviders());
        if (!provider.isEmpty()) intent.setPackage(provider);

        try {
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (ActivityNotFoundException e) {
            post("Голосовой ввод недоступен", false);
        }
    }

    private void sendPrompt() {
        if (busy) {
            return;
        }
        String host = normalizeHost(hostInput.getText().toString());
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            return;
        }
        if (TimerCommand.isTimer(prompt)) {
            int seconds = TimerCommand.seconds(prompt);
            if (seconds > 0) setTimer(seconds); else post("Не удалось определить длительность таймера", false);
            return;
        }
        stopSpeech();
        setBusy(true);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, host).apply();
        responseView.setText("Ожидание ответа...");
        showAnswer();
        boolean viaPhone = usePhone;

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                boolean weather = WeatherCommand.isWeather(prompt);
                boolean tomorrow = WeatherCommand.isTomorrow(prompt);
                if (weather && viaPhone) {
                    JSONObject response = phoneLink.weather(tomorrow);
                    boolean success = !response.has("error");
                    post(response.optString(success ? "text" : "error"), success);
                    return;
                }
                if (viaPhone && !weather) {
                    JSONObject response = phoneLink.generate(prompt);
                    boolean success = !response.has("error");
                    post(response.optString(success ? "text" : "error"), success);
                    return;
                }
                URL url = new URL(host + (weather ? "/weather?tomorrow=" + tomorrow : "/generate"));
                connection = (HttpURLConnection) url.openConnection();
                activeConnection = connection;
                connection.setRequestMethod(weather ? "GET" : "POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(weather ? 45000 : 300000);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (!weather) {
                    connection.setDoOutput(true);
                    byte[] payload = new JSONObject().put("prompt", prompt).toString()
                            .getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream outputStream = connection.getOutputStream()) {
                        outputStream.write(payload);
                    }
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
                post(response.optString(success ? "text" : "error", body.toString()), success);
            } catch (Exception e) {
                post("Ошибка связи: " + (e.getMessage() == null ? "нет ответа" : e.getMessage()), false);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                activeConnection = null;
            }
        });
    }

    private String normalizeHost(String input) {
        String value = input == null ? "" : input.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        return value;
    }

    private void post(String text, boolean shouldSpeak) {
        handler.post(() -> {
            if (isDestroyed()) {
                return;
            }
            setBusy(false);
            responseView.setText(text);
            showAnswer();
            speakButton.setEnabled(shouldSpeak && !text.isEmpty());
            if (shouldSpeak) {
                lastResponse = text;
                speakButton.setEnabled(!text.isEmpty());
                if (autoSpeakEnabled && resumed) {
                    speak(text);
                }
            }
        });
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (!ttsInitialized) {
            pendingSpeech = text;
            return;
        }
        if (!ttsReady || textToSpeech == null) {
            Toast.makeText(this, "Озвучка недоступна", Toast.LENGTH_SHORT).show();
            return;
        }
        speechStatus.setText("");
        int max = TextToSpeech.getMaxSpeechInputLength();
        for (int offset = 0, part = 0; offset < text.length(); part++) {
            int end = Math.min(offset + max, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            int queue = offset == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            String id = "llm-" + part + (end == text.length() ? "-last" : "");
            if (textToSpeech.speak(text.substring(offset, end), queue, null, id) == TextToSpeech.ERROR) {
                speechStatus.setText("Ошибка озвучки");
                break;
            }
            offset = end;
        }
    }

    private void stopSpeech() {
        pendingSpeech = "";
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        resetSpeakButton();
    }

    private void resetSpeakButton() {
        if (!isDestroyed() && speakButton != null) {
            speakButton.setImageResource(R.drawable.ic_volume);
            speakButton.setContentDescription("Озвучить ответ");
            speakButton.setTooltipText("Озвучить ответ");
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        voiceButton.setEnabled(!value);
        voiceButton.setAlpha(value ? 0.4f : 1);
    }

    private ImageButton tool(int icon, String label, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setImageTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? Color.BLACK : INK, Color.rgb(75, 82, 87)}));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? ACCENT : Color.TRANSPARENT);
        background.setCornerRadius(dp(primary ? 34 : 22));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(primary ? 34 : 22));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(74, 104, 89)), background, mask));
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
