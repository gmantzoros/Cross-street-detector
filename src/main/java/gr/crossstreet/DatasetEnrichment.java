package gr.crossstreet;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.geo.IntersectionDetector;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.TestCase;
import gr.crossstreet.util.RoadNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * One-shot analysis pass that enriches the 500-case dataset with two quantities that the
 * plain PASS/FAIL evaluation does not record:
 *
 * <ol>
 *   <li><b>Cross-street distance</b> — the metric distance from the user's current position to the
 *       detected forward intersection ({@link DetectionResult#distanceMeters()}).</li>
 *   <li><b>Road-type (highway class)</b> — the OSM {@code highway} tag of the <em>target</em>
 *       cross-street way and of the <em>detected</em> cross-street way, resolved from the same
 *       Overpass data the detector used for that case.</li>
 * </ol>
 *
 * <p>It runs the full geometry-based detector over every case exactly as {@link BatchEvaluator}
 * does (same 3 s inter-query delay to respect the public Overpass rate limit), so a complete run
 * takes roughly an hour. Per-case rows are written to a semicolon-delimited CSV, and aggregate
 * distributions (road-type counts and a distance histogram over the PASS cases) are printed to the
 * console and appended to the CSV as comment lines.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.DatasetEnrichment \
 *      [input-annotation.csv] [output-enrichment.csv]
 * </pre>
 * Defaults: {@code src/main/resources/test_data_annotation_all.csv} and
 * {@code results/dataset-enrichment.csv}.
 */
public class DatasetEnrichment {

    private static final Logger log = LoggerFactory.getLogger(DatasetEnrichment.class);

    private static final long OVERPASS_DELAY_MS = 3000;
    private static final String UNKNOWN = "UNKNOWN";

    /** One enriched row of analysis output. */
    private record EnrichedRow(
            int row,
            String city,
            String targetRoad,
            String targetHighway,
            String detectedRoad,
            String detectedHighway,
            String result,        // PASS / FAIL / ERROR
            double distanceMeters,
            double bearing,
            String note
    ) {}

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test_data_annotation_all.csv";
        String outputPath = args.length >= 2 ? args[1] : "results/dataset-enrichment.csv";

        DatasetEnrichment enrichment = new DatasetEnrichment();
        List<TestCase> cases = new BatchEvaluator().loadCsv(Path.of(inputPath));
        List<EnrichedRow> rows = enrichment.runAll(cases);
        enrichment.writeCsv(rows, Path.of(outputPath));
        enrichment.printSummary(rows);
        log.info("Enrichment complete: {} cases -> {}", rows.size(), outputPath);
    }

    /** Runs the detector over every case, collecting distance and highway-type information. */
    private List<EnrichedRow> runAll(List<TestCase> cases) {
        CrossStreetDetectorApp app = new CrossStreetDetectorApp();
        List<EnrichedRow> rows = new ArrayList<>();

        int i = 0;
        for (TestCase tc : cases) {
            i++;
            log.info("========== [{}/{}] #{} | {} | Target: {} ==========",
                    i, cases.size(), tc.rowNumber(), tc.city(), tc.targetRoad());

            EnrichedRow row = evaluateOne(app, tc);
            rows.add(row);
            log.info("#{} | {} | dist={} m | targetHwy={} | detectedHwy={} | got='{}'",
                    tc.rowNumber(), row.result(),
                    String.format(Locale.US, "%.1f", row.distanceMeters()),
                    row.targetHighway(), row.detectedHighway(), row.detectedRoad());

            sleep(OVERPASS_DELAY_MS);
        }
        return rows;
    }

    /** Evaluates a single case; never throws — failures become ERROR rows. */
    private EnrichedRow evaluateOne(CrossStreetDetectorApp app, TestCase tc) {
        try {
            DetectionResult detection = app.detect(tc.currentCoords(), tc.previousCoords());
            String detected = detection.roadName().orElse(UNKNOWN);

            OverpassClient.OverpassData roadData = app.getLastRoadData();
            String targetHwy = highwayTypeForName(roadData, tc.targetRoad());
            String detectedHwy = detection.roadName().isPresent()
                    ? highwayTypeForName(roadData, detected)
                    : UNKNOWN;

            boolean pass = RoadNameMatcher.fuzzyMatch(detected, tc.targetRoad());
            String result = pass ? "PASS" : "FAIL";

            return new EnrichedRow(tc.rowNumber(), tc.city(), tc.targetRoad(), targetHwy,
                    detected, detectedHwy, result,
                    detection.distanceMeters(), detection.bearingToIntersection(), "");
        } catch (Exception e) {
            log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage());
            return new EnrichedRow(tc.rowNumber(), tc.city(), tc.targetRoad(), UNKNOWN,
                    "", UNKNOWN, "ERROR", 0.0, 0.0, sanitize(e.getMessage()));
        }
    }

    /**
     * Resolves the OSM {@code highway} class of the way whose name fuzzy-matches {@code name},
     * from the given Overpass data. Returns the most frequent highway value among matching ways
     * (a single physical road is often split into several ways sharing one class), or UNKNOWN if
     * no named way matches.
     */
    private static String highwayTypeForName(OverpassClient.OverpassData roadData, String name) {
        if (roadData == null || name == null || name.isBlank() || UNKNOWN.equals(name)) {
            return UNKNOWN;
        }
        Map<String, Integer> highwayVotes = new HashMap<>();
        for (OverpassClient.OsmWay way : roadData.ways()) {
            String wayName = IntersectionDetector.resolveRoadName(way.tags());
            if (wayName == null) continue;
            if (!RoadNameMatcher.fuzzyMatch(wayName, name)) continue;
            String hwy = way.tags().get("highway");
            if (hwy != null && !hwy.isBlank()) {
                highwayVotes.merge(hwy, 1, Integer::sum);
            }
        }
        return highwayVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(UNKNOWN);
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private void writeCsv(List<EnrichedRow> rows, Path outputPath) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            log.warn("Could not create output directory: {}", e.getMessage());
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            w.println("Row;City;TargetRoad;TargetHighway;DetectedRoad;DetectedHighway;Result;DistanceMeters;Bearing;Note");
            for (EnrichedRow r : rows) {
                w.printf(Locale.US, "%d;%s;%s;%s;%s;%s;%s;%.1f;%.1f;%s%n",
                        r.row(), r.city(), r.targetRoad(), r.targetHighway(),
                        r.detectedRoad(), r.detectedHighway(), r.result(),
                        r.distanceMeters(), r.bearing(), r.note());
            }
            log.info("Per-case enrichment written to {}", outputPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write enrichment CSV", e);
        }
    }

    /** Prints the two requested distributions to the console. */
    private void printSummary(List<EnrichedRow> rows) {
        long total = rows.size();
        long pass = rows.stream().filter(r -> "PASS".equals(r.result())).count();
        long fail = rows.stream().filter(r -> "FAIL".equals(r.result())).count();
        long error = rows.stream().filter(r -> "ERROR".equals(r.result())).count();

        System.out.println("=".repeat(80));
        System.out.println("  ENRICHMENT SUMMARY");
        System.out.println("=".repeat(80));
        System.out.printf(Locale.US, "  Cases: %d | PASS: %d (%.1f%%) | FAIL: %d | ERROR: %d%n",
                total, pass, total > 0 ? pass * 100.0 / total : 0.0, fail, error);
        System.out.println();

        // --- Road-type distribution of the TARGET cross streets (ground truth) ---
        System.out.println("  TARGET CROSS-STREET ROAD TYPE (OSM highway class), all cases:");
        Map<String, Long> byHighway = rows.stream()
                .collect(Collectors.groupingBy(EnrichedRow::targetHighway, TreeMap::new, Collectors.counting()));
        byHighway.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf(Locale.US, "    %-18s %4d (%5.1f%%)%n",
                        e.getKey(), e.getValue(), e.getValue() * 100.0 / total));
        System.out.println();

        // --- Cross-street distance distribution over PASS cases (correct detections) ---
        double[] dist = rows.stream()
                .filter(r -> "PASS".equals(r.result()))
                .mapToDouble(EnrichedRow::distanceMeters)
                .filter(d -> d > 0)
                .sorted()
                .toArray();

        System.out.println("  CROSS-STREET DISTANCE from user (PASS cases only):");
        if (dist.length == 0) {
            System.out.println("    (no PASS cases with positive distance)");
        } else {
            System.out.printf(Locale.US, "    Min: %.1f m | Max: %.1f m | Mean: %.1f m | Median: %.1f m%n",
                    dist[0], dist[dist.length - 1],
                    Arrays.stream(dist).average().orElse(0), dist[dist.length / 2]);
            int[] bins = {0, 20, 40, 60, 80, 100, 120, 140, 160, 200};
            for (int i = 0; i < bins.length - 1; i++) {
                int lo = bins[i], hi = bins[i + 1];
                long c = Arrays.stream(dist).filter(d -> d >= lo && d < hi).count();
                String bar = "#".repeat((int) (c * 40.0 / dist.length));
                System.out.printf(Locale.US, "    %3d-%3d m: %3d (%5.1f%%) %s%n",
                        lo, hi, c, c * 100.0 / dist.length, bar);
            }
            long above = Arrays.stream(dist).filter(d -> d >= bins[bins.length - 1]).count();
            System.out.printf(Locale.US, "    %3d+   m: %3d (%5.1f%%)%n",
                    bins[bins.length - 1], above, above * 100.0 / dist.length);
        }
        System.out.println("=".repeat(80));
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
