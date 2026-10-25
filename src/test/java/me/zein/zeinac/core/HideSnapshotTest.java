package me.zein.zeinac.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.zein.zeinac.hide.HideSnapshot;
import org.junit.jupiter.api.Test;

class HideSnapshotTest {
    @Test
    void surfaceHidesEverything() {
        HideSnapshot s = new HideSnapshot("world", 0, -64, 16, true, 0, 0, 3);
        assertTrue(s.hidden(0, 0));
        assertTrue(s.hidden(50, 50));
    }

    @Test
    void undergroundHidesOnlyBeyondRadius() {
        HideSnapshot s = new HideSnapshot("world", 0, -64, 16, false, 10, -4, 3);
        assertFalse(s.hidden(10, -4));
        assertFalse(s.hidden(13, -1));
        assertTrue(s.hidden(14, -4));
        assertTrue(s.hidden(10, 0));
    }

    @Test
    void negativeRadiusDisablesRadiusRule() {
        HideSnapshot s = new HideSnapshot("world", 0, -64, 16, false, 0, 0, -1);
        assertFalse(s.hidden(1000, 1000));
    }

    @Test
    void sameOutcomeDetectsChunksThatNeedRefresh() {
        HideSnapshot a = new HideSnapshot("world", 0, -64, 16, false, 0, 0, 3);
        HideSnapshot b = new HideSnapshot("world", 0, -64, 16, false, 1, 0, 3);
        assertTrue(a.sameOutcome(b, 0, 0));
        assertFalse(a.sameOutcome(b, 4, 0)); // 4 is outside a's radius but inside b's
        HideSnapshot other = new HideSnapshot("nether", 1, 0, 16, false, 0, 0, 3);
        assertFalse(a.sameOutcome(other, 0, 0));
    }
}
