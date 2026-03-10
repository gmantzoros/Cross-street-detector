package gr.crossstreet;

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

public class BatchEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BatchEvaluator.class);

    public enum Outcome { PASS, ACCEPTABLE, FAIL, ERROR }

    public record EvalResult(TestCase testCase, Outcome outcome, String detectedRoad, String errorMessage) {
    }

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test-data.csv";
        String outputPath = args.length >= 2 ? args[1] : "results/evaluation-results.csv";

        BatchEvaluator evaluator = new BatchEvaluator();
        List<TestCase> testCases = evaluator.loadCsv(Path.of(inputPath));
        List<EvalResult> results = evaluator.runAll(testCases);
        evaluator.printReport(results);
        evaluator.writeCsv(results, Path.of(outputPath));
    }

    /**
     * Reads the CSV file and parses each row into a TestCase.
     * Expects columns: Previous Coordinates, Current Coordinates, Current Road, Target Road, Result, City
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

    private TestCase parseLine(String line, int rowNumber) {
        // Detect delimiter: semicolon (Greek/EU locale) or comma
        String delimiter;
        if (line.contains(";")) {
            delimiter = ";";
        } else {
            delimiter = ",";
        }

        String[] parts = line.split(delimiter);

        // Expected: PreviousCoords ; CurrentCoords ; CurrentRoad ; TargetRoad ; Result ; City
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

    private String[] parseQuotedCsv(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    /**
     * Runs the detector on all test cases and classifies each result.
     */
    public List<EvalResult> runAll(List<TestCase> testCases) {
        CrossStreetDetectorApp app = new CrossStreetDetectorApp();
        List<EvalResult> results = new ArrayList<>();

        for (TestCase tc : testCases) {
            log.info("=== Test #{} | Target: {} | Current: {} ===",
                    tc.rowNumber(), tc.targetRoad(), tc.currentRoad());

            try {
                DetectionResult detection = app.detect(tc.currentCoords(), tc.previousCoords());
                String detectedRoad = detection.roadName().orElse("UNKNOWN");

                Outcome outcome = classify(detectedRoad, tc.targetRoad(), tc.currentRoad());
                results.add(new EvalResult(tc, outcome, detectedRoad, null));

                String icon = switch (outcome) {
                    case PASS -> "PASS";
                    case ACCEPTABLE -> "ACCEPTABLE";
                    case FAIL -> "FAIL";
                    case ERROR -> "ERROR";
                };
                log.info("#{} | {} | Target: {} | Got: {}",
                        tc.rowNumber(), icon, tc.targetRoad(), detectedRoad);

            } catch (Exception e) {
                log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage());
                results.add(new EvalResult(tc, Outcome.ERROR, "", e.getMessage()));
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
     * Classifies the detection result:
     * - PASS: detected road matches the target road (within tolerance)
     * - ACCEPTABLE: detected road matches the current road
     * - FAIL: neither
     */
    private Outcome classify(String detected, String target, String currentRoad) {
        if (fuzzyMatch(detected, target)) {
            return Outcome.PASS;
        } else if (fuzzyMatch(detected, currentRoad)) {
            return Outcome.ACCEPTABLE;
        } else {
            return Outcome.FAIL;
        }
    }

    /**
     * Matches road names accounting for:
     * - Case differences
     * - Greek transliteration variations (up to ~30% character differences)
     * - Abbreviations ("P Tsaldari" vs "Panagi Tsaldari")
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

        // Proportional Levenshtein: allow up to 35% of the shorter string's length
        int maxAllowed = Math.max(2, (int) (Math.min(normA.length(), normB.length()) * 0.35));
        return levenshteinDistance(normA, normB) <= maxAllowed;
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
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
     * Prints a formatted summary report to the console.
     */
    public void printReport(List<EvalResult> results) {
        int total = results.size();
        long pass = results.stream().filter(r -> r.outcome == Outcome.PASS).count();
        long acceptable = results.stream().filter(r -> r.outcome == Outcome.ACCEPTABLE).count();
        long fail = results.stream().filter(r -> r.outcome == Outcome.FAIL).count();
        long error = results.stream().filter(r -> r.outcome == Outcome.ERROR).count();

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("  EVALUATION RESULTS");
        System.out.println("=".repeat(70));

        for (EvalResult r : results) {
            String icon = switch (r.outcome) {
                case PASS -> "PASS       ";
                case ACCEPTABLE -> "ACCEPTABLE ";
                case FAIL -> "FAIL       ";
                case ERROR -> "ERROR      ";
            };
            System.out.printf("  #%03d | %s | Target: %-25s | Got: %s%n",
                    r.testCase.rowNumber(), icon, r.testCase.targetRoad(), r.detectedRoad);
        }

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("  SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("  PASS:        %d/%d%n", pass, total);
        System.out.printf("  ACCEPTABLE:  %d/%d%n", acceptable, total);
        System.out.printf("  FAIL:        %d/%d%n", fail, total);
        System.out.printf("  ERROR:       %d/%d%n", error, total);
        System.out.printf("  Accuracy:    %.1f%%%n", (pass * 100.0) / total);
        System.out.printf("  Pass + Acc:  %.1f%%%n", ((pass + acceptable) * 100.0) / total);
        System.out.println("=".repeat(70));
    }

    /**
     * Writes results to a CSV file that can be opened in Excel.
     */
    public void writeCsv(List<EvalResult> results, Path outputPath) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            writer.println("Row,Previous Coordinates,Current Coordinates,Current Road,Target Road,Detected Road,Result,City");

            for (EvalResult r : results) {
                writer.printf("%d,\"%s\",\"%s\",%s,%s,%s,%s,%s%n",
                        r.testCase.rowNumber(),
                        r.testCase.previousCoords().toApiString(),
                        r.testCase.currentCoords().toApiString(),
                        r.testCase.currentRoad(),
                        r.testCase.targetRoad(),
                        r.detectedRoad,
                        r.outcome,
                        r.testCase.city());
            }

            log.info("Results written to {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to write results CSV: {}", e.getMessage(), e);
        }
    }
}