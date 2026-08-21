package gr.crossstreet.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Binary logistic regression fitted by iteratively reweighted least squares (Newton–Raphson),
 * reporting coefficients with Wald standard errors, z statistics and p-values.
 *
 * <p>This is what carries the per-case analysis: regressing PASS/FAIL on the local orientation
 * order around each test case turns a six-point city-level scatter into a 500-point model of the
 * same hypothesis, and yields an effect size with a confidence interval rather than a rank
 * ordering that six cities happen to satisfy.</p>
 *
 * <p>An intercept column is added automatically; callers pass predictors only.</p>
 */
public final class LogisticRegression {

    private static final int MAX_ITERATIONS = 100;
    private static final double CONVERGENCE_TOLERANCE = 1e-10;
    /** Added to the diagonal of XᵀWX before inversion, to survive near-separation. */
    private static final double RIDGE = 1e-8;

    private LogisticRegression() {
        // Utility class
    }

    /**
     * One fitted coefficient.
     *
     * @param name           predictor name ("(intercept)" for the constant term)
     * @param coefficient    β on the log-odds scale
     * @param standardError  Wald standard error of β
     * @param z              β / SE
     * @param pValue         two-tailed p-value against β = 0
     * @param oddsRatio      exp(β) — the multiplicative change in odds per unit of the predictor
     * @param oddsRatioPerSd exp(β · SD) — per one standard deviation, usually the readable one
     */
    public record Coefficient(
            String name,
            double coefficient,
            double standardError,
            double z,
            double pValue,
            double oddsRatio,
            double oddsRatioPerSd
    ) {
        /** 95 % Wald confidence interval for the odds ratio. */
        public double[] oddsRatioCi() {
            return new double[]{
                    Math.exp(coefficient - 1.96 * standardError),
                    Math.exp(coefficient + 1.96 * standardError)
            };
        }
    }

