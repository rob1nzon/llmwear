package dev.veedo.llmwear.commands;

import java.util.Locale;
import java.util.regex.Pattern;

public final class WeatherCommand {
    private static final String WHEN = "(?:сейчас|сегодня|завтра|на завтра|на сегодня)";
    private static final String OUTSIDE = "(?:на улице|за окном)";
    private static final Pattern QUERY = Pattern.compile(
            "(?:(?:покажи|узнай|скажи|расскажи|подскажи) )?"
            + "(?:пожалуйста )?(?:"
            + "(?:какая )?(?:" + WHEN + " )?(?:будет )?погод[ау]"
            + "|прогноз погоды|что с погодой|как там погода"
            + "|(?:какая )?(?:" + WHEN + " )?температура"
            + "|сколько (?:сейчас )?градусов"
            + "|(?:будет ли|идет ли|идёт ли|будет|идет|идёт) (?:дождь|снег)"
            + ")(?: (?:будет )?" + WHEN + ")?"
            + "(?: " + OUTSIDE + ")?(?: " + WHEN + ")?(?: пожалуйста)?");

    private WeatherCommand() { }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[?!.;,]+", " ").replaceAll("\\s+", " ").trim();
    }

    public static boolean isWeather(String text) {
        return QUERY.matcher(normalize(text)).matches();
    }

    public static boolean isTomorrow(String text) {
        return Pattern.compile("(?:^| )завтра(?: |$)").matcher(normalize(text)).find();
    }
}
