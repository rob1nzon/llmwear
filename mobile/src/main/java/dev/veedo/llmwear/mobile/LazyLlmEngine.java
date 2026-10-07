package dev.veedo.llmwear.mobile;

import org.json.JSONArray;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Serializes native initialization, inference and release on one non-waking worker. */
final class LazyLlmEngine implements LlmEngine {
    interface Factory { LlmEngine create() throws Exception; }
    private interface Inference { String run(LlmEngine engine) throws Exception; }

    private final String name;
    private final Factory factory;
    private final long idleMillis;
    private final ScheduledThreadPoolExecutor worker;
    private final Object lifecycle = new Object();
    private volatile String state = "sleeping";
    private volatile String error = "";
    private boolean closed;
    private LlmEngine loaded;
    private ScheduledFuture<?> idleRelease;
    private Future<Void> shutdownResult;

    LazyLlmEngine(String name, Factory factory, long idleMillis, ThreadFactory threads) {
        if (idleMillis <= 0) throw new IllegalArgumentException("Idle timeout must be positive");
        this.name = name;
        this.factory = factory;
        this.idleMillis = idleMillis;
        worker = new ScheduledThreadPoolExecutor(1, threads);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override public String modelName() { return name; }
    @Override public String modelState() { return state; }
    @Override public String lastError() { return error; }
    @Override public long idleTimeoutSeconds() { return TimeUnit.MILLISECONDS.toSeconds(idleMillis); }

    @Override public String generate(String prompt) throws Exception {
        return infer(engine -> engine.generate(prompt));
    }

    @Override public String chat(JSONArray messages) throws Exception {
        return infer(engine -> engine.chat(messages));
    }

    private String infer(Inference inference) throws Exception {
        Future<String> result;
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Server is stopping");
            result = worker.submit(() -> {
                cancelIdleRelease();
                try {
                    if (!error.isEmpty() && loaded != null) releaseLoaded();
                    error = "";
                    if (loaded == null) {
                        state = "loading";
                        loaded = Objects.requireNonNull(factory.create(), "Model factory returned null");
                    }
                    state = "generating";
                    String text = inference.run(loaded);
                    state = "ready";
                    synchronized (lifecycle) {
                        if (!closed) idleRelease = worker.schedule(this::releaseIdle, idleMillis, TimeUnit.MILLISECONDS);
                    }
                    return text;
                } catch (Exception | LinkageError | OutOfMemoryError failure) {
                    try { releaseLoaded(); }
                    catch (Exception | LinkageError | OutOfMemoryError cleanup) { failure.addSuppressed(cleanup); }
                    error = message(failure);
                    state = "error";
                    throw new IllegalStateException(error, failure);
                }
            });
        }
        return await(result);
    }

    private void cancelIdleRelease() {
        if (idleRelease != null) idleRelease.cancel(false);
        idleRelease = null;
    }

    private void releaseLoaded() {
        if (loaded == null) return;
        state = "unloading";
        loaded.close();
        loaded = null;
    }

    private void releaseIdle() {
        idleRelease = null;
        try {
            releaseLoaded();
            state = "sleeping";
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            error = message(failure);
            state = "error";
        }
    }

    @Override public void close() {
        Future<Void> release;
        synchronized (lifecycle) {
            if (shutdownResult == null) {
                closed = true;
                shutdownResult = worker.submit((Callable<Void>) () -> {
                    cancelIdleRelease();
                    try { releaseLoaded(); }
                    finally { state = "closed"; }
                    return null;
                });
                worker.shutdown();
            }
            release = shutdownResult;
        }
        try { await(release); }
        catch (Exception failure) { throw new IllegalStateException("Could not close model", failure); }
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    // Do not abandon a native call when its HTTP thread is interrupted.
    private static <T> T await(Future<T> result) throws Exception {
        boolean interrupted = false;
        try {
            while (true) {
                try { return result.get(); }
                catch (InterruptedException ignored) { interrupted = true; }
                catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