    /**
     * A fitted model.
     *
     * @param coefficients      intercept first, then one entry per predictor in the order supplied
     * @param logLikelihood     log-likelihood at convergence
     * @param nullLogLikelihood log-likelihood of the intercept-only model
     * @param mcFaddenR2        1 − logLikelihood / nullLogLikelihood
     * @param auc               area under the ROC curve of the fitted probabilities
     * @param n                 number of observations
     * @param converged         whether IRLS reached the tolerance before the iteration cap
     */
    public record Model(
            List<Coefficient> coefficients,
            double logLikelihood,
            double nullLogLikelihood,
            double mcFaddenR2,
            double auc,
            int n,
            boolean converged
    ) {
        /** Likelihood-ratio test of the full model against the intercept-only model. */
        public double likelihoodRatioP() {
            double statistic = 2 * (logLikelihood - nullLogLikelihood);
            int df = coefficients.size() - 1;
            if (df <= 0 || statistic <= 0) return 1.0;
            // Chi-squared upper tail with df degrees of freedom = I_x(df/2, ...) complement;
            // expressed through the incomplete gamma's relationship to the normal for df = 1,
            // and by series otherwise.
            return chiSquaredUpperTail(statistic, df);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US,
                    "  n = %d | log-likelihood = %.2f (null %.2f) | McFadden R² = %.4f | AUC = %.3f%s%n",
                    n, logLikelihood, nullLogLikelihood, mcFaddenR2, auc,
                    converged ? "" : "  [DID NOT CONVERGE]"));
            sb.append(String.format(Locale.US, "  likelihood-ratio test vs intercept-only: p = %.5f%n",
                    likelihoodRatioP()));
            sb.append(String.format(Locale.US, "  %-26s %10s %10s %8s %10s %10s %10s%n",
                    "term", "beta", "SE", "z", "p", "OR", "OR/SD"));
            for (Coefficient c : coefficients) {
                boolean isIntercept = "(intercept)".equals(c.name());
                sb.append(String.format(Locale.US, "  %-26s %10.4f %10.4f %8.2f %10.5f %10s %10s%n",
                        c.name(), c.coefficient(), c.standardError(), c.z(), c.pValue(),
                        isIntercept ? "-" : String.format(Locale.US, "%.3f", c.oddsRatio()),
                        isIntercept ? "-" : String.format(Locale.US, "%.3f", c.oddsRatioPerSd())));
            }
            sb.append("    OR    = exp(beta), per one unit of the predictor — the readable one for a 0/1 indicator.\n");
            sb.append("    OR/SD = exp(beta * SD), per one standard deviation — the readable one for a continuous predictor.\n");
            return sb.toString();
        }
    }

    /**
     * Fits the model.
     *
     * @param predictors     n × k matrix; row per observation, column per predictor
     * @param outcomes       n binary outcomes, 1 = success
     * @param predictorNames k names, used only for reporting
     */
    public static Model fit(double[][] predictors, int[] outcomes, String[] predictorNames) {
        int n = predictors.length;
        if (n == 0) throw new IllegalArgumentException("No observations supplied");
        int k = predictors[0].length;
        if (outcomes.length != n) {
            throw new IllegalArgumentException(
                    "Outcome count %d does not match observation count %d".formatted(outcomes.length, n));
        }
        if (predictorNames.length != k) {
            throw new IllegalArgumentException(
                    "Name count %d does not match predictor count %d".formatted(predictorNames.length, k));
        }

        // Design matrix with a leading intercept column.
        int p = k + 1;
        double[][] design = new double[n][p];
        for (int i = 0; i < n; i++) {
            design[i][0] = 1.0;
            for (int j = 0; j < k; j++) {
                design[i][j + 1] = predictors[i][j];
            }
        }

        double[] beta = new double[p];
        double[][] covariance = null;
        boolean converged = false;

        for (int iteration = 0; iteration < MAX_ITERATIONS && !converged; iteration++) {
            double[] probabilities = new double[n];
            for (int i = 0; i < n; i++) {
                probabilities[i] = sigmoid(dot(design[i], beta));
            }

            // Score vector Xᵀ(y − p) and Fisher information XᵀWX, W = diag(p(1−p)).
            double[] score = new double[p];
            double[][] information = new double[p][p];
            for (int i = 0; i < n; i++) {
                double residual = outcomes[i] - probabilities[i];
                double weight = Math.max(probabilities[i] * (1 - probabilities[i]), 1e-10);
                for (int a = 0; a < p; a++) {
                    score[a] += design[i][a] * residual;
                    for (int b = 0; b < p; b++) {
                        information[a][b] += design[i][a] * design[i][b] * weight;
                    }
                }
            }
            for (int a = 0; a < p; a++) information[a][a] += RIDGE;

            covariance = invert(information);
            double[] step = multiply(covariance, score);

            double maxChange = 0;
            for (int a = 0; a < p; a++) {
                beta[a] += step[a];
                maxChange = Math.max(maxChange, Math.abs(step[a]));
            }
            converged = maxChange < CONVERGENCE_TOLERANCE;
        }

        // Assemble results.
        double[] fitted = new double[n];
        for (int i = 0; i < n; i++) fitted[i] = sigmoid(dot(design[i], beta));

        double logLikelihood = logLikelihood(fitted, outcomes);
        double baseRate = Arrays.stream(outcomes).average().orElse(0.5);
        double[] nullFitted = new double[n];
        Arrays.fill(nullFitted, baseRate);
        double nullLogLikelihood = logLikelihood(nullFitted, outcomes);

        List<Coefficient> coefficients = new ArrayList<>(p);
        for (int a = 0; a < p; a++) {
            String name = a == 0 ? "(intercept)" : predictorNames[a - 1];
            double standardError = Math.sqrt(Math.max(covariance[a][a], 0));
            double z = standardError > 0 ? beta[a] / standardError : Double.NaN;
            double sd = a == 0 ? 0 : standardDeviation(design, a);
            coefficients.add(new Coefficient(
                    name, beta[a], standardError, z, Distributions.zTestTwoTailed(z),
                    Math.exp(beta[a]), Math.exp(beta[a] * sd)));
        }

        return new Model(coefficients, logLikelihood, nullLogLikelihood,
                1 - logLikelihood / nullLogLikelihood, auc(fitted, outcomes), n, converged);
    }

    /**
     * Area under the ROC curve, computed from the rank-sum (Mann–Whitney U) identity so that
     * ties in the fitted probabilities are handled correctly.
     */
    static double auc(double[] scores, int[] outcomes) {
        int positives = 0;
        for (int outcome : outcomes) if (outcome == 1) positives++;
        int negatives = outcomes.length - positives;
        if (positives == 0 || negatives == 0) return Double.NaN;

        double[] ranks = Correlation.rank(scores);
        double positiveRankSum = 0;
        for (int i = 0; i < outcomes.length; i++) {
            if (outcomes[i] == 1) positiveRankSum += ranks[i];
        }
        return (positiveRankSum - positives * (positives + 1) / 2.0) / ((double) positives * negatives);
    }

    private static double logLikelihood(double[] probabilities, int[] outcomes) {
        double total = 0;
        for (int i = 0; i < outcomes.length; i++) {
            double p = Math.clamp(probabilities[i], 1e-12, 1 - 1e-12);
            total += outcomes[i] == 1 ? Math.log(p) : Math.log(1 - p);
        }
        return total;
    }

    private static double standardDeviation(double[][] design, int column) {
        int n = design.length;
        double mean = 0;
        for (double[] row : design) mean += row[column];
        mean /= n;
        double variance = 0;
        for (double[] row : design) variance += (row[column] - mean) * (row[column] - mean);
        return Math.sqrt(variance / Math.max(n - 1, 1));
    }

    private static double sigmoid(double x) {
        // Branch on the sign to keep exp() away from overflow at large |x|.
        if (x >= 0) return 1 / (1 + Math.exp(-x));
        double e = Math.exp(x);
        return e / (1 + e);
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) result[i] = dot(matrix[i], vector);
        return result;
    }

    /** Gauss–Jordan inversion with partial pivoting; the matrices here are at most a few rows. */
    private static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n + i] = 1;
        }

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            }
            if (Math.abs(a[pivot][col]) < 1e-14) {
                throw new ArithmeticException(
                        "Fisher information matrix is singular at column %d — predictors are collinear "
                                + "or the outcome is perfectly separated".formatted(col));
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;

            double diagonal = a[col][col];
            for (int j = 0; j < 2 * n; j++) a[col][j] /= diagonal;

            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = a[row][col];
                if (factor == 0) continue;
                for (int j = 0; j < 2 * n; j++) a[row][j] -= factor * a[col][j];
            }
        }

        double[][] inverse = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], n, inverse[i], 0, n);
        return inverse;
    }

    /**
     * Upper tail of the chi-squared distribution, via the regularised incomplete gamma function
     * evaluated as a series (small x) or a continued fraction (large x).
     */
    static double chiSquaredUpperTail(double x, int df) {
        double a = df / 2.0;
        double scaled = x / 2.0;
        if (scaled < a + 1) {
            // Series expansion for the lower tail P(a, x), then complement.
            double term = 1.0 / a;
            double sum = term;
            for (int i = 1; i < 1000; i++) {
                term *= scaled / (a + i);
                sum += term;
                if (Math.abs(term) < Math.abs(sum) * 1e-15) break;
            }
            double lower = sum * Math.exp(-scaled + a * Math.log(scaled) - Distributions.logGamma(a));
            return Math.clamp(1 - lower, 0, 1);
        }

        // Lentz continued fraction for the upper tail Q(a, x).
        double tiny = 1e-300;
        double b = scaled + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 1000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) d = tiny;
            c = b + an / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-15) break;
        }
        return Math.clamp(h * Math.exp(-scaled + a * Math.log(scaled) - Distributions.logGamma(a)), 0, 1);
    }
}
