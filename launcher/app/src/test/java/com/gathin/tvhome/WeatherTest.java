package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the weather panel's pure logic. */
public class WeatherTest {

    @Test
    public void emptyHasNoNumber() {
        assertFalse(Weather.EMPTY.valid);
        assertEquals(Integer.MIN_VALUE, Weather.EMPTY.degrees());
    }

    @Test
    public void fromCodeRejectsNaNAndInfinity() {
        assertEquals(Weather.EMPTY, Weather.fromCode(0, Double.NaN, Weather.PLACE, 1000L));
        assertEquals(Weather.EMPTY, Weather.fromCode(0, Double.POSITIVE_INFINITY, Weather.PLACE, 1000L));
    }

    @Test
    public void fromCodeKeepsTheTemperature() {
        Weather w = Weather.fromCode(0, 23.6, Weather.PLACE, 1234L);
        assertTrue(w.valid);
        assertEquals(24, w.degrees());
        assertEquals("Despejado", w.condition);
        assertEquals(1234L, w.fetchedAtEpochMs);
    }

    // WeatherClient.isStale lives in a class that needs the Android main looper, so it is not
    // reachable from a plain JVM test; covered indirectly through the panel's refresh path.
}
