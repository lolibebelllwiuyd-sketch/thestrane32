package me.zein.zeinac.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MeterAndRingTest {
    @Test
    void meterDecaysByHalfLife() {
        ViolationMeter m = new ViolationMeter(1000.0);
        m.add(8.0, 1_000L);
        assertEquals(4.0, m.get(2_000L), 1e-6);
        assertEquals(2.0, m.get(3_000L), 1e-6);
        m.add(1.0, 3_000L);
        assertEquals(3.0, m.get(3_000L), 1e-6);
    }

    @Test
    void meterResets() {
        ViolationMeter m = new ViolationMeter(1000.0);
        m.add(5.0, 10L);
        m.reset();
        assertEquals(0.0, m.get(20L), 1e-12);
    }

    @Test
    void poseRingKeepsNewestFirstAndIsBounded() {
        PoseRing r = new PoseRing(3);
        for (int i = 1; i <= 5; i++) {
            r.record(i, 0, 0, 0f, 0f, i * 10L);
        }
        assertEquals(3, r.size());
        assertEquals(5.0, r.x(0), 1e-12);
        assertEquals(4.0, r.x(1), 1e-12);
        assertEquals(3.0, r.x(2), 1e-12);
        assertEquals(50L, r.time(0));
    }

    @Test
    void poseRingIgnoresIdenticalSamples() {
        PoseRing r = new PoseRing(4);
        r.record(1, 2, 3, 4f, 5f, 100L);
        r.record(1, 2, 3, 4f, 5f, 200L);
        assertEquals(1, r.size());
        assertEquals(200L, r.time(0));
    }

    @Test
    void recentBreaksRespectAge() {
        RecentBreaks rb = new RecentBreaks(8);
        rb.add(1, 2, 3, 1_000L);
        assertTrue(rb.contains(1, 2, 3, 5_000L, 10_000L));
        assertFalse(rb.contains(1, 2, 3, 20_000L, 10_000L));
        assertFalse(rb.contains(9, 9, 9, 5_000L, 10_000L));
    }

    @Test
    void veinTrackerLinksNearbyOre() {
        VeinTracker v = new VeinTracker(4);
        assertFalse(v.touchOrAdd(0, 10, -50, 10, 1_000L, 3, 60_000L), "first block is a new vein");
        assertTrue(v.touchOrAdd(0, 11, -50, 11, 2_000L, 3, 60_000L), "adjacent block is the same vein");
        assertFalse(v.touchOrAdd(0, 40, -50, 40, 3_000L, 3, 60_000L), "far block is a new vein");
        assertFalse(v.touchOrAdd(1, 11, -50, 11, 4_000L, 3, 60_000L), "different group is a new vein");
        assertFalse(v.touchOrAdd(0, 10, -50, 10, 200_000L, 3, 60_000L), "stale vein is forgotten");
    }

    @Test
    void windowCountersRollOff() {
        WindowCounters w = new WindowCounters(2, 3, 10);
        long t0 = 60_000L * 100;
        w.add(t0, 0, 0, 5);
        w.add(t0 + 60_000L * 3, 0, 0, 2);
        w.add(t0, 1, 2, 7);
        assertEquals(7, w.sum(t0 + 60_000L * 3, 0, 0));
        assertEquals(7, w.sum(t0 + 60_000L * 3, 1, 2));
        assertEquals(2, w.sum(t0 + 60_000L * 12, 0, 0), "first minute rolled out of the 10 minute window");
        assertEquals(0, w.sum(t0 + 60_000L * 30, 0, 0));
    }

    @Test
    void baselineBlendsPriorAndObservations() {
        Baseline b = new Baseline();
        assertEquals(0.0025, b.ratePerBlock("diamond", 2.5, 20_000), 1e-12);
        b.addCounted("diamond", 80_000);
        b.addHidden("diamond", 80);
        // (0.0025*20000 + 80) / (20000 + 80000) = 130 / 100000
        assertEquals(0.0013, b.ratePerBlock("diamond", 2.5, 20_000), 1e-12);
    }
}
