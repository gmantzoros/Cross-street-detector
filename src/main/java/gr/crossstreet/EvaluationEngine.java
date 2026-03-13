package gr.crossstreet;

import gr.crossstreet.image.DebugImageSaver;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates a single test case against the cross-street detector.
 *
 * <p>Simple pass/fail: if the detected road matches the target → PASS, otherwise → FAIL.</p>
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
     * @param detectedRoad   the road name that led to this outcome
     * @param iterationsUsed number of forward steps taken (0 = immediate)
     * @param errorMessage   error details if outcome is ERROR, null otherwise
     */
    public record EvalResult(
            TestCase testCase,
            Outcome outcome,
            String detectedRoad,
            int iterationsUsed,
            String errorMessage
    ) {}

    /**
     * Evaluates one test case, returning a result that is always PASS, FAIL, or ERROR —
     * never throws.
     */
    public EvalResult evaluate(CrossStreetDetectorApp app, TestCase tc) {
        try {
            return evaluateInternal(app, tc);
        } catch (Exception e) {
            log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage(), e);
            return new EvalResult(tc, Outcome.ERROR, "", 0, e.getMessage());
        }
    }

    private EvalResult evaluateInternal(CrossStreetDetectorApp app, TestCase tc) throws Exception {
        DetectionResult detection = app.detect(tc.currentCoords(), tc.previousCoords(), tc.currentRoad());
        String detected = detection.roadName().orElse("UNKNOWN");

        log.info("#{} | detected='{}'", tc.rowNumber(), detected);

        if (fuzzyMatch(detected, tc.targetRoad())) {
            log.info("#{} | PASS | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), detected);
            return new EvalResult(tc, Outcome.PASS, detected, 0, null);
        }

        log.warn("#{} | FAIL | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), detected);
        if (debugImageSaver != null) {
            debugImageSaver.save(tc, detection, app.getLastRoadData(), app.getLastIntersections());
        }
        return new EvalResult(tc, Outcome.FAIL, detected, 0, "Wrong road");
    }

    /**
     * Fuzzy road name match handling Greek transliteration variants, abbreviations,
     * and last-word suffix matching.
     */
    boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;
        if ("UNKNOWN".equals(a) || "UNKNOWN".equals(b)) return false;

        String normA = normalize(a);
        String normB = normalize(b);

        if (normA.equals(normB)) return true;

        // Catches abbreviations like "P Tsaldari" ⊂ "Panagi Tsaldari"
        if (normA.contains(normB) || normB.contains(normA)) return true;

        // Abbreviation match: "K Palama" → "Kosti Palama"
        if (abbreviationMatch(normA, normB)) return true;

        // Last word often the most distinctive part of Greek street names
        String lastA = normA.contains(" ") ? normA.substring(normA.lastIndexOf(' ') + 1) : normA;
        String lastB = normB.contains(" ") ? normB.substring(normB.lastIndexOf(' ') + 1) : normB;
        if (levenshteinDistance(lastA, lastB) <= 2) return true;

        // Proportional edit distance: allow ~30% character differences
        int maxAllowed = Math.max(2, (int) (Math.min(normA.length(), normB.length()) * 0.3));
        return levenshteinDistance(normA, normB) <= maxAllowed;
    }

    /**
     * Lowercases and maps common Greek transliteration variants to a canonical form.
     */
    private String normalize(String s) {
        String r = s.trim().toLowerCase().replaceAll("\\s+", " ");
        // Multi-char: digraphs first (order matters to avoid double-substitution)
        r = r.replace("mp", "b");
        r = r.replace("mb", "b");
        r = r.replace("nt", "d");
        r = r.replace("gk", "g");
        r = r.replace("ou", "u");
        r = r.replace("ch", "k");
        r = r.replace("th", "t");
        r = r.replace("ph", "f");
        r = r.replace("ai", "e");
        r = r.replace("ei", "i");
        r = r.replace("oi", "i");
        // Single-char
        r = r.replace("j", "i");
        r = r.replace("y", "i");
        r = r.replace("x", "k");
        r = r.replace("w", "o");
        return r;
    }

    /**
     * Checks if one name is an abbreviation of the other.
     * Matches when single-letter words in one name are prefixes of
     * corresponding words in the other (e.g., "k palama" matches "kosti palama").
     */
    private boolean abbreviationMatch(String a, String b) {
        String[] wordsA = a.split(" ");
        String[] wordsB = b.split(" ");
        return abbreviationMatchDirectional(wordsA, wordsB) || abbreviationMatchDirectional(wordsB, wordsA);
    }

    private boolean abbreviationMatchDirectional(String[] shorter, String[] longer) {
        if (shorter.length >= longer.length) return false;
        int li = 0;
        for (String sw : shorter) {
            boolean matched = false;
            while (li < longer.length) {
                if (longer[li].startsWith(sw)) {
                    matched = true;
                    li++;
                    break;
                }
                li++;
            }
            if (!matched) return false;
        }
        return true;
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
