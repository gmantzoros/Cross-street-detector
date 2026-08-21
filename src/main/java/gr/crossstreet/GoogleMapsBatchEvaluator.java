package gr.crossstreet;

import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch runner for the Google Maps benchmark dataset ({@code test-data_googlemaps.csv}).
 *
 * <p>Same pipeline, report and output format as {@link BatchEvaluator} — only the input
 * parsing differs. That dataset is semicolon-delimited, carries an extra "Current Road"
 * column before the target, and annotates road names in Latin transliteration rather than
 * Greek script. Cross-script matching is handled by
 * {@link gr.crossstreet.util.RoadNameMatcher#fuzzyMatch}.</p>
 */
public class GoogleMapsBatchEvaluator extends BatchEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsBatchEvaluator.class);

    private static final int COL_PREV = 0;
    private static final int COL_CURR = 1;
    private static final int COL_TARGET = 3;
    private static final int COL_CITY = 5;

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test-data_googlemaps.csv";
        String outputPath = args.length >= 2 ? args[1] : "results/evaluation-results_googlemaps.csv";

        GoogleMapsBatchEvaluator evaluator = new GoogleMapsBatchEvaluator();
        List<TestCase> testCases = evaluator.loadCsv(Path.of(inputPath));
        List<EvaluationEngine.EvalResult> results = evaluator.runAll(testCases);
        evaluator.printReport(results);
        evaluator.writeCsv(results, Path.of(outputPath));
    }

    /**
     * Reads the semicolon-delimited Google Maps CSV.
     * Expects columns: Previous Coordinates, Current Coordinates, Current Road, Target Road,
     * Result, City. The "Current Road" and "Result" columns are ignored — the detector
     * auto-detects the current road.
     */
    @Override
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
        // Coordinates contain commas, but the delimiter is a semicolon, so a plain split works.
        String[] parts = line.split(";", -1);

        if (parts.length <= COL_TARGET) {
            throw new IllegalArgumentException("Could not parse line: " + line);
        }

        String targetRoad = strip(parts[COL_TARGET]);
        if (targetRoad.isEmpty()) {
            throw new IllegalArgumentException("Missing target road: " + line);
        }

        String city = parts.length > COL_CITY ? strip(parts[COL_CITY]) : "";

        return new TestCase(rowNumber,
                GeoPoint.parse(strip(parts[COL_PREV])),
                GeoPoint.parse(strip(parts[COL_CURR])),
                targetRoad, city);
    }

    private static String strip(String field) {
        String s = field.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
