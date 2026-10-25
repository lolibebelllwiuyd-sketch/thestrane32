package me.zein.zeinac.core;

/**
 * Small statistics helpers (no external dependencies).
 * All p-values are one-sided upper tails: "how likely is a result at least this extreme by pure luck?".
 */
public final class Stats {
    private static final int TABLE = 4096;
    private static final double[] LOG_FACT = new double[TABLE];

    static {
        LOG_FACT[0] = 0.0;
        for (int i = 1; i < TABLE; i++) {
            LOG_FACT[i] = LOG_FACT[i - 1] + Math.log(i);
        }
    }

    private Stats() {}

    public static double logFactorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n < 0");
        }
        if (n < TABLE) {
            return LOG_FACT[n];
        }
        double x = n;
        return x * Math.log(x) - x + 0.5 * Math.log(2.0 * Math.PI * x) + 1.0 / (12.0 * x);
    }

    /** P(X >= k) for X ~ Poisson(lambda). */
    public static double poissonUpperTail(int k, double lambda) {
        if (k <= 0) {
            return 1.0;
        }
        if (!(lambda > 0.0)) {
            return 0.0;
        }
        double term = Math.exp(-lambda + k * Math.log(lambda) - logFactorial(k));
        if (term == 0.0) {
            return k < lambda ? 1.0 : 0.0;
        }
        double sum = 0.0;
        int i = k;
        for (int guard = 0; guard < 200000; guard++) {
            sum += term;
            i++;
            term *= lambda / i;
            if (term == 0.0) {
                break;
            }
            if (i > lambda && term < sum * 1e-16) {
                break;
            }
        }
        return Math.min(1.0, sum);
    }

    /** P(X >= k) for X ~ Binomial(n, q). */
    public static double binomialUpperTail(int k, int n, double q) {
        if (k <= 0) {
            return 1.0;
        }
        if (k > n || q <= 0.0) {
            return 0.0;
        }
        if (q >= 1.0) {
            return 1.0;
        }
        double lq = Math.log(q);
        double l1q = Math.log1p(-q);
        double lnFact = logFactorial(n);
        double sum = 0.0;
        for (int i = k; i <= n; i++) {
            double lt = lnFact - logFactorial(i) - logFactorial(n - i) + i * lq + (n - i) * l1q;
            sum += Math.exp(lt);
        }
        return Math.min(1.0, sum);
    }

    /** Converts a p-value to a "score" = -log10(p), capped at 300. Higher = less likely to be luck. */
    public static double score(double p) {
        if (p >= 1.0) {
            return 0.0;
        }
        return Math.max(0.0, -Math.log10(Math.max(p, 1e-300)));
    }
}
