package gr.crossstreet.stats;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Pearson and Spearman correlation with two-tailed significance tests and a bootstrap
 * confidence interval.
 *
 * <p>The bootstrap matters here more than the parametric interval does. With six cities the
 * t-based confidence interval on r rests on a bivariate-normality assumption that six points
 * cannot support, whereas a percentile bootstrap at least reports honestly how much the estimate
 * moves when individual cities are resampled — which, at n = 6, is a great deal.</p>
 */
public final class Correlation {

    private static final int BOOTSTRAP_SAMPLES = 10_000;
    private static final long BOOTSTRAP_SEED = 42;

    private Correlation() {
        // Utility class
    }

    /**
     * A correlation estimate and its uncertainty.
     *
     * @param coefficient  r (Pearson) or ρ (Spearman)
     * @param pValue       two-tailed p-value under the null of no association
     * @param n            number of paired observations
     * @param ciLow        lower bound of the 95 % percentile bootstrap interval
     * @param ciHigh       upper bound of the 95 % percentile bootstrap interval
     */
    public record Result(double coefficient, double pValue, int n, double ciLow, double ciHigh) {

        /** Formats as "r = 0.87, 95 % CI [0.21, 0.98], p = 0.024, n = 6". */
        public String format(String symbol) {
            return String.format(java.util.Locale.US,
                    "%s = %.3f, 95%% CI [%.3f, %.3f], p = %.4f, n = %d",
                    symbol, coefficient, ciLow, ciHigh, pValue, n);
        }
    }

    /** Pearson product-moment correlation between two equal-length series. */
    public static Result pearson(double[] x, double[] y) {
        int n = requireEqualLength(x, y);
        double r = pearsonCoefficient(x, y);
        double[] ci = bootstrapInterval(x, y, Correlation::pearsonCoefficient);
        return new Result(r, pValueFor(r, n), n, ci[0], ci[1]);
    }

    /**
     * Spearman rank correlation — Pearson applied to the ranks, with tied values sharing
     * their average rank. Preferred over Pearson when the relationship is expected to be
     * monotonic but not necessarily linear, and less sensitive to a single outlying city.
     */
    public static Result spearman(double[] x, double[] y) {
        int n = requireEqualLength(x, y);
        double rho = spearmanCoefficient(x, y);
        double[] ci = bootstrapInterval(x, y, Correlation::spearmanCoefficient);
        return new Result(rho, pValueFor(rho, n), n, ci[0], ci[1]);
    }

    private static double pearsonCoefficient(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) return Double.NaN;

        double meanX = Arrays.stream(x).average().orElse(0);
        double meanY = Arrays.stream(y).average().orElse(0);

        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX <= 0 || varianceY <= 0) return Double.NaN;
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    private static double spearmanCoefficient(double[] x, double[] y) {
        return pearsonCoefficient(rank(x), rank(y));
    }

    /** Converts values to ranks, assigning tied values the mean of the ranks they span. */
    static double[] rank(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++;
            double averageRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) ranks[order[k]] = averageRank;
            i = j + 1;
        }
        return ranks;
    }

    /** Two-tailed p-value via the t transform of a correlation coefficient, df = n − 2. */
    private static double pValueFor(double r, int n) {
        if (n < 3 || Double.isNaN(r)) return 1.0;
        if (Math.abs(r) >= 1.0) return 0.0;
        double t = r * Math.sqrt((n - 2) / (1 - r * r));
        return Distributions.tTestTwoTailed(t, n - 2);
    }

    /**
     * 95 % percentile bootstrap interval, resampling city pairs with replacement.
     * Resamples that degenerate (every drawn point identical, so variance is zero) are skipped.
     */
    private static double[] bootstrapInterval(double[] x, double[] y,
                                              java.util.function.BiFunction<double[], double[], Double> estimator) {
        int n = x.length;
        if (n < 3) return new double[]{Double.NaN, Double.NaN};

        java.util.Random random = new java.util.Random(BOOTSTRAP_SEED);
        double[] estimates = new double[BOOTSTRAP_SAMPLES];
        int kept = 0;

        for (int b = 0; b < BOOTSTRAP_SAMPLES; b++) {
            double[] sampleX = new double[n];
            double[] sampleY = new double[n];
            for (int i = 0; i < n; i++) {
                int pick = random.nextInt(n);
                sampleX[i] = x[pick];
                sampleY[i] = y[pick];
            }
            double estimate = estimator.apply(sampleX, sampleY);
            if (!Double.isNaN(estimate)) {
                estimates[kept++] = estimate;
            }
        }

        if (kept < 100) return new double[]{Double.NaN, Double.NaN};
        double[] valid = Arrays.copyOf(estimates, kept);
        Arrays.sort(valid);
        return new double[]{
                valid[(int) (0.025 * kept)],
                valid[(int) Math.min(0.975 * kept, kept - 1.0)]
        };
    }

    private static int requireEqualLength(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException(
                    "Series lengths differ: %d vs %d".formatted(x.length, y.length));
        }
        return x.length;
    }
}
