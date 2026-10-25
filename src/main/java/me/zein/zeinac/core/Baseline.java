package me.zein.zeinac.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-wide expected "hidden ore discoveries per mined block" per ore group.
 * Starts from the configured prior and gradually learns from the server's own (non-suspicious) players,
 * so the baseline adapts to the actual world seed/generation instead of trusting a guessed number.
 */
public final class Baseline {
    private final Map<String, long[]> data = new HashMap<>();

    public void addCounted(String id, int n) {
        data.computeIfAbsent(id, k -> new long[2])[0] += n;
    }

    public void addHidden(String id, int n) {
        data.computeIfAbsent(id, k -> new long[2])[1] += n;
    }

    /** Bayesian blend: prior worth {@code priorWeightBlocks} mined blocks, plus what we observed. */
    public double ratePerBlock(String id, double priorPer1000, double priorWeightBlocks) {
        long[] d = data.get(id);
        double c = d == null ? 0.0 : d[0];
        double h = d == null ? 0.0 : d[1];
        return (priorPer1000 / 1000.0 * priorWeightBlocks + h) / (priorWeightBlocks + c);
    }

    public long[] get(String id) {
        long[] d = data.get(id);
        return d == null ? new long[2] : d.clone();
    }

    public void load(Map<String, long[]> in) {
        for (Map.Entry<String, long[]> e : in.entrySet()) {
            long[] v = e.getValue();
            if (v != null && v.length >= 2) {
                long[] cur = data.computeIfAbsent(e.getKey(), k -> new long[2]);
                cur[0] += v[0];
                cur[1] += v[1];
            }
        }
    }

    public Map<String, long[]> snapshot() {
        Map<String, long[]> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : data.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        return out;
    }
}
