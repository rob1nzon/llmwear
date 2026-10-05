package dev.veedo.llmwear.wear;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimerCommand {
    private static final Pattern COMMAND = Pattern.compile(
            "^(?:(?:поставь|установи|запусти|включи)\\s+)?таймер(?:\\s+на)?(?:\\s+(.*))?[.!?]?$", Pattern.UNICODE_CASE);
    private static final Pattern PART = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?|[а-яё]+(?:\\s+[а-яё]+)?)\\s*(час(?:а|ов)?|минут(?:а|ы|у)?|секунд(?:а|ы|у)?)(?=\\s|$)");
    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("один", 1), Map.entry("одна", 1), Map.entry("одну", 1), Map.entry("два", 2), Map.entry("две", 2),
            Map.entry("три", 3), Map.entry("четыре", 4), Map.entry("пять", 5), Map.entry("шесть", 6),
            Map.entry("семь", 7), Map.entry("восемь", 8), Map.entry("девять", 9), Map.entry("десять", 10),
            Map.entry("одиннадцать", 11), Map.entry("двенадцать", 12), Map.entry("тринадцать", 13),
            Map.entry("четырнадцать", 14), Map.entry("пятнадцать", 15), Map.entry("шестнадцать", 16),
            Map.entry("семнадцать", 17), Map.entry("восемнадцать", 18), Map.entry("девятнадцать", 19),
            Map.entry("двадцать", 20), Map.entry("тридцать", 30), Map.entry("сорок", 40), Map.entry("пятьдесят", 50),
            Map.entry("шестьдесят", 60), Map.entry("семьдесят", 70), Map.entry("восемьдесят", 80), Map.entry("девяносто", 90));

    static boolean isTimer(String prompt) {
        return COMMAND.matcher(normalize(prompt)).matches();
    }

    static int seconds(String prompt) {
        Matcher command = COMMAND.matcher(normalize(prompt));
        if (!command.matches() || command.group(1) == null) return -1;
        String duration = command.group(1).replaceAll("[.!?]+$", "")
                .replace("полчаса", "30 минут").replace("полторы", "1.5").replace("полтора", "1.5")
                .replaceAll("\\s+и\\s+", " ").trim();
        if (duration.matches("час|минуту|секунду")) duration = "1 " + duration;
        Matcher part = PART.matcher(duration);
        double total = 0;
        int end = 0;
        while (part.find()) {
            if (!duration.substring(end, part.start()).trim().isEmpty()) return -1;
            double number = number(part.group(1));
            if (number <= 0) return -1;
            String unit = part.group(2);
            total += number * (unit.startsWith("час") ? 3600 : unit.startsWith("мин") ? 60 : 1);
            end = part.end();
        }
        if (!duration.substring(end).trim().isEmpty() || total < 1 || total > 86400 || total != Math.floor(total)) return -1;
        return (int) total;
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException ignored) {
            String[] words = text.split("\\s+");
            Integer first = NUMBERS.get(words[0]);
            if (first == null) return -1;
            if (words.length == 1) return first;
            Integer second = NUMBERS.get(words[1]);
            return words.length == 2 && first >= 20 && first % 10 == 0 && second != null && second < 10 ? first + second : -1;
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
