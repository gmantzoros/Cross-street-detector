package gr.crossstreet;

import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch evaluator for the cross-street detection algorithm.
 *
 * <p>Reads test cases from a CSV file, runs the detector on each one, and classifies results
 * as PASS, FAIL, or ERROR. Implements the iterative forward simulation mechanism described
 * in the paper (Section 5.2): when the detected road matches the current road (i.e., the
 * cross-street is still too far), the user position is projected 25m forward along the bearing
 * and the detection is retried up to 3 times (75m total).</p>
 *
 * <p>This eliminates the intermediate ACCEPTABLE category — all results are resolved
 * into either PASS or FAIL.</p>
 */
public class BatchEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BatchEvaluator.class);

    /** Distance in meters to move forward on each retry iteration. */
    private static final double FORWARD_STEP_METERS = 25.0;

    /** Maximum number of forward retries before marking an acceptable result as FAIL. */
    private static final int MAX_RETRIES = 3;

    public enum Outcome { PASS, FAIL, ERROR }

    /**
     * Encapsulates the evaluation result for a single test case.
     *
     * @param testCase       the original test case
     * @param outcome        PASS, FAIL, or ERROR
     * @param detectedRoad   the road name returned by the detector
     * @param iterationsUsed number of forward iterations needed (0 = immediate success)
     * @param errorMessage   error details if outcome is ERROR, null otherwise
     */
    public record EvalResult(
            TestCase testCase,
            Outcome outcome,
            String detectedRoad,
            int iterationsUsed,
            String errorMessage
    ) {
    }

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test-data.csv";
        String outputPath = args.length >= 2 ? args[1] : "evaluation-results.csv";

        BatchEvaluator evaluator = new BatchEvaluator();
        List<TestCase> testCases = evaluator.loadCsv(Path.of(inputPath));
        List<EvalResult> results = evaluator.runAll(testCases);
        evaluator.printReport(results);
        evaluator.writeCsv(results, Path.of(outputPath));
    }

    /**
     * Reads the CSV file and parses each row into a TestCase.
     * Supports both comma and semicolon delimiters (Greek/EU locale exports with semicolons).
     * Expects columns: Previous Coordinates, Current Coordinates, Current Road, Target Road, Result, City.
     *
     * @param csvPath path to the input CSV file
     * @return list of parsed test cases
     */
    public List<TestCase> loadCsv(Path csvPath) {
        List<TestCase> testCases = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty");
            }

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    TestCase tc = parseLine(line, rowNumber);
                    testCases.add(tc);
                } catch (Exception e) {
                    log.warn("Skipping row {}: {}", rowNumber, e.getMessage());
                }
                rowNumber++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CSV: " + csvPath, e);
        }

        log.info("Loaded {} test cases from {}", testCases.size(), csvPath);
        return testCases;
    }

    /**
     * Parses a single CSV line into a TestCase.
     * Auto-detects semicolon vs comma delimiter.
     *
     * @param line      the raw CSV line
     * @param rowNumber the 1-based row number for logging
     * @return the parsed TestCase
     * @throws IllegalArgumentException if the line cannot be parsed
     */
    private TestCase parseLine(String line, int rowNumber) {
        String delimiter;
        if (line.contains(";")) {
            delimiter = ";";
        } else {
            delimiter = ",";
        }

        String[] parts = line.split(delimiter);

        if (parts.length >= 4) {
            String prevCoords = parts[0].trim();
            String currCoords = parts[1].trim();
            String currentRoad = parts[2].trim();
            String targetRoad = parts[3].trim();
            String city = parts.length >= 6 ? parts[5].trim() : "";

            return new TestCase(rowNumber,
                    GeoPoint.parse(prevCoords),
                    GeoPoint.parse(currCoords),
                    currentRoad, targetRoad, city);
        }

        throw new IllegalArgumentException("Could not parse line: " + line);
    }

    /**
     * Runs the detector on all test cases with iterative forward simulation.
     *
     * <p>For each test case, the detector is called. If the result matches the target road,
     * it's a PASS. If it matches the current road (user hasn't reached the cross-street yet),
     * the position is moved 25m forward and the detection is retried up to 3 times.
     * Any other result is an immediate FAIL.</p>
     *
     * @param testCases the list of test cases to evaluate
     * @return the list of evaluation results
     */
    public List<EvalResult> runAll(List<TestCase> testCases) {
        CrossStreetDetectorApp app = new CrossStreetDetectorApp();
        List<EvalResult> results = new ArrayList<>();

        for (TestCase tc : testCases) {
            log.info("========== Test #{} | Target: {} | Current Road: {} ==========",
                    tc.rowNumber(), tc.targetRoad(), tc.currentRoad());

            try {
                EvalResult result = evaluateWithRetries(app, tc);
                results.add(result);
            } catch (Exception e) {
                log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage());
                results.add(new EvalResult(tc, Outcome.ERROR, "", 0, e.getMessage()));
            }

            // Small delay to avoid hitting Google API rate limits
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return results;
    }

    /**
     * Evaluates a single test case with the iterative forward simulation mechanism.
     *
     * <p>As described in the paper (Section 5.2), when the algorithm returns the current road
     * instead of the cross-street, it means the intersection is still too far ahead. The user's
     * position is projected 25m forward along their bearing and the detection is retried.
     * This repeats up to {@value MAX_RETRIES} times (75m total forward).</p>
     *
     * <p>Possible outcomes per iteration:</p>
     * <ul>
     *   <li>Detected road matches target road → PASS (stop)</li>
     *   <li>Detected road matches current road → move forward 25m and retry</li>
     *   <li>Detected road is something else entirely → FAIL (stop)</li>
     * </ul>
     *
     * @param app the detector application instance
     * @param tc  the test case to evaluate
     * @return the evaluation result
     * @throws Exception if the detector encounters an unrecoverable error
     */
    private EvalResult evaluateWithRetries(CrossStreetDetectorApp app, TestCase tc) throws Exception {
        // Calculate the bearing for forward projection
        double bearing = GeoUtils.calculateBearing(tc.previousCoords(), tc.currentCoords());

        GeoPoint currentPos = tc.currentCoords();
        GeoPoint previousPos = tc.previousCoords();

        for (int iteration = 0; true; iteration++) {
            String iterLabel = iteration == 0
                    ? "Initial"
                    : "Retry #" + iteration + " (+" + (iteration * 25) + "m)";

            DetectionResult detection = app.detect(currentPos, previousPos);
            String detectedRoad = detection.roadName().orElse("UNKNOWN");

            if (fuzzyMatch(detectedRoad, tc.targetRoad())) {
                log.info("#{} | {} | PASS | Target: {} | Got: {}",
                        tc.rowNumber(), iterLabel, tc.targetRoad(), detectedRoad);
                return new EvalResult(tc, Outcome.PASS, detectedRoad, iteration, null);
            }

            if (fuzzyMatch(detectedRoad, tc.currentRoad())) {
                if (iteration < MAX_RETRIES) {
                    log.info("#{} | {} | CURRENT ROAD detected ('{}') — moving forward 25m and retrying...",
                            tc.rowNumber(), iterLabel, detectedRoad);

                    // Move forward 25m along the bearing
                    previousPos = currentPos;
                    currentPos = GeoUtils.projectPoint(currentPos, FORWARD_STEP_METERS, bearing);

                    continue;
                } else {
                    // Exhausted all retries, still getting current road
                    log.warn("#{} | {} | FAIL (exhausted {} retries, still detecting current road '{}')",
                            tc.rowNumber(), iterLabel, MAX_RETRIES, detectedRoad);
                    return new EvalResult(tc, Outcome.FAIL, detectedRoad, iteration, "Exhausted retries");
                }
            }

            // Neither target nor current road — immediate failure
            log.warn("#{} | {} | FAIL | Target: {} | Got: {} (wrong road)",
                    tc.rowNumber(), iterLabel, tc.targetRoad(), detectedRoad);
            return new EvalResult(tc, Outcome.FAIL, detectedRoad, iteration, null);
        }
    }

    /**
     * Matches road names accounting for:
     * <ul>
     *   <li>Case differences</li>
     *   <li>Greek transliteration variations (up to ~30% character differences)</li>
     *   <li>Abbreviations ("P Tsaldari" vs "Panagi Tsaldari")</li>
     *   <li>Last-word matching ("Dim Gounari" vs "Dimitriou Gounari")</li>
     * </ul>
     *
     * @param a first road name
     * @param b second road name
     * @return true if the names are considered a match
     */
    private boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;
        String normA = a.trim().toLowerCase().replaceAll("\\s+", " ");
        String normB = b.trim().toLowerCase().replaceAll("\\s+", " ");

        // Exact match
        if (normA.equals(normB)) return true;

        // One contains the other (catches abbreviations like "Dim Gounari" vs "Dimitriou Gounari")
        if (normA.contains(normB) || normB.contains(normA)) return true;

        // Check if last word matches (key part of Greek street names)
        String lastA = normA.contains(" ") ? normA.substring(normA.lastIndexOf(' ') + 1) : normA;
        String lastB = normB.contains(" ") ? normB.substring(normB.lastIndexOf(' ') + 1) : normB;
        if (levenshteinDistance(lastA, lastB) <= 2) return true;

        // Proportional Levenshtein: allow up to 30% of the shorter string's length
        int maxAllowed = Math.max(2, (int) (Math.min(normA.length(), normB.length()) * 0.3));
        return levenshteinDistance(normA, normB) <= maxAllowed;
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     * This counts the minimum number of single-character edits (insertions,
     * deletions, substitutions) needed to transform one string into the other.
     *
     * @param s1 first string
     * @param s2 second string
     * @return the edit distance
     */
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

    /**
     * Prints a formatted summary report to the console, including per-case results
     * and aggregate statistics. PASS results from retries show the forward distance used.
     *
     * @param results the list of evaluation results
     */
    public void printReport(List<EvalResult> results) {
        int total = results.size();
        long pass = results.stream().filter(r -> r.outcome == Outcome.PASS).count();
        long passImmediate = results.stream().filter(r -> r.outcome == Outcome.PASS && r.iterationsUsed == 0).count();
        long passRetried = results.stream().filter(r -> r.outcome == Outcome.PASS && r.iterationsUsed > 0).count();
        long fail = results.stream().filter(r -> r.outcome == Outcome.FAIL).count();
        long error = results.stream().filter(r -> r.outcome == Outcome.ERROR).count();

        System.out.println();
        System.out.println("=".repeat(85));
        System.out.println("  EVALUATION RESULTS");
        System.out.println("=".repeat(85));

        for (EvalResult r : results) {
            String icon = switch (r.outcome) {
                case PASS -> r.iterationsUsed == 0 ? "PASS       " : "PASS (+" + (r.iterationsUsed * 25) + "m) ";
                case FAIL -> "FAIL       ";
                case ERROR -> "ERROR      ";
            };
            System.out.printf("  #%03d | %s | Target: %-25s | Got: %s%n",
                    r.testCase.rowNumber(), icon, r.testCase.targetRoad(), r.detectedRoad);
        }

        System.out.println();
        System.out.println("=".repeat(85));
        System.out.println("  SUMMARY");
        System.out.println("=".repeat(85));
        System.out.printf("  PASS (immediate): %d/%d%n", passImmediate, total);
        System.out.printf("  PASS (retried):   %d/%d%n", passRetried, total);
        System.out.printf("  PASS (total):     %d/%d%n", pass, total);
        System.out.printf("  FAIL:             %d/%d%n", fail, total);
        System.out.printf("  ERROR:            %d/%d%n", error, total);
        System.out.printf("  Success rate:     %.1f%%%n", (pass * 100.0) / total);
        System.out.println("=".repeat(85));
    }

    /**
     * Writes evaluation results to a semicolon-delimited CSV file compatible with
     * Greek-locale Excel. Includes an Iterations column showing how many forward
     * steps were needed for each test case.
     *
     * @param results    the list of evaluation results
     * @param outputPath path to the output CSV file
     */
    public void writeCsv(List<EvalResult> results, Path outputPath) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            writer.println("Row;Previous Coordinates;Current Coordinates;Current Road;Target Road;Detected Road;Result;Iterations;City");

            for (EvalResult r : results) {
                writer.printf("%d;\"%s\";\"%s\";%s;%s;%s;%s;%d;%s%n",
                        r.testCase.rowNumber(),
                        r.testCase.previousCoords().toApiString(),
                        r.testCase.currentCoords().toApiString(),
                        r.testCase.currentRoad(),
                        r.testCase.targetRoad(),
                        r.detectedRoad,
                        r.outcome,
                        r.iterationsUsed,
                        r.testCase.city());
            }

            log.info("Results written to {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to write results CSV: {}", e.getMessage(), e);
        }
    }
}