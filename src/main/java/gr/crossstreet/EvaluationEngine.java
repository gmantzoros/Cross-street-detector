package gr.crossstreet;

import gr.crossstreet.image.DebugImageSaver;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates a single test case against the cross-street detector.
 *
 * <p>Evaluation flow:</p>
 * <ol>
 *   <li>Run the detector — returns a primary road (closer direction) and an alternative (farther).</li>
 *   <li>Primary matches target → <b>PASS</b>.</li>
 *   <li>Primary is acceptable (matches current road) or neither → try the alternative direction:
 *       <ul>
 *         <li>Alternative matches target → <b>PASS</b> (marked as alternative-used).</li>
 *         <li>Otherwise → <b>FAIL</b>.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final DebugImageSaver debugImageSaver;

    public EvaluationEngine() {
        this.debugImageSaver = null;
    }

    public EvaluationEngine(DebugImageSaver debugImageSaver) {
        this.debugImageSaver = debugImageSaver;
    }

    public enum Outcome { PASS, FAIL, ERROR }

    /**
     * @param testCase       the original test case
     * @param outcome        PASS, FAIL, or ERROR
     * @param detectedRoad   the road name(s) that led to this outcome
     * @param iterationsUsed number of forward steps taken (0 = immediate)
     * @param alternativeUsed true if the alternative direction's road produced the PASS
     * @param errorMessage   error details if outcome is ERROR, null otherwise
     */
    public record EvalResult(
            TestCase testCase,
            Outcome outcome,
            String detectedRoad,
            int iterationsUsed,
            boolean alternativeUsed,
            String errorMessage
    ) {}

    /**
     * Evaluates one test case, returning a result that is always PASS, FAIL, or ERROR —
     * never throws.
     */
    public EvalResult evaluate(CrossStreetDetectorApp app, TestCase tc) {
        try {
            return evaluateWithRetries(app, tc);
        } catch (Exception e) {
            log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage(), e);
            return new EvalResult(tc, Outcome.ERROR, "", 0, false, e.getMessage());
        }
    }

    private EvalResult evaluateWithRetries(CrossStreetDetectorApp app, TestCase tc) throws Exception {
        DetectionResult detection = app.detect(tc.currentCoords(), tc.previousCoords());
        String primary = detection.roadName().orElse("UNKNOWN");
        String alternative = detection.alternativeRoadName().orElse("UNKNOWN");

        log.info("#{} | primary='{}' alternative='{}'", tc.rowNumber(), primary, alternative);

        // Primary matches target → PASS
        if (fuzzyMatch(primary, tc.targetRoad())) {
            log.info("#{} | PASS | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), primary);
            return new EvalResult(tc, Outcome.PASS, primary, 0, false, null);
        }

        // Primary is acceptable (current road) or wrong — try the alternative direction
        if (fuzzyMatch(primary, tc.currentRoad())) {
            log.info("#{} | Primary '{}' is acceptable (current road) — trying alternative '{}'",
                    tc.rowNumber(), primary, alternative);
        } else {
            log.info("#{} | Primary '{}' is neither target nor current — trying alternative '{}'",
                    tc.rowNumber(), primary, alternative);
        }

        if (fuzzyMatch(alternative, tc.targetRoad())) {
            log.info("#{} | PASS (via alternative) | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), alternative);
            return new EvalResult(tc, Outcome.PASS, alternative, 0, true, null);
        }

        log.warn("#{} | FAIL | Target: {} | Primary: {} | Alternative: {}",
                tc.rowNumber(), tc.targetRoad(), primary, alternative);
        if (debugImageSaver != null) {
            debugImageSaver.save(tc, detection);
        }
        return new EvalResult(tc, Outcome.FAIL, primary + " / " + alternative, 0, false, "Wrong road on both directions");
    }

    /**
     * Fuzzy road name match handling Greek transliteration variants, abbreviations,
     * and last-word suffix matching.
     *
     * <p>Normalizes common Greek transliteration variants before comparing so that,
     * e.g., "Thlemaxou" (th=η, x=χ, ou=υ) matches "Tilemachou" (ch=χ, ou=υ),
     * and "Elaiwn" (ai=αι, w=ω) matches "Eleon".</p>
     */
    boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;
        if ("UNKNOWN".equals(a) || "UNKNOWN".equals(b)) return false;

        String normA = normalize(a);
        String normB = normalize(b);

        if (normA.equals(normB)) return true;

        // Catches abbreviations like "P Tsaldari" ⊂ "Panagi Tsaldari"
        if (normA.contains(normB) || normB.contains(normA)) return true;

        // Last word often the most distinctive part of Greek street names
        String lastA = normA.contains(" ") ? normA.substring(normA.lastIndexOf(' ') + 1) : normA;
        String lastB = normB.contains(" ") ? normB.substring(normB.lastIndexOf(' ') + 1) : normB;
        if (levenshteinDistance(lastA, lastB) <= 2) return true;

        // Proportional edit distance: allow ~30% character differences
        int maxAllowed = Math.max(2, (int) (Math.min(normA.length(), normB.length()) * 0.3));
        return levenshteinDistance(normA, normB) <= maxAllowed;
    }

    /**
     * Lowercases and maps common Greek transliteration variants to a canonical form
     * so that different romanizations of the same Greek street name compare equal.
     *
     * <p>Multi-character substitutions are applied before single-character ones to
     * avoid double-substitution (e.g., "ch" must be mapped before "c" or "h" separately).</p>
     */
    private String normalize(String s) {
        String r = s.trim().toLowerCase().replaceAll("\\s+", " ");
        // Multi-char variants (apply first)
        r = r.replace("ou", "u");   // υ / ου → u
        r = r.replace("ch", "k");   // χ → k
        r = r.replace("th", "t");   // θ / η-as-th → t
        r = r.replace("ph", "f");   // φ → f
        r = r.replace("ai", "e");   // αι → e
        r = r.replace("ei", "i");   // ει → i
        r = r.replace("oi", "i");   // οι → i
        // Single-char variants
        r = r.replace("x", "k");    // χ written as x → k
        r = r.replace("w", "o");    // ω written as w → o
        return r;
    }

    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[len1][len2];
    }
}