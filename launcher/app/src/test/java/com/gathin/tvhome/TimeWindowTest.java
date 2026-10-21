package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for the time-of-day slots: the exact cuts, their edges and the order. */
public class TimeWindowTest {

    @Test
    public void theFourCutsAreExact() {
        assertEquals(TimeWindow.Slot.NIGHT, TimeWindow.at(0));        // 00:00
        assertEquals(TimeWindow.Slot.NIGHT, TimeWindow.at(299));      // 04:59
        assertEquals(TimeWindow.Slot.MORNING, TimeWindow.at(300));    // 05:00
        assertEquals(TimeWindow.Slot.MORNING, TimeWindow.at(719));    // 11:59
        assertEquals(TimeWindow.Slot.AFTERNOON, TimeWindow.at(720));  // 12:00
        assertEquals(TimeWindow.Slot.AFTERNOON, TimeWindow.at(1019)); // 16:59
        assertEquals(TimeWindow.Slot.DUSK, TimeWindow.at(1020));      // 17:00
        assertEquals(TimeWindow.Slot.DUSK, TimeWindow.at(1319));      // 21:59
        assertEquals(TimeWindow.Slot.NIGHT, TimeWindow.at(1320));     // 22:00
        assertEquals(TimeWindow.Slot.NIGHT, TimeWindow.at(1439));     // 23:59
    }

    @Test
    public void everyMinuteOfTheDayHasASlot() {
        int changes = 0;
        TimeWindow.Slot previous = TimeWindow.at(0);
        for (int minute = 1; minute < 1440; minute++) {
            TimeWindow.Slot slot = TimeWindow.at(minute);
            if (slot != previous) changes++;
            previous = slot;
        }
        // 05:00, 12:00, 17:00, 22:00, and nothing else.
        assertEquals(4, changes);
        assertEquals(TimeWindow.Slot.NIGHT, previous);
    }

    @Test
    public void namesAreTheFilesASlotLooksFor() {
        assertEquals("bg_night", TimeWindow.fileName(TimeWindow.Slot.NIGHT));
        assertEquals("bg_morning", TimeWindow.fileName(TimeWindow.Slot.MORNING));
        assertEquals("bg_afternoon", TimeWindow.fileName(TimeWindow.Slot.AFTERNOON));
        assertEquals("bg_dusk", TimeWindow.fileName(TimeWindow.Slot.DUSK));
        assertEquals(4, TimeWindow.extensions().length);
        assertEquals("jpg", TimeWindow.extensions()[0]);
        assertEquals("webp", TimeWindow.extensions()[3]);
    }

    @Test
    public void nextFollowsTheClockAndWrapsAtMidnight() {
        assertEquals(TimeWindow.Slot.MORNING, TimeWindow.next(TimeWindow.Slot.NIGHT));
        assertEquals(TimeWindow.Slot.AFTERNOON, TimeWindow.next(TimeWindow.Slot.MORNING));
        assertEquals(TimeWindow.Slot.DUSK, TimeWindow.next(TimeWindow.Slot.AFTERNOON));
        assertEquals(TimeWindow.Slot.NIGHT, TimeWindow.next(TimeWindow.Slot.DUSK));
        // Dusk runs into night, which is the slot right after it on the clock.
        assertEquals(TimeWindow.at(1320), TimeWindow.next(TimeWindow.at(1319)));
    }
}
