package com.gathin.tvhome;

/**
 * The weather shown next to the clock: the last Open-Meteo reading for the place the launcher is set to
 * (see {@link WeatherClient}). Immutable, no Android types, so it is tested on the JVM.
 */
final class Weather {

    /** The place the launcher asks about; Open-Meteo takes the coordinates and the zone in the URL. */
    static final String PLACE = "La Rioja";

    /** Shown while the first fetch is running or when nothing could be loaded. */
    static final Weather EMPTY = new Weather(Double.NaN, "\u2014", PLACE, 0, false);

    /** Degrees Celsius; {@link Double#NaN} for the placeholder, which shows no number. */
    final double temperatureC;
    /** In Spanish, e.g. "Nublado" (see {@link #fromCode}). */
    final String condition;
    final String place;
    final long fetchedAtEpochMs;
    /** False for the placeholder: no number and no condition to show. */
    final boolean valid;

    private Weather(double temperatureC, String condition, String place, long fetchedAtEpochMs, boolean valid) {
        this.temperatureC = temperatureC;
        this.condition = condition;
        this.place = place;
        this.fetchedAtEpochMs = fetchedAtEpochMs;
        this.valid = valid;
    }

    static Weather empty() {
        return EMPTY;
    }

    /** A reading from Open-Meteo's WMO weather code, taken at {@code nowMs}. */
    static Weather fromCode(int weatherCode, double tempC, String place, long nowMs) {
        if (Double.isNaN(tempC) || Double.isInfinite(tempC)) return EMPTY;
        String where = place == null || place.trim().isEmpty() ? PLACE : place.trim();
        return new Weather(tempC, conditionFor(weatherCode), where, nowMs, true);
    }

    /** The WMO weather code as the text the panel shows; "—" for a code the API adds later. */
    static String conditionFor(int code) {
        switch (code) {
            case 0: return "Despejado";
            case 1: return "Mayormente despejado";
            case 2: return "Parcialmente nublado";
            case 3: return "Nublado";
            case 45:
            case 48: return "Niebla";
            case 51:
            case 53:
            case 55: return "Llovizna";
            case 56:
            case 57: return "Llovizna helada";
            case 61:
            case 63:
            case 65: return "Lluvia";
            case 66:
            case 67: return "Lluvia helada";
            case 71:
            case 73:
            case 75: return "Nieve";
            case 77: return "Granizo fino";
            case 80:
            case 81:
            case 82: return "Chaparrones";
            case 85:
            case 86: return "Nevadas";
            case 95: return "Tormenta";
            case 96:
            case 99: return "Tormenta con granizo";
            default: return "\u2014";
        }
    }

    /** The temperature as the panel shows it: rounded to a whole degree. */
    int degrees() {
        return valid ? (int) Math.round(temperatureC) : Integer.MIN_VALUE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Weather)) return false;
        Weather w = (Weather) other;
        return valid == w.valid && Double.compare(temperatureC, w.temperatureC) == 0
                && fetchedAtEpochMs == w.fetchedAtEpochMs
                && condition.equals(w.condition) && place.equals(w.place);
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(temperatureC);
        h = 31 * h + condition.hashCode();
        h = 31 * h + place.hashCode();
        h = 31 * h + (int) (fetchedAtEpochMs ^ (fetchedAtEpochMs >>> 32));
        return 31 * h + (valid ? 1 : 0);
    }

    @Override
    public String toString() {
        return valid ? degrees() + "\u00B0 " + condition + ", " + place : "(no weather)";
    }
}
