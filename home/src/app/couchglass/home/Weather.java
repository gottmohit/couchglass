package app.couchglass.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Current weather for the header pill. Location comes from the TV's internet address
 * (ipwho.is, refreshed daily); weather from Open-Meteo (no key, refreshed every 30 min).
 * The last good reading is kept so the pill survives a network drop.
 */
public class Weather {
    public static class Now {
        public int temp, high, low, code;
        public boolean day = true;
        public String city = "";
        public long sunrise, sunset, fetchedAt;

        public String label() {
            return Weather.label(code);
        }
    }

    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long LOCATION_MS = 24 * 3_600_000L;
    private final SharedPreferences prefs;

    public Weather(Context c) {
        prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The cached reading (may be stale or null); never touches the network. */
    public Now cached() {
        String s = prefs.getString("now", null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            Now n = new Now();
            n.temp = o.getInt("temp");
            n.high = o.getInt("high");
            n.low = o.getInt("low");
            n.code = o.getInt("code");
            n.day = o.getBoolean("day");
            n.city = o.optString("city");
            n.sunrise = o.getLong("sunrise");
            n.sunset = o.getLong("sunset");
            n.fetchedAt = o.getLong("at");
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean stale() {
        Now n = cached();
        return n == null || System.currentTimeMillis() - n.fetchedAt > REFRESH_MS;
    }

    /** Fetches fresh weather. Call off the main thread. Returns null on failure. */
    public Now refresh() {
        try {
            double lat, lon;
            String city;
            if (System.currentTimeMillis() - prefs.getLong("loc_at", 0) > LOCATION_MS || !prefs.contains("lat")) {
                JSONObject ip = new JSONObject(get("https://ipwho.is/"));
                if (!ip.optBoolean("success", false)) throw new Exception("location lookup failed");
                lat = ip.getDouble("latitude");
                lon = ip.getDouble("longitude");
                city = ip.optString("city", "");
                prefs.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                        .putString("city", city).putLong("loc_at", System.currentTimeMillis()).apply();
            } else {
                lat = prefs.getFloat("lat", 0);
                lon = prefs.getFloat("lon", 0);
                city = prefs.getString("city", "");
            }
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                    + "&timezone=auto&forecast_days=1", lat, lon);
            JSONObject w = new JSONObject(get(url));
            JSONObject cur = w.getJSONObject("current");
            JSONObject daily = w.getJSONObject("daily");
            Now n = new Now();
            n.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            n.code = cur.getInt("weather_code");
            n.day = cur.optInt("is_day", 1) == 1;
            n.high = (int) Math.round(first(daily, "temperature_2m_max"));
            n.low = (int) Math.round(first(daily, "temperature_2m_min"));
            n.sunrise = localTime(daily.getJSONArray("sunrise").getString(0));
            n.sunset = localTime(daily.getJSONArray("sunset").getString(0));
            n.city = city;
            n.fetchedAt = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("temp", n.temp).put("high", n.high).put("low", n.low).put("code", n.code).put("day", n.day)
                    .put("city", n.city).put("sunrise", n.sunrise).put("sunset", n.sunset).put("at", n.fetchedAt);
            prefs.edit().putString("now", o.toString()).apply();
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    private static double first(JSONObject daily, String key) throws Exception {
        JSONArray a = daily.getJSONArray(key);
        return a.getDouble(0);
    }

    /** Open-Meteo returns local times like 2026-10-28T06:34 when timezone=auto; the TV shares that zone. */
    private static long localTime(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Couchglass/1.0 (Android TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes in plain words. */
    public static String label(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Haze";
    }

    /** Icon family for a code: 0 sun/moon, 1 partly, 2 cloud, 3 rain, 4 storm, 5 fog/snow. */
    public static int kind(int code) {
        if (code == 0) return 0;
        if (code <= 2) return 1;
        if (code == 3) return 2;
        if (code >= 95) return 4;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return 3;
        return 5;
    }
}
