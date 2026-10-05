package dev.veedo.llmwear.mobile;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
import static org.junit.Assert.*;

public final class WeatherClientTest {
    private static JSONObject place() throws Exception {
        return new JSONObject("{\"name\":\"Мытищи\",\"latitude\":55.91099,\"longitude\":37.72964,\"timezone\":\"Europe/Moscow\"}");
    }

    private static JSONObject fallback(Instant now) throws Exception {
        JSONArray series = new JSONArray();
        for (int hour = 0; hour < 49; hour++) {
            JSONObject values = new JSONObject()
                    .put("instant", new JSONObject().put("details", new JSONObject()
                            .put("air_temperature", 8 + hour % 6).put("wind_speed", 2.0)))
                    .put("next_1_hours", new JSONObject().put("summary", new JSONObject().put("symbol_code", "rain")));
            series.put(new JSONObject().put("time", now.plusSeconds(hour * 3600L).toString()).put("data", values));
        }
        return new JSONObject().put("properties", new JSONObject().put("timeseries", series));
    }

    @Test public void primaryFailureFallsBackUsingCorrectCoordinates() throws Exception {
        String text = WeatherClient.forecast("Мытищи", false, address -> {
            if (address.startsWith("https://geocoding-api.open-meteo.com/")) {
                return new JSONObject().put("results", new JSONArray().put(place()));
            }
            if (address.startsWith("https://api.met.no/")) {
                assertTrue(address.endsWith("lat=55.9110&lon=37.7296"));
                return fallback(Instant.now());
            }
            throw new IOException("timeout");
        });
        assertTrue(text.contains("Мытищи. Сейчас 8 °C, дождь"));
        assertTrue(text.contains("Ветер 2 м/с"));
        assertTrue(text.contains("MET Norway"));
    }

    @Test public void tomorrowUsesLocalDateRatherThanUtcOrArrayIndex() throws Exception {
        Instant now = Instant.parse("2026-10-05T22:00:00Z");
        String text = WeatherClient.metNo(place(), fallback(now), true, now);
        assertTrue(text.contains("2026-10-07"));
        assertTrue(text.contains("От 8 до 13 °C"));
        assertFalse(text.contains("Сейчас"));
    }

    @Test public void emptyAndUnknownCitiesAreNotReplacedWithIpLocation() throws Exception {
        try {
            WeatherClient.forecast(" ", false, address -> { throw new AssertionError("No network expected"); });
            fail("Missing city accepted");
        } catch (IOException e) { assertTrue(e.getMessage().contains("Укажите город")); }
        try {
            WeatherClient.forecast("unknown", false, address -> {
                assertTrue(address.startsWith("https://geocoding-api.open-meteo.com/"));
                return new JSONObject();
            });
            fail("Unknown city accepted");
        } catch (IOException e) { assertTrue(e.getMessage().contains("Город не найден")); }
    }

    @Test public void bothProvidersFailWithReadableError() throws Exception {
        try {
            WeatherClient.forecast("Мытищи", false, address -> {
                if (address.startsWith("https://geocoding-api.open-meteo.com/")) {
                    return new JSONObject().put("results", new JSONArray().put(place()));
                }
                throw new IOException("timeout");
            });
            fail("Network failure accepted");
        } catch (IOException e) { assertTrue(e.getMessage().contains("интернет на телефоне")); }
    }

    @Test public void staleOrIncompleteDataIsRejected() throws Exception {
        Instant now = Instant.parse("2026-10-05T18:00:00Z");
        try {
            WeatherClient.metNo(place(), fallback(now.minusSeconds(86400 * 4)), false, now);
            fail("Stale forecast accepted");
        } catch (IOException expected) { }
        try {
            WeatherClient.metNo(place(), fallback(now.plusSeconds(86400 * 3)), true, now);
            fail("Wrong day accepted");
        } catch (IOException expected) { }
    }

    @Test public void weatherCodes() {
        assertEquals("ясно", WeatherClient.description(0));
        assertEquals("пасмурно", WeatherClient.description(3));
        assertEquals("туман", WeatherClient.description(45));
        assertEquals("ледяной дождь", WeatherClient.description(67));
        assertEquals("снег", WeatherClient.description(73));
        assertEquals("гроза", WeatherClient.description(95));
        assertEquals("нет описания", WeatherClient.description(-1));
    }
}
