package dev.veedo.llmwear.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

final class WeatherClient {
    static String city(Context context) {
        return context.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("city", "");
    }

    static String forecast(Context context, boolean tomorrow) throws Exception {
        enableCache(context);
        return forecast(city(context), tomorrow, WeatherClient::get);
    }

    private static synchronized void enableCache(Context context) {
        if (android.net.http.HttpResponseCache.getInstalled() == null) {
            try {
                android.net.http.HttpResponseCache.install(new java.io.File(context.getCacheDir(), "weather_http"), 2 * 1024 * 1024);
            } catch (IOException ignored) { }
        }
    }

    interface JsonSource {
        JSONObject get(String address) throws Exception;
    }

    static String forecast(String city, boolean tomorrow, JsonSource source) throws Exception {
        city = city.trim();
        if (city.isEmpty()) throw new IOException("Укажите город в приложении на телефоне");
        String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8.name()).replace("+", "%20");
        JSONObject search = source.get("https://geocoding-api.open-meteo.com/v1/search?name="
                + encoded + "&count=1&language=ru&format=json");
        JSONArray places = search.optJSONArray("results");
        if (places == null || places.length() == 0) throw new IOException("Город не найден. Проверьте название на телефоне.");
        JSONObject place = places.getJSONObject(0);
        try {
            JSONObject data = source.get("https://api.open-meteo.com/v1/forecast?latitude=" + place.getDouble("latitude")
                + "&longitude=" + place.getDouble("longitude")
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code"
                    + "&wind_speed_unit=ms&timezone=auto&forecast_days=2");
            return openMeteo(place.getString("name"), data, tomorrow);
        } catch (IOException | org.json.JSONException primary) {
            try {
                String coordinates = String.format(Locale.ROOT, "lat=%.4f&lon=%.4f",
                        place.getDouble("latitude"), place.getDouble("longitude"));
                return metNo(place, source.get("https://api.met.no/weatherapi/locationforecast/2.0/compact?"
                        + coordinates), tomorrow, Instant.now());
            } catch (Exception fallback) {
                throw new IOException("Не удалось получить погоду. Проверьте интернет на телефоне или VPN.", fallback);
            }
        }
    }

    private static String openMeteo(String name, JSONObject data, boolean tomorrow) throws Exception {
        if (tomorrow) {
            JSONObject daily = data.getJSONObject("daily");
            return name + ". Завтра: " + description(daily.getJSONArray("weather_code").getInt(1))
                    + ".\nОт " + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(1))
                    + " до " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(1))
                    + " °C.\nВероятность осадков " + daily.getJSONArray("precipitation_probability_max").getInt(1)
                    + "%.\n\nOpen-Meteo";
        }
        JSONObject current = data.getJSONObject("current");
        String time = current.getString("time");
        return name + ". Сейчас " + Math.round(current.getDouble("temperature_2m")) + " °C, "
                + description(current.getInt("weather_code")) + ".\nОщущается как "
                + Math.round(current.getDouble("apparent_temperature")) + " °C.\nВетер "
                + Math.round(current.getDouble("wind_speed_10m")) + " м/с.\n\n"
                + time.substring(time.indexOf('T') + 1) + " · Open-Meteo";
    }

    static String metNo(JSONObject place, JSONObject data, boolean tomorrow, Instant now) throws Exception {
        String name = place.getString("name");
        ZoneId zone = ZoneId.of(place.getString("timezone"));
        JSONArray series = data.getJSONObject("properties").getJSONArray("timeseries");
        if (tomorrow) {
            java.time.LocalDate date = now.atZone(zone).toLocalDate().plusDays(1);
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            JSONObject midday = null;
            int noonDistance = 24;
            int count = 0;
            int firstHour = 24;
            int lastHour = -1;
            for (int i = 0; i < series.length(); i++) {
                JSONObject point = series.getJSONObject(i);
                ZonedDateTime time = Instant.parse(point.getString("time")).atZone(zone);
                if (!date.equals(time.toLocalDate())) continue;
                JSONObject values = point.getJSONObject("data");
                double temperature = values.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
                low = Math.min(low, temperature);
                high = Math.max(high, temperature);
                count++;
                firstHour = Math.min(firstHour, time.getHour());
                lastHour = Math.max(lastHour, time.getHour());
                int distance = Math.abs(time.getHour() - 12);
                if (distance < noonDistance) { midday = values; noonDistance = distance; }
            }
            if (count < 12 || firstHour > 2 || lastHour < 21) throw new IOException("Нет полного прогноза на завтра");
            return name + ". Завтра: " + metDescription(midday) + ".\nОт " + Math.round(low)
                    + " до " + Math.round(high) + " °C.\n\n" + date + " · MET Norway";
        }
        JSONObject closest = null;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < series.length(); i++) {
            JSONObject point = series.getJSONObject(i);
            long diff = Math.abs(Instant.parse(point.getString("time")).getEpochSecond() - now.getEpochSecond());
            if (diff < distance) { closest = point; distance = diff; }
        }
        if (closest == null || distance > 7200) throw new IOException("Нет актуального прогноза");
        JSONObject values = closest.getJSONObject("data");
        JSONObject instant = values.getJSONObject("instant").getJSONObject("details");
        String time = Instant.parse(closest.getString("time")).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
        return name + ". Сейчас " + Math.round(instant.getDouble("air_temperature")) + " °C, "
                + metDescription(values) + ".\nВетер " + Math.round(instant.getDouble("wind_speed"))
                + " м/с.\n\nПрогноз на " + time + " · MET Norway";
    }

    private static String metDescription(JSONObject values) throws Exception {
        JSONObject period = values.optJSONObject("next_1_hours");
        if (period == null) period = values.optJSONObject("next_6_hours");
        if (period == null) return "нет описания";
        String symbol = period.getJSONObject("summary").getString("symbol_code");
        if (symbol.contains("thunder")) return "гроза";
        if (symbol.contains("sleet")) return "мокрый снег";
        if (symbol.contains("snow")) return "снег";
        if (symbol.contains("rain")) return "дождь";
        if (symbol.startsWith("clearsky")) return "ясно";
        if (symbol.startsWith("fair")) return "малооблачно";
        if (symbol.startsWith("partlycloudy")) return "переменная облачность";
        if (symbol.startsWith("cloudy")) return "пасмурно";
        if (symbol.startsWith("fog")) return "туман";
        return "нет описания";
    }

    private static JSONObject get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent", "LLMWear/0.1");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Погода недоступна: HTTP " + connection.getResponseCode());
            try (BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 65536) throw new java.io.IOException("Ответ погоды слишком большой");
                }
                return new JSONObject(body.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    static String description(int code) {
        if (code == 0) return "ясно";
        if (code == 1) return "малооблачно";
        if (code == 2) return "переменная облачность";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 55) return "морось";
        if (code == 56 || code == 57) return "ледяная морось";
        if (code >= 61 && code <= 65) return "дождь";
        if (code == 66 || code == 67) return "ледяной дождь";
        if (code >= 71 && code <= 77) return "снег";
        if (code >= 80 && code <= 82) return "ливень";
        if (code == 85 || code == 86) return "снежные заряды";
        if (code >= 95 && code <= 99) return "гроза";
        return "нет описания";
    }
}
