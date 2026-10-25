package me.zein.zeinac.model;

/** How sure the plugin is. HIGH requires multiple independent indicators (xray) or sustained server-verified violations (freecam). */
public enum Confidence {
    LOW, MEDIUM, HIGH;

    public boolean atLeast(Confidence other) {
        return ordinal() >= other.ordinal();
    }

    public static Confidence parse(String s, Confidence def) {
        if (s == null) {
            return def;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
