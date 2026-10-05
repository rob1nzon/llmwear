package dev.veedo.llmwear.wear;

import dev.veedo.llmwear.commands.WeatherCommand;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeatherCommandTest {
    @Test public void naturalWeatherQuestions() {
        for (String phrase : new String[]{"погода", "Какая сейчас погода?", "покажи погоду",
                "какая погода на улице", "скажи, пожалуйста, какая погода завтра",
                "прогноз погоды на завтра", "что с погодой", "сколько сейчас градусов на улице",
                "какая температура за окном", "будет ли дождь завтра", "погода на завтра"}) {
            assertTrue(phrase, WeatherCommand.isWeather(phrase));
        }
    }

    @Test public void unrelatedOrUnsupportedQueriesStayWithLlm() {
        for (String phrase : new String[]{"", "расскажи что такое погода", "погода в Париже",
                "прогноз погоды на неделю", "не показывай погоду", "погода вчера"}) {
            assertFalse(phrase, WeatherCommand.isWeather(phrase));
        }
        assertFalse(WeatherCommand.isWeather(null));
    }

    @Test public void tomorrowIsExplicit() {
        assertTrue(WeatherCommand.isTomorrow("Какая погода на завтра?"));
        assertFalse(WeatherCommand.isTomorrow("погода сейчас"));
        assertFalse(WeatherCommand.isTomorrow("погода послезавтра"));
    }
}
