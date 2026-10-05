package dev.veedo.llmwear.wear;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public final class AssistantSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    private static final class Session extends VoiceInteractionSession {
        private final Context context;

        Session(Context context) {
            super(context);
            this.context = context;
        }

        @Override public void onPrepareShow(Bundle args, int flags) {
            super.onPrepareShow(args, flags);
            setUiEnabled(false);
        }

        @Override public void onShow(Bundle args, int flags) {
            super.onShow(args, flags);
            startAssistantActivity(new Intent(context, MainActivity.class)
                    .setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }

        @Override public void onTaskFinished(Intent intent, int taskId) {
            super.onTaskFinished(intent, taskId);
            finish();
        }
    }
}
