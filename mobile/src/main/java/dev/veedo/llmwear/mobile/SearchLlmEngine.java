package dev.veedo.llmwear.mobile;

import dev.veedo.llmwear.commands.SearchCommand;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.BooleanSupplier;

final class SearchLlmEngine implements LlmEngine {
    interface Search { WebSearchResult run(String query) throws Exception; }
    private final LlmEngine delegate;
    private final Search search;
    private final BooleanSupplier enabled;
    private volatile boolean searching;

    SearchLlmEngine(LlmEngine delegate, Search search, BooleanSupplier enabled) {
        this.delegate = delegate;
        this.search = search;
        this.enabled = enabled;
    }

    @Override public String modelName() { return delegate.modelName(); }
    @Override public String modelState() { return searching ? "searching" : delegate.modelState(); }
    @Override public String lastError() { return delegate.lastError(); }
    @Override public long idleTimeoutSeconds() { return delegate.idleTimeoutSeconds(); }

    @Override public String generate(String prompt) throws Exception {
        String query = SearchCommand.query(prompt);
        if (query == null) return delegate.generate(prompt);
        WebSearchResult results = find(query);
        return results.withSources(delegate.generate(results.prompt(query, prompt)));
    }

    @Override public String chat(JSONArray messages) throws Exception {
        String query = SearchCommand.query(messages.getJSONObject(messages.length() - 1).getString("content"));
        if (query == null) return delegate.chat(messages);
        WebSearchResult results = find(query);
        JSONArray grounded = new JSONArray(messages.toString());
        JSONObject last = grounded.getJSONObject(grounded.length() - 1);
        last.put("content", results.prompt(query, last.getString("content")));
        return results.withSources(delegate.chat(grounded));
    }

    private WebSearchResult find(String query) throws Exception {
        if (!enabled.getAsBoolean()) throw new IllegalStateException("Web search is disabled on the phone");
        if (query.isEmpty() || query.length() > 300) {
            throw new IllegalArgumentException("Search query must contain 1 to 300 characters");
        }
        searching = true;
        try { return search.run(query); }
        finally { searching = false; }
    }

    @Override public void close() { delegate.close(); }
}
