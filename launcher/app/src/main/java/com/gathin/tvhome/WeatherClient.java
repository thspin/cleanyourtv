package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The weather next to the clock, from Open-Meteo's free forecast API (no key, CC BY 4.0). The fetch runs
 * on one background thread like {@link WallpaperPlayer}'s decoder; the answer is always delivered on the
 * main thread. The last good reading is kept in SharedPreferences ({@code weather}) so Home still shows
 * one without a network: the panel only asks again every {@link #REFRESH_MS}.
 *
 * This is the launcher's only network access; everything else reads local files (see {@link Config}).
 */
final class WeatherClient {
    private static final String TAG = "TvHome.Weather";
    static final String ATTRIBUTION = "Open-Meteo \u00B7 CC BY 4.0";
    /** How long a reading is fresh; after this Home asks again when it comes back to the front. */
    static final long REFRESH_MS = 30 * 60 * 1000L;

    static final String PREFS_NAME = "weather";
    private static final String TEMP_KEY = "temp";
    private static final String CODE_KEY = "code";
    private static final String FETCHED_KEY = "fetched_at";

    /** La Rioja, the only place this launcher asks about. */
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=-29.4131&longitude=-66.8558"
            + "&current=temperature_2m,weather_code"
            + "&timezone=America%2FArgentina%2FLa_Rioja";
    private static final int TIMEOUT_MS = 5000;

    /** The result, on the main thread: a reading, or {@link Weather#empty()} when a fetch may not have one. */
    interface Callback {
        void onWeather(Weather weather);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "weather");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Bumped per fetch, so a cancelled or superseded answer is dropped instead of drawn. */
    private static final AtomicLong GENERATION = new AtomicLong();

    private WeatherClient() {
    }

    /**
     * Fetches the current weather in the background and calls {@code callback} on the main thread with a
     * reading, or {@link Weather#empty()} when the network or the answer fails.
     * {@code context} is used to read and write the cache; it is only touched while the main thread runs
     * the callback and the Preferences call below, which are safe from either thread.
     */
    static void fetch(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        long generation = GENERATION.incrementAndGet();
        EXECUTOR.execute(() -> {
            Weather weather = null;
            String error = null;
            int code = -1;
            try {
                JSONObject json = download();
                JSONObject current = json.getJSONObject("current");
                double temp = current.getDouble("temperature_2m");
                code = current.getInt("weather_code");
                weather = Weather.fromCode(code, temp, Weather.PLACE, System.currentTimeMillis());
                if (!weather.valid) throw new IOException("no temperature");
            } catch (IOException | JSONException e) {
                error = e.toString();
            } catch (RuntimeException e) {
                error = e.toString();
            }
            final Weather result = weather;
            final String failure = error;
            final int weatherCode = code;
            MAIN.post(() -> {
                if (GENERATION.get() != generation) return;   // cancelled while it was in flight
                if (result == null) {
                    Log.w(TAG, "no weather" + (failure == null ? "" : ": " + failure));
                    callback.onWeather(Weather.empty());
                    return;
                }
                save(app, result, weatherCode);
                callback.onWeather(result);
            });
        });
    }

    /** Drops the answer of a fetch still in flight, e.g. in {@code onPause}; its network call finishes unseen. */
    static void cancel() {
        GENERATION.incrementAndGet();
    }

    /** The last good reading, or {@link Weather#empty()} when none was saved yet. */
    static Weather cached(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!preferences.contains(FETCHED_KEY) || !preferences.contains(TEMP_KEY)) return Weather.empty();
        long fetched = preferences.getLong(FETCHED_KEY, 0);
        if (fetched <= 0) return Weather.empty();
        double temp = Double.longBitsToDouble(preferences.getLong(TEMP_KEY, 0));
        int code = preferences.getInt(CODE_KEY, -1);
        return Weather.fromCode(code, temp, Weather.PLACE, fetched);
    }

    static boolean isStale(Weather weather, long nowMs) {
        if (weather == null || !weather.valid) return true;
        long age = nowMs - weather.fetchedAtEpochMs;
        return age < 0 || age >= REFRESH_MS;
    }

    private static void save(Context context, Weather weather, int code) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(TEMP_KEY, Double.doubleToRawLongBits(weather.temperatureC))
                .putInt(CODE_KEY, code)
                .putLong(FETCHED_KEY, weather.fetchedAtEpochMs)
                .apply();
    }

    /** Parses the forecast body; the caller builds the {@link Weather} so the code can be cached too. */
    private static JSONObject download() throws IOException, JSONException {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) new URL(FORECAST_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            stream = connection.getInputStream();
            StringBuilder body = new StringBuilder(512);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) > 0) body.append(buffer, 0, read);
            return new JSONObject(body.toString());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Nothing left to read; the connection is dropped below anyway.
                }
            }
            if (connection != null) connection.disconnect();
        }
    }
}
