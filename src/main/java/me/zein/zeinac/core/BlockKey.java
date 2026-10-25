package me.zein.zeinac.core;

/** Packs block coordinates into a single long (26 bits x, 26 bits z, 12 bits y). Used for equality only. */
public final class BlockKey {
    private BlockKey() {}

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
