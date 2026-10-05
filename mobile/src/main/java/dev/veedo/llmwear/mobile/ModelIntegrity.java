package dev.veedo.llmwear.mobile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class ModelIntegrity {
    private ModelIntegrity() {}

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 0xf];
        }
        return new String(result);
    }

    static void verify(String name, long size, long declaredSize, String sha256) {
        if (size == 0 || (declaredSize >= 0 && size != declaredSize)) {
            throw new IllegalArgumentException("Файл модели пустой или скопирован не полностью");
        }
        // Official artifacts pinned to HF revision 6b78abd; other model names remain supported.
        String canonical = name.toLowerCase(Locale.ROOT).replaceFirst(" \\(\\d+\\)(?=\\.litertlm$)", "");
        String expected = null;
        long expectedSize = -1;
        if (canonical.equals("gemma-4-e2b-it-gpu.litertlm")) {
            expected = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5";
            expectedSize = 2008432640L;
        } else if (canonical.equals("gemma-4-e2b-it.litertlm")) {
            expected = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
            expectedSize = 2588147712L;
        }
        if (expected != null && (size != expectedSize || !expected.equals(sha256))) {
            throw new IllegalArgumentException("Gemma 4 E2B не совпадает с проверенной официальной версией "
                    + "(SHA-256). Скачайте файл заново; прежняя модель сохранена.");
        }
    }
}
