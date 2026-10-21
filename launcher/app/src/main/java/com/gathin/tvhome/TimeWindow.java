package com.gathin.tvhome;

import java.util.TimeZone;

/**
 * Time-of-day slots for the "time" wallpaper mode (config.json, wallpaper_mode): four parts of the
 * day, each one picture.
 *
 * Pure maths, no Android types and no clock of its own: the caller passes the minute since midnight,
 * so the slots can be tested on the JVM. The cuts are the ones the greeting has always used
 * (night before 5, morning before 12, afternoon before 17, evening before 22), so the picture and
 * the greeting change together.
 */
final class TimeWindow {

    /** The four parts of the day a theme can give a picture to. */
    enum Slot {
        NIGHT, MORNING, AFTERNOON, DUSK
    }

    /** Kinds the wallpaper loader tries, in order, for a slot's name. */
    private static final String[] EXTENSIONS = {"jpg", "jpeg", "png", "webp"};

    private TimeWindow() {
    }

    /** The slot at {@code minuteOfDay}: 22:00 to 04:59 is night, 05:00 morning, 12:00 afternoon, 17:00 dusk. */
    static Slot at(int minuteOfDay) {
        int m = Math.max(0, Math.min(1439, minuteOfDay));
        if (m < 300) return Slot.NIGHT;       // 00:00 to 04:59
        if (m < 720) return Slot.MORNING;     // 05:00 to 11:59
        if (m < 1020) return Slot.AFTERNOON;  // 12:00 to 16:59
        if (m < 1320) return Slot.DUSK;       // 17:00 to 21:59
        return Slot.NIGHT;                    // 22:00 to 23:59
    }

    /** The file a slot looks for in the wallpapers folder, without extension: "bg_night", "bg_morning"... */
    static String fileName(Slot slot) {
        switch (slot) {
            case MORNING:
                return "bg_morning";
            case AFTERNOON:
                return "bg_afternoon";
            case DUSK:
                return "bg_dusk";
            case NIGHT:
            default:
                return "bg_night";
        }
    }

    /** Extensions {@link #fileName} may have, in the order the loader tries them. Never mutated by callers. */
    static String[] extensions() {
        return EXTENSIONS.clone();
    }

    /** Local minute of day, 0 to 1439, of a wall clock instant. */
    static int minuteOfDay(long epochMs, TimeZone tz) {
        long minutes = Math.floorDiv(epochMs + tz.getOffset(epochMs), 60_000L);
        return (int) Math.floorMod(minutes, 1440L);
    }

    /** The slot after {@code current}, wrapping around at midnight; use it to tell whether the slot changed. */
    static Slot next(Slot current) {
        if (current == null) return at(0);
        switch (current) {
            case NIGHT:
                return Slot.MORNING;
            case MORNING:
                return Slot.AFTERNOON;
            case AFTERNOON:
                return Slot.DUSK;
            case DUSK:
            default:
                return Slot.NIGHT;
        }
    }
}
