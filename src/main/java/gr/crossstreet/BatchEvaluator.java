package gr.crossstreet;

import gr.crossstreet.image.DebugImageSaver;
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
 * Batch runner for the cross-street detection algorithm.
 *
 * <p>Loads test cases from a CSV, delegates evaluation to {@link EvaluationEngine},
 * prints a summary report, and writes results to an output CSV.</p>
 */
public class BatchEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BatchEvaluator.class);

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test_data_annotation_all.csv";
    String outputPath = args.length >= 2 ? args[1] : "results/evaluation-results_all.csv";

        BatchEvaluator evaluator = new BatchEvaluator();
        List<TestCase> testCases = evaluator.loadCsv(Path.of(inputPath));
        List<EvaluationEngine.EvalResult> results = evaluator.runAll(testCases);
        evaluator.printReport(results);
        evaluator.writeCsv(results, Path.of(outputPath));
    }

    /**
     * Reads the CSV file and parses each row into a TestCase.
     * Expects columns: Previous Coordinates, Current Coordinates, Target Road, Google Maps Link, City.
     * Quoted fields (e.g., coordinates containing commas) are handled correctly.
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
                    testCases.add(parseLine(line, rowNumber));
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
        List<String> parts = parseCsvLine(line);

        if (parts.size() < 3) {
            throw new IllegalArgumentException("Could not parse line: " + line);
        }

        String prevCoords = parts.get(0);
        String currCoords = parts.get(1);
        String targetRoad = parts.get(2);
        String city = parts.size() >= 5 ? parts.get(4) : "";

        return new TestCase(rowNumber,
                GeoPoint.parse(prevCoords),
                GeoPoint.parse(currCoords),
                targetRoad, city);
    }

    /** Tokenizes one CSV line, respecting double-quoted fields. */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString().trim());
        return fields;
    }

    /**
     * Runs the detector on all test cases via {@link EvaluationEngine}.
     * Debug images are saved to {@code debug/} for any FAIL cases.
     */
    public List<EvaluationEngine.EvalResult> runAll(List<TestCase> testCases) {
        CrossStreetDetectorApp app = new CrossStreetDetectorApp();
        DebugImageSaver debugSaver = new DebugImageSaver();
        EvaluationEngine engine = new EvaluationEngine(debugSaver);
        List<EvaluationEngine.EvalResult> results = new ArrayList<>();

        for (TestCase tc : testCases) {
            log.info("========== Test #{} | Target: {} ==========",
                    tc.rowNumber(), tc.targetRoad());

            results.add(engine.evaluate(app, tc));

            // Delay to respect Overpass API rate limits (public server throttles at ~1 req/3s)
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return results;
    }

    /**
     * Prints a formatted summary to the console with per-case results and aggregate stats.
     */
    public void printReport(List<EvaluationEngine.EvalResult> results) {
        int total = results.size();
        long pass  = results.stream().filter(r -> r.outcome() == EvaluationEngine.Outcome.PASS).count();
        long fail  = results.stream().filter(r -> r.outcome() == EvaluationEngine.Outcome.FAIL).count();
        long error = results.stream().filter(r -> r.outcome() == EvaluationEngine.Outcome.ERROR).count();

        System.out.println();
        System.out.println("=".repeat(90));
        System.out.println("  EVALUATION RESULTS");
        System.out.println("=".repeat(90));

        for (EvaluationEngine.EvalResult r : results) {
            String icon = switch (r.outcome()) {
                case PASS  -> "PASS  ";
                case FAIL  -> "FAIL  ";
                case ERROR -> "ERROR ";
            };
            System.out.printf("  #%03d | %s | Target: %-25s | Got: %s%n",
                    r.testCase().rowNumber(), icon, r.testCase().targetRoad(), r.detectedRoad());
        }

        System.out.println();
        System.out.println("=".repeat(90));
        System.out.println("  SUMMARY");
        System.out.println("=".repeat(90));
        System.out.printf("  PASS:          %d/%d%n", pass, total);
        System.out.printf("  FAIL:          %d/%d%n", fail, total);
        System.out.printf("  ERROR:         %d/%d%n", error, total);
        System.out.printf("  Success rate:  %.1f%%%n", total > 0 ? (pass * 100.0) / total : 0.0);
        System.out.println("=".repeat(90));
    }

    /**
     * Writes results to a semicolon-delimited CSV compatible with Greek-locale Excel.
     */
    public void writeCsv(List<EvaluationEngine.EvalResult> results, Path outputPath) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("Could not create output directory: {}", e.getMessage());
        }

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            writer.println("Row;Previous Coordinates;Current Coordinates;Target Road;Detected Road;Result;City");

            for (EvaluationEngine.EvalResult r : results) {
                writer.printf("%d;\"%s\";\"%s\";%s;%s;%s;%s%n",
                        r.testCase().rowNumber(),
                        r.testCase().previousCoords().toApiString(),
                        r.testCase().currentCoords().toApiString(),
                        r.testCase().targetRoad(),
                        r.detectedRoad(),
                        r.outcome(),
                        r.testCase().city());
            }

            log.info("Results written to {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to write results CSV: {}", e.getMessage(), e);
        }
    }
}
