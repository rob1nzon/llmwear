package dev.veedo.llmwear.wear;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Legacy assistant metadata requires a valid recognition service before Android 12. */
@SuppressWarnings("deprecation")
public final class AssistantRecognitionService extends RecognitionService {
    private SpeechRecognizer recognizer;
    private Callback callback;

    @Override protected void onStartListening(Intent intent, Callback listener) {
        if (callback != null) {
            deliver(() -> listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
            return;
        }
        ComponentName provider = null;
        for (ResolveInfo service : getPackageManager().queryIntentServices(new Intent(SERVICE_INTERFACE), 0)) {
            if (service.serviceInfo != null && !getPackageName().equals(service.serviceInfo.packageName)) {
                provider = new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name);
                break;
            }
        }
        if (provider == null) {
            deliver(() -> listener.error(SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        callback = listener;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this, provider);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { deliver(() -> listener.readyForSpeech(params)); }
                @Override public void onBeginningOfSpeech() { deliver(listener::beginningOfSpeech); }
                @Override public void onRmsChanged(float rms) { deliver(() -> listener.rmsChanged(rms)); }
                @Override public void onBufferReceived(byte[] buffer) { deliver(() -> listener.bufferReceived(buffer)); }
                @Override public void onEndOfSpeech() { deliver(listener::endOfSpeech); }
                @Override public void onPartialResults(Bundle results) { deliver(() -> listener.partialResults(results)); }
                @Override public void onEvent(int event, Bundle params) { }
                @Override public void onError(int error) {
                    deliver(() -> listener.error(error));
                    release();
                }
                @Override public void onResults(Bundle results) {
                    deliver(() -> listener.results(results));
                    release();
                }
            });
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            deliver(() -> listener.error(SpeechRecognizer.ERROR_CLIENT));
            release();
        }
    }

    @Override protected void onStopListening(Callback listener) {
        if (recognizer != null) recognizer.stopListening();
    }

    @Override protected void onCancel(Callback listener) { release(); }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        callback = null;
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
    }

    private interface Delivery { void run() throws RemoteException; }

    private static void deliver(Delivery delivery) {
        try { delivery.run(); } catch (RemoteException ignored) { }
    }
}
