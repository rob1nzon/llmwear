package dev.veedo.llmwear.wear;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class PhoneLink implements AutoCloseable, MessageClient.OnMessageReceivedListener {
    private final Context context;
    private final MessageClient client;
    private volatile CompletableFuture<JSONObject> pending;
    private volatile String pendingId;
    private volatile String pendingNode;

    PhoneLink(Context context) {
        this.context = context.getApplicationContext();
        this.client = Wearable.getMessageClient(this.context);
    }

    private Node phone() throws Exception {
        for (Node node : Tasks.await(Wearable.getCapabilityClient(context)
                .getCapability("llm_wear_host", CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS).getNodes()) {
            if (node.isNearby()) return node;
        }
        throw new java.io.IOException("Нет прямой связи с телефоном");
    }

    JSONObject status() throws Exception {
        byte[] response = Tasks.await(client.sendRequest(phone().getId(), "/llmwear/status", new byte[0]),
                15, TimeUnit.SECONDS);
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }

    JSONObject weather(boolean tomorrow) throws Exception {
        byte[] response = Tasks.await(client.sendRequest(phone().getId(), "/llmwear/weather",
                new JSONObject().put("tomorrow", tomorrow).toString().getBytes(StandardCharsets.UTF_8)),
                45, TimeUnit.SECONDS);
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }

    JSONObject generate(String prompt) throws Exception {
        String node = phone().getId();
        String id = UUID.randomUUID().toString();
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        pending = response;
        pendingId = id;
        pendingNode = node;
        try {
            Tasks.await(client.addListener(this), 10, TimeUnit.SECONDS);
            byte[] payload = new JSONObject().put("id", id).put("prompt", prompt)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > 65536) throw new java.io.IOException("Сообщение слишком длинное");
            Tasks.await(client.sendMessage(node, "/llmwear/generate", payload), 15, TimeUnit.SECONDS);
            return response.get(300, TimeUnit.SECONDS);
        } finally {
            pendingId = null;
            pendingNode = null;
            pending = null;
            client.removeListener(this);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!"/llmwear/response".equals(event.getPath()) || !event.getSourceNodeId().equals(pendingNode)) return;
        try {
            JSONObject response = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
            CompletableFuture<JSONObject> future = pending;
            if (future != null && response.optString("id").equals(pendingId)) future.complete(response);
        } catch (org.json.JSONException ignored) {
        }
    }

    @Override
    public void close() {
        CompletableFuture<JSONObject> future = pending;
        if (future != null) future.cancel(true);
        client.removeListener(this);
    }
}
