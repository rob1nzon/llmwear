package dev.veedo.llmwear.commands;

public final class SearchSources {
    public static final String HEADER = "\n\n\u0418\u0441\u0442\u043e\u0447\u043d\u0438\u043a\u0438:\n";

    private SearchSources() { }

    public static String forSpeech(String answer) {
        if (answer == null) return null;
        int start = answer.lastIndexOf(HEADER);
        if (start < 0) return answer;
        String[] sources = answer.substring(start + HEADER.length()).trim().split("\n");
        if (sources.length == 0 || sources.length > 3) return answer;
        for (String source : sources) {
            if (!source.matches("(?i)[1-3]\\. https?://\\S+")) return answer;
        }
        return answer.substring(0, start).trim();
    }
}
