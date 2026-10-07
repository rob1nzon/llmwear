package dev.veedo.llmwear.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class LazyLlmEngineTest {
    private static LazyLlmEngine lazy(LazyLlmEngine.Factory factory, long timeout) {
        return new LazyLlmEngine("test-model", factory, timeout, runnable -> new Thread(runnable, "test-model-worker"));
    }

    private static void waitForState(LazyLlmEngine engine, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!state.equals(engine.modelState()) && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(state, engine.modelState());
    }

    @Test public void metadataAndClosingAnUnusedEngineDoNotLoad() {
        AtomicInteger loads = new AtomicInteger();
        LazyLlmEngine engine = lazy(() -> { loads.incrementAndGet(); return new FakeEngine(); }, 120000);
        assertEquals("test-model", engine.modelName());
        assertEquals("sleeping", engine.modelState());
        assertEquals(120, engine.idleTimeoutSeconds());
        engine.close();
        engine.close();
        assertEquals("closed", engine.modelState());
        assertEquals(0, loads.get());
    }

    @Test public void reusesWarmEngineThenUnloadsAndReloads() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Thread> nativeThread = new AtomicReference<>();
        try (LazyLlmEngine engine = lazy(() -> {
            loads.incrementAndGet();
            nativeThread.compareAndSet(null, Thread.currentThread());
            assertSame(nativeThread.get(), Thread.currentThread());
            return new FakeEngine() {
                @Override public String generate(String prompt) throws Exception {
                    assertSame(nativeThread.get(), Thread.currentThread());
                    return super.generate(prompt);
                }
                @Override public void close() {
                    assertSame(nativeThread.get(), Thread.currentThread());
                    super.close();
                    closes.incrementAndGet();
                }
            };
        }, 500)) {
            assertEquals("answer:first", engine.generate("first"));
            assertEquals("answer:second", engine.generate("second"));
            assertEquals(1, loads.get());
            assertEquals("ready", engine.modelState());
            waitForState(engine, "sleeping");
            assertEquals(1, closes.get());
            assertEquals("answer:third", engine.generate("third"));
            assertEquals(2, loads.get());
        }
        assertEquals(2, closes.get());
    }

    @Test public void chatPassesStructuredHistoryWithoutGeneratingAnotherPrompt() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"));
        try (LazyLlmEngine engine = lazy(() -> nativeEngine, 120000)) {
            assertEquals("chat", engine.chat(messages));
            assertSame(messages, nativeEngine.messages);
        }
    }

    @Test public void neverUnloadsDuringAnActiveAnswer() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        nativeEngine.block = true;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (LazyLlmEngine engine = lazy(() -> nativeEngine, 50)) {
            Future<String> answer = caller.submit(() -> engine.generate("long answer"));
            assertTrue(nativeEngine.entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertEquals("generating", engine.modelState());
            assertFalse(nativeEngine.closed);
            nativeEngine.release.countDown();
            assertEquals("answer:long answer", answer.get(2, TimeUnit.SECONDS));
            waitForState(engine, "sleeping");
            assertTrue(nativeEngine.closed);
        } finally {
            nativeEngine.release.countDown();
            caller.shutdownNow();
        }
    }

    @Test public void initializationFailureCanBeRetried() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        try (LazyLlmEngine engine = lazy(() -> {
            if (loads.incrementAndGet() == 1) throw new IllegalStateException("load failed");
            return new FakeEngine();
        }, 120000)) {
            try { engine.generate("first"); fail("Failed load accepted"); }
            catch (IllegalStateException expected) { assertEquals("load failed", expected.getMessage()); }
            assertEquals("error", engine.modelState());
            assertEquals("load failed", engine.lastError());
            assertEquals("answer:retry", engine.generate("retry"));
            assertEquals("", engine.lastError());
            assertEquals(2, loads.get());
        }
    }

    @Test public void failedInferenceReleasesNativeResourcesBeforeRetry() throws Exception {
        FakeEngine failed = new FakeEngine() {
            @Override public String generate(String prompt) { throw new IllegalStateException("inference failed"); }
        };
        AtomicInteger loads = new AtomicInteger();
        try (LazyLlmEngine engine = lazy(() -> loads.incrementAndGet() == 1 ? failed : new FakeEngine(), 120000)) {
            try { engine.generate("first"); fail("Failed inference accepted"); }
            catch (IllegalStateException expected) { assertEquals("inference failed", expected.getMessage()); }
            assertTrue(failed.closed);
            assertEquals("error", engine.modelState());
            assertEquals("answer:retry", engine.generate("retry"));
        }
    }

    @Test public void closeWaitsForInferenceAndRejectsLaterRequests() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        nativeEngine.block = true;
        LazyLlmEngine engine = lazy(() -> nativeEngine, 50);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> answer = callers.submit(() -> engine.generate("active"));
            assertTrue(nativeEngine.entered.await(2, TimeUnit.SECONDS));
            Future<?> close = callers.submit(engine::close);
            Thread.sleep(100);
            assertFalse(close.isDone());
            assertFalse(nativeEngine.closed);
            nativeEngine.release.countDown();
            assertEquals("answer:active", answer.get(2, TimeUnit.SECONDS));
            close.get(2, TimeUnit.SECONDS);
            assertTrue(nativeEngine.closed);
            assertEquals("closed", engine.modelState());
            try { engine.generate("later"); fail("Closed engine accepted a request"); }
            catch (IllegalStateException expected) { assertEquals("Server is stopping", expected.getMessage()); }
        } finally {
            nativeEngine.release.countDown();
            engine.close();
            callers.shutdownNow();
        }
    }

    @Test public void nativeLinkageErrorsBecomeReadableAndDoNotKillTheWorker() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        try (LazyLlmEngine engine = lazy(() -> {
            if (loads.incrementAndGet() == 1) throw new UnsatisfiedLinkError("missing native runtime");
            return new FakeEngine();
        }, 120000)) {
            try { engine.generate("first"); fail("Linkage error accepted"); }
            catch (IllegalStateException expected) { assertEquals("missing native runtime", expected.getMessage()); }
            assertEquals("answer:retry", engine.generate("retry"));
        }
    }

    @Test public void closeWaitsForAnInProgressLoad() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finishLoad = new CountDownLatch(1);
        FakeEngine nativeEngine = new FakeEngine();
        LazyLlmEngine engine = lazy(() -> {
            loading.countDown();
            if (!finishLoad.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return nativeEngine;
        }, 50);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> answer = callers.submit(() -> engine.generate("first"));
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            assertEquals("loading", engine.modelState());
            Future<?> close = callers.submit(engine::close);
            Thread.sleep(100);
            assertFalse(close.isDone());
            finishLoad.countDown();
            assertEquals("answer:first", answer.get(2, TimeUnit.SECONDS));
            close.get(2, TimeUnit.SECONDS);
            assertTrue(nativeEngine.closed);
            assertEquals("closed", engine.modelState());
        } finally {
            finishLoad.countDown();
            engine.close();
            callers.shutdownNow();
        }
    }

    @Test public void interruptedCallerStillWaitsForNativeCompletion() throws Exception {
        FakeEngine nativeEngine = new FakeEngine();
        nativeEngine.block = true;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LazyLlmEngine engine = lazy(() -> nativeEngine, 120000)) {
            Thread caller = new Thread(() -> {
                try {
                    assertEquals("answer:active", engine.generate("active"));
                    assertTrue(Thread.currentThread().isInterrupted());
                } catch (Throwable e) { failure.set(e); }
                finally { done.countDown(); }
            });
            caller.start();
            assertTrue(nativeEngine.entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertFalse(done.await(100, TimeUnit.MILLISECONDS));
            assertFalse(nativeEngine.closed);
            nativeEngine.release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            caller.join();
            assertNull(failure.get());
        } finally { nativeEngine.release.countDown(); }
    }

    private static class FakeEngine implements LlmEngine {
        volatile boolean closed;
        volatile boolean block;
        volatile JSONArray messages;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        public String modelName() { return "test-model"; }
        public String generate(String prompt) throws Exception {
            assertFalse(closed);
            if (block) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            }
            return "answer:" + prompt;
        }
        public String chat(JSONArray messages) { this.messages = messages; return "chat"; }
        public void close() { assertFalse(closed); closed = true; }
    }
}
