package me.zein.zeinac.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoTest {
    @Test
    void distanceToBox() {
        assertEquals(0.0, Geo.distanceToBox(0.5, 0.5, 0.5, 0, 0, 0, 1, 1, 1), 1e-12);
        assertEquals(2.0, Geo.distanceToBox(3.0, 0.5, 0.5, 0, 0, 0, 1, 1, 1), 1e-12);
        assertEquals(Math.sqrt(2.0), Geo.distanceToBox(2.0, 2.0, 0.5, 0, 0, 0, 1, 1, 1), 1e-12);
    }

    @Test
    void angleBetweenVectors() {
        assertEquals(0.0, Geo.angleDeg(1, 0, 0, 5, 0, 0), 1e-9);
        assertEquals(90.0, Geo.angleDeg(1, 0, 0, 0, 1, 0), 1e-9);
        assertEquals(180.0, Geo.angleDeg(1, 0, 0, -1, 0, 0), 1e-9);
        assertEquals(0.0, Geo.angleDeg(0, 0, 0, 1, 0, 0), 1e-9);
    }

    @Test
    void lookVectorMatchesMinecraftConvention() {
        double[] south = Geo.lookVector(0f, 0f);
        assertEquals(0.0, south[0], 1e-9);
        assertEquals(1.0, south[2], 1e-9);
        double[] west = Geo.lookVector(90f, 0f);
        assertEquals(-1.0, west[0], 1e-9);
        double[] down = Geo.lookVector(0f, 90f);
        assertEquals(-1.0, down[1], 1e-9);
    }

    @Test
    void insideAndClosest() {
        assertTrue(Geo.insideBox(0.5, 0.5, 0.5, 0, 0, 0, 1, 1, 1));
        assertFalse(Geo.insideBox(1.5, 0.5, 0.5, 0, 0, 0, 1, 1, 1));
        double[] c = Geo.closestPointOnBox(5, 0.5, -3, 0, 0, 0, 1, 1, 1);
        assertEquals(1.0, c[0], 1e-12);
        assertEquals(0.5, c[1], 1e-12);
        assertEquals(0.0, c[2], 1e-12);
    }
}
