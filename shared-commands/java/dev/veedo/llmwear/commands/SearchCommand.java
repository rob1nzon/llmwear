package dev.veedo.llmwear.commands;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchCommand {
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:(?:\u043d\u0430\u0439\u0434\u0438|\u043f\u043e\u0438\u0449\u0438|\u043f\u043e\u0438\u0441\u043a)"
            + "\\s+\u0432\\s+(?:\u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442\u0435|\u0441\u0435\u0442\u0438)"
            + "|search the web(?: for)?|web search)(?=$|[\\s:])[:\\s]*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private SearchCommand() { }

    /** Null means local input; an empty query is an incomplete search command. */
    public static String query(String text) {
        if (text == null) return null;
        String input = text.trim();
        Matcher match = PREFIX.matcher(input);
        return match.find() ? input.substring(match.end()).trim() : null;
    }
}
