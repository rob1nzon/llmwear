package dev.veedo.llmwear.mobile;

interface LlmEngine extends AutoCloseable {
    String modelName();

    String generate(String prompt) throws Exception;

    String chat(org.json.JSONArray messages) throws Exception;

    @Override
    void close();
}
