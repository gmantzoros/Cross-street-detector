package gr.crossstreet.stats;

/**
 * The handful of special functions and distribution tails needed to attach p-values to the
 * correlation and regression results, implemented directly so the project keeps its
 * dependency-free stance (see §6.2 — Overpass is the sole external dependency).
 *
 * <p>Algorithms follow <i>Numerical Recipes</i>, 3rd ed.: a Lanczos approximation for the log
 * gamma function and a modified Lentz continued fraction for the incomplete beta.</p>
 */
public final class Distributions {

    private static final double[] LANCZOS = {
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7
    };

    private static final int MAX_ITERATIONS = 300;
    private static final double EPSILON = 3.0e-16;
    private static final double TINY = 1.0e-300;

    private Distributions() {
        // Utility class
    }

    /** Natural logarithm of the gamma function, for x &gt; 0. */
    public static double logGamma(double x) {
        if (x < 0.5) {
            // Reflection formula, to keep the series in its region of convergence.
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        }
        double z = x - 1;
        double a = 0.99999999999980993;
        for (int i = 0; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (z + i + 1);
        }
        double t = z + LANCZOS.length - 0.5;
        return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularised incomplete beta function I_x(a, b). */
    public static double incompleteBeta(double a, double b, double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;

        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1 - x));

        // The continued fraction converges quickly only on one side of the symmetry point;
        // reflect onto the other side when necessary.
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(a, b, x) / a;
        }
        return 1 - front * betaContinuedFraction(b, a, 1 - x) / b;
    }

    /** Modified Lentz evaluation of the continued fraction for the incomplete beta. */
    private static double betaContinuedFraction(double a, double b, double x) {
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;

        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < TINY) d = TINY;
        d = 1 / d;
        double result = d;

        for (int m = 1; m <= MAX_ITERATIONS; m++) {
            int m2 = 2 * m;

            // Even step of the recurrence.
            double numerator = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + numerator * d;
            if (Math.abs(d) < TINY) d = TINY;
            c = 1 + numerator / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            result *= d * c;

            // Odd step.
            numerator = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + numerator * d;
            if (Math.abs(d) < TINY) d = TINY;
            c = 1 + numerator / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            double delta = d * c;
            result *= delta;

            if (Math.abs(delta - 1) < EPSILON) break;
        }
        return result;
    }

    /**
     * Two-tailed p-value for a t statistic with {@code df} degrees of freedom.
     * Returns 1.0 for a non-positive df, where the statistic is undefined.
     */
    public static double tTestTwoTailed(double t, double df) {
        if (df <= 0 || Double.isNaN(t)) return 1.0;
        return incompleteBeta(df / 2.0, 0.5, df / (df + t * t));
    }

    /** Two-tailed p-value for a standard normal z statistic. */
    public static double zTestTwoTailed(double z) {
        if (Double.isNaN(z)) return 1.0;
        return erfc(Math.abs(z) / Math.sqrt(2));
    }

    /**
     * Complementary error function, via the Chebyshev-fitted approximation from
     * <i>Numerical Recipes</i>; accurate to about 1.2e-7 relative, which is far finer than
     * any p-value reported here needs.
     */
    public static double erfc(double x) {
        double z = Math.abs(x);
        double t = 2.0 / (2.0 + z);
        double ty = 4.0 * t - 2.0;

        double[] coefficients = {
                -1.3026537197817094, 6.4196979235649026e-1, 1.9476473204185836e-2,
                -9.561514786808631e-3, -9.46595344482036e-4, 3.66839497852761e-4,
                4.2523324806907e-5, -2.0278578112534e-5, -1.624290004647e-6,
                1.303655835580e-6, 1.5626441722e-8, -8.5238095915e-8,
                6.529054439e-9, 5.059343495e-9, -9.91364156e-10,
                -2.27365122e-10, 9.6467911e-11, 2.394038e-11,
                -6.886027e-12, -1.61748e-12, 3.9440e-13,
                1.1522e-13, -4.515e-14, -1.454e-14,
                6.20e-15, 4.04e-15, -1.06e-15
        };

        double d = 0;
        double dd = 0;
        for (int j = coefficients.length - 1; j > 0; j--) {
            double tmp = d;
            d = ty * d - dd + coefficients[j];
            dd = tmp;
        }
        double result = t * Math.exp(-z * z + 0.5 * (coefficients[0] + ty * d) - dd);
        return x >= 0 ? result : 2.0 - result;
    }
}
