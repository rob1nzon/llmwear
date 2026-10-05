package dev.veedo.llmwear.mobile;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelIntegrityTest {
    private static final String GPU_SHA = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5";

    @Test public void sha256Encoding() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ModelIntegrity.hex(ModelIntegrity.sha256().digest("abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void acceptsOfficialArtifacts() {
        ModelIntegrity.verify("gemma-4-E2B-it-gpu.litertlm", 2008432640L, -1, GPU_SHA);
        ModelIntegrity.verify("gemma-4-E2B-it-gpu (1).litertlm", 2008432640L, 2008432640L, GPU_SHA);
        ModelIntegrity.verify("gemma-4-E2B-it.litertlm", 2588147712L, -1,
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c");
    }

    @Test public void rejectsObservedBadDownloadAndTruncation() {
        assertThrows(IllegalArgumentException.class, () -> ModelIntegrity.verify(
                "gemma-4-E2B-it-gpu.litertlm", 2008432640L, -1,
                "3e0008ba1f6af7fe0aa376acae599e6eb9a4936c40d1bfb83fbdfdac320e49d2"));
        assertThrows(IllegalArgumentException.class, () -> ModelIntegrity.verify(
                "gemma-4-E2B-it-gpu.litertlm", 100, -1, GPU_SHA));
        assertThrows(IllegalArgumentException.class, () -> ModelIntegrity.verify(
                "custom.litertlm", 10, 20, "arbitrary"));
        assertThrows(IllegalArgumentException.class, () -> ModelIntegrity.verify(
                "custom.litertlm", 0, -1, "arbitrary"));
    }

    @Test public void doesNotPinCustomModels() {
        ModelIntegrity.verify("custom.litertlm", 10, 10, "arbitrary");
        ModelIntegrity.verify("gemma3-1b-it-int4.litertlm", 10, -1, "arbitrary");
    }
}
