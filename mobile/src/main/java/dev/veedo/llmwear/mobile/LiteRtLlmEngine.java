package dev.veedo.llmwear.mobile;

import android.content.Context;
import android.os.PowerManager;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LiteRtLlmEngine implements LlmEngine {
    private final Engine engine;
    private final String name;
    private final PowerManager.WakeLock inferenceLock;

    LiteRtLlmEngine(Context context) {
        this(context, ModelStore.useGpu(context));
    }

    LiteRtLlmEngine(Context context, boolean useGpu) {
        if (!ModelStore.modelFile(context).isFile()) {
            throw new IllegalStateException("Import a .litertlm model first");
        }
        name = ModelStore.modelName(context);
        inferenceLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "llmwear:inference");
        Backend backend = useGpu ? new Backend.GPU() : new Backend.CPU();
        EngineConfig config = new EngineConfig(ModelStore.modelFile(context).getAbsolutePath(),
                backend, null, null, 2048, null, context.getCacheDir().getAbsolutePath());
        engine = new Engine(config);
        inferenceLock.acquire(60_000);
        try {
            engine.initialize();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (engine.isInitialized()) engine.close();
            throw failure;
        } finally {
            if (inferenceLock.isHeld()) {
                inferenceLock.release();
            }
        }
    }

    @Override
    public String modelName() {
        return name;
    }

    @Override
    public String generate(String prompt) {
        return answer(null, Collections.emptyList(), prompt);
    }

    @Override
    public String chat(JSONArray messages) throws Exception {
        List<Message> history = new ArrayList<>();
        Contents system = null;
        for (int i = 0; i < messages.length() - 1; i++) {
            JSONObject message = messages.getJSONObject(i);
            String content = message.getString("content");
            switch (message.getString("role")) {
                case "system":
                    system = Contents.Companion.of(content);
                    break;
                case "assistant":
                    history.add(Message.Companion.model(content));
                    break;
                default:
                    history.add(Message.Companion.user(content));
            }
        }
        return answer(system, history, messages.getJSONObject(messages.length() - 1).getString("content"));
    }

    private String answer(Contents system, List<Message> history, String prompt) {
        ConversationConfig config = new ConversationConfig(system, history, Collections.emptyList(),
                null, false, null, Collections.emptyMap(), null, false, 256);
        // Each API request has its own conversation; model weights remain loaded.
        inferenceLock.acquire(300_000);
        try {
            try (Conversation conversation = engine.createConversation(config)) {
                return conversation.sendMessage(prompt).toString();
            }
        } finally {
            if (inferenceLock.isHeld()) {
                inferenceLock.release();
            }
        }
    }

    @Override
    public void close() {
        if (engine.isInitialized()) {
            engine.close();
        }
    }
}
