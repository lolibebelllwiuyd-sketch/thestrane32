package me.zein.zeinac.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.zein.zeinac.hide.HideSnapshot;
import org.junit.jupiter.api.Test;

class PathTrackerTest {
    private static PathTracker tracker() {
        return new PathTracker(3.0, 0.6, 6.0, 20_000L);
    }

    @Test
    void straightTunnelNeverCountsAsBranch() {
        PathTracker p = tracker();
        long t = 1_000;
        for (int x = 0; x < 60; x++) {
            assertFalse(p.onBreak(x, -50, 0, t));
            t += 400;
        }
    }

    @Test
    void turnThenLongSegmentCountsOneBranchAndOneHit() {
        PathTracker p = tracker();
        long t = 1_000;
        int branches = 0;
        for (int x = 0; x <= 12; x++) {
            if (p.onBreak(x, -50, 0, t)) branches++;
            t += 400;
        }
        assertEquals(0, branches);
        for (int z = 1; z <= 10; z++) {
            if (p.onBreak(12, -50, z, t)) branches++;
            t += 400;
        }
        assertEquals(1, branches);
        assertTrue(p.markHit());
        assertFalse(p.markHit(), "a branch can only be a hit once");
    }

    @Test
    void shortSideStepIsNotABranch() {
        PathTracker p = tracker();
        long t = 1_000;
        int branches = 0;
        for (int x = 0; x <= 12; x++) {
            if (p.onBreak(x, -50, 0, t)) branches++;
            t += 400;
        }
        for (int z = 1; z <= 4; z++) {
            if (p.onBreak(12, -50, z, t)) branches++;
            t += 400;
        }
        assertEquals(0, branches);
        assertFalse(p.markHit());
    }

    @Test
    void longPauseResetsPath() {
        PathTracker p = tracker();
        p.onBreak(0, 0, 0, 1_000);
        p.onBreak(5, 0, 0, 2_000);
        // 60 s later: tracker restarts, so a sharp direction change is not a turn
        assertFalse(p.onBreak(5, 0, 6, 62_000));
        assertFalse(p.markHit());
    }
}

