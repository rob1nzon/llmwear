package dev.veedo.llmwear.mobile;

interface LlmEngine extends AutoCloseable {
    String modelName();

    default String modelState() { return "ready"; }

    default String lastError() { return ""; }

    default long idleTimeoutSeconds() { return 0; }

    String generate(String prompt) throws Exception;

    String chat(org.json.JSONArray messages) throws Exception;

    @Override
    void close();
}
