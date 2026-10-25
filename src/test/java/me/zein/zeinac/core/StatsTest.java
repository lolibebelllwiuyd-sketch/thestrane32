package me.zein.zeinac.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatsTest {
    @Test
    void poissonKnownValues() {
        assertEquals(1.0 - Math.exp(-1.0), Stats.poissonUpperTail(1, 1.0), 1e-9);
        double expected = 1.0 - Math.exp(-0.5) * (1.0 + 0.5 + 0.125);
        assertEquals(expected, Stats.poissonUpperTail(3, 0.5), 1e-9);
    }

    @Test
    void poissonEdgeCases() {
        assertEquals(1.0, Stats.poissonUpperTail(0, 3.0), 1e-12);
        assertEquals(0.0, Stats.poissonUpperTail(5, 0.0), 1e-12);
        assertTrue(Stats.poissonUpperTail(2, 100.0) > 0.999999);
        assertTrue(Stats.poissonUpperTail(40, 1.0) < 1e-40);
    }

    @Test
    void poissonIsMonotonic() {
        double prev = 2.0;
        for (int k = 0; k < 15; k++) {
            double p = Stats.poissonUpperTail(k, 3.0);
            assertTrue(p <= prev + 1e-12, "tail must not increase with k");
            prev = p;
        }
    }

    @Test
    void binomialKnownValues() {
        assertEquals(0.5, Stats.binomialUpperTail(2, 3, 0.5), 1e-12);
        assertEquals(1.0, Stats.binomialUpperTail(0, 10, 0.1), 1e-12);
        assertEquals(0.0, Stats.binomialUpperTail(11, 10, 0.1), 1e-12);
        assertEquals(Math.pow(0.1, 3), Stats.binomialUpperTail(3, 3, 0.1), 1e-12);
    }

    @Test
    void luckyPlayerIsNotFlaggedButHeavyOutlierIs() {
        // 3 hidden veins when ~1.5 expected: ordinary luck, score far below a 3.0 trigger
        double lucky = Stats.score(Stats.poissonUpperTail(3, 1.5));
        assertTrue(lucky < 1.0, "lucky score " + lucky);
        // 12 hidden veins when ~1.5 expected: extremely unlikely
        double cheat = Stats.score(Stats.poissonUpperTail(12, 1.5));
        assertTrue(cheat > 5.0, "outlier score " + cheat);
    }

    @Test
    void scoreBounds() {
        assertEquals(0.0, Stats.score(1.0), 1e-12);
        assertEquals(0.0, Stats.score(2.0), 1e-12);
        assertEquals(300.0, Stats.score(0.0), 1e-9);
        assertEquals(3.0, Stats.score(0.001), 1e-9);
    }
}
