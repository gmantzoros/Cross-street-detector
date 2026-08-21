package gr.crossstreet;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.geo.OrientationEntropy;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.model.TestCase;
import gr.crossstreet.stats.Correlation;
import gr.crossstreet.stats.LogisticRegression;
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
 * Quantifies the claim made in §8.4 that detection accuracy varies inversely with the
 * irregularity of a city's street network, replacing the qualitative morphological labels
 * ("grid", "winding", "irregular") with a measured quantity.
 *
 * <p>Irregularity is operationalised as street-network <b>orientation order</b> φ
 * ({@link OrientationEntropy}), after Boeing (2019). The analysis runs at two levels:</p>
 *
 * <ol>
 *   <li><b>City level</b> (n = 6) — one φ per city, computed over the drivable network covering
 *       that city's sampled area, correlated against the city's success rate. This is the
 *       headline scatter, and it is deliberately reported with a bootstrap interval: six points
 *       cannot support a confident correlation estimate, and the interval says so.</li>
 *   <li><b>Case level</b> (n = 500) — a local φ computed in a {@value #DEFAULT_LOCAL_RADIUS_M} m
 *       window around each test case, entered as a predictor in a logistic regression of
 *       PASS/FAIL. This is the analysis that actually carries statistical weight, and it tests
 *       the hypothesis at the resolution the mechanism operates at: the crossing-angle filter
 *       (§6.8) sees one junction, not a city.</li>
 * </ol>
 *
 * <p>A second regression adds mean local segment length as a control. §8.4 offers two competing
 * explanations for Heraklion's low score — oblique crossing angles <em>and</em> short segments in
 * the dense core. They are confounded in the prose; entering both lets the data separate them.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.OrientationAnalysis \
 *      [results.csv] [annotation.csv] [localRadiusMeters]
 * </pre>
 * Defaults: {@code results/evaluation-results_all.csv},
 * {@code src/main/resources/test_data_annotation_all.csv}, {@value #DEFAULT_LOCAL_RADIUS_M} m.
 *
 * <p>Six Overpass queries are issued in total — one per city — and the per-case windows are cut
 * from that in-memory network rather than re-queried, so the run costs seconds, not the hour a
 * per-case fetch would take.</p>
 */
public class OrientationAnalysis {

    private static final Logger log = LoggerFactory.getLogger(OrientationAnalysis.class);

    /** Radius of the neighbourhood window used for per-case local orientation order. */
    static final int DEFAULT_LOCAL_RADIUS_M = 500;

    /** Margin added around a city's test-case bounding box before querying Overpass. */
    private static final int CITY_BBOX_BUFFER_M = 1000;

    /**
     * A local window with fewer segments than this is too sparse for its entropy to be
     * meaningful, and the case is dropped from the regression rather than fitted on noise.
     */
    private static final int MIN_LOCAL_SEGMENTS = 30;

    /**
     * Drivable-network filter, matching the {@code network_type="drive"} definition Boeing uses.
     * Service roads, alleys and pedestrian ways are excluded: they are numerous, short, and
     * oriented by parcel geometry rather than by the street plan, and including them washes out
     * the very signal being measured.
     */
    private static final String DRIVE_FILTER =
            "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street"
                    + "|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$";

    /**
     * Fraction of cases in the lowest tail of local orientation order, used to test whether the
     * relationship is a floor effect rather than a gradient.
     */
    private static final double LOW_ORDER_TAIL = 0.10;

    /** Per-city aggregate row. */
    private record CityRow(
            String city,
            int cases,
            int passes,
            double successRate,
            OrientationEntropy.Result orientation,
            double networkLengthKm,
            double meanLocalOrder,
            double meanSegmentLengthM
    ) {}

    /** Per-test-case row. */
    private record CaseRow(
            int row,
            String city,
            boolean passed,
            double localEntropy,
            double localOrder,
            int localSegments,
            double meanSegmentLengthM
    ) {}

    public static void main(String[] args) {
        String resultsPath = args.length >= 1 ? args[0] : "results/evaluation-results_all.csv";
        String annotationPath = args.length >= 2 ? args[1] : "src/main/resources/test_data_annotation_all.csv";
        int localRadius = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_LOCAL_RADIUS_M;

        new OrientationAnalysis().run(Path.of(resultsPath), Path.of(annotationPath), localRadius);
    }

    private void run(Path resultsPath, Path annotationPath, int localRadius) {
        List<TestCase> cases = new BatchEvaluator().loadCsv(annotationPath);
        Map<Integer, Boolean> outcomes = loadOutcomes(resultsPath);
        log.info("Loaded {} test cases and {} recorded outcomes", cases.size(), outcomes.size());

        Map<String, List<TestCase>> byCity = cases.stream()
                .collect(Collectors.groupingBy(TestCase::city, LinkedHashMap::new, Collectors.toList()));

        OverpassClient client = new OverpassClient(AppConfig.getInstance());
        List<CityRow> cityRows = new ArrayList<>();
        List<CaseRow> caseRows = new ArrayList<>();

        for (Map.Entry<String, List<TestCase>> entry : byCity.entrySet()) {
            String city = entry.getKey();
            List<TestCase> cityCases = entry.getValue();

            List<OverpassClient.OsmWay> network = fetchCityNetwork(client, city, cityCases);
            if (network.isEmpty()) {
                log.error("No drivable network returned for {} — skipping city", city);
                continue;
            }

            OrientationEntropy.Result cityOrientation = OrientationEntropy.compute(network);
            int passes = 0;
            List<CaseRow> cityCaseRows = new ArrayList<>();
            for (TestCase tc : cityCases) {
                Boolean passed = outcomes.get(tc.rowNumber());
                if (passed == null) {
                    log.warn("Row {} ({}) has no recorded outcome — skipping", tc.rowNumber(), city);
                    continue;
                }
                if (passed) passes++;
                cityCaseRows.add(localRow(tc, passed, network, localRadius));
            }
            caseRows.addAll(cityCaseRows);

            int scored = cityCaseRows.size();
            cityRows.add(new CityRow(city, scored, passes,
                    scored > 0 ? (double) passes / scored : 0.0,
                    cityOrientation, cityOrientation.totalLengthMeters() / 1000.0,
                    cityCaseRows.stream().mapToDouble(CaseRow::localOrder).average().orElse(Double.NaN),
                    cityCaseRows.stream().mapToDouble(CaseRow::meanSegmentLengthM).average().orElse(Double.NaN)));

            log.info("{}: phi={} H={} over {} km of drivable network, success {}/{}",
                    city,
                    String.format(Locale.US, "%.4f", cityOrientation.orientationOrder()),
                    String.format(Locale.US, "%.4f", cityOrientation.entropy()),
                    String.format(Locale.US, "%.1f", cityOrientation.totalLengthMeters() / 1000.0),
                    passes, scored);
        }

        writeCityCsv(cityRows, Path.of("results/orientation-city.csv"));
        writeCaseCsv(caseRows, Path.of("results/orientation-per-case.csv"), localRadius);
        writeBearingDistributions(cityRows, Path.of("results/orientation-bearing-distributions.csv"));

        report(cityRows, caseRows, localRadius);
        client.cache().logStatistics();
    }

    // -------------------------------------------------------------------------
    // Network retrieval
    // -------------------------------------------------------------------------

    /**
     * Fetches the drivable network covering a city's sampled area: the bounding box of its test
     * cases, expanded by {@value #CITY_BBOX_BUFFER_M} m so that windows around edge cases are not
     * truncated.
     *
     * <p>Bounding the query by the sample rather than by an administrative boundary is the more
     * defensible choice here. A municipal boundary would fold in districts no test case visits,
     * diluting φ with morphology the detector was never exercised on; the sampled area is the
     * network the success rate actually reflects.</p>
     */
    private List<OverpassClient.OsmWay> fetchCityNetwork(OverpassClient client, String city,
                                                         List<TestCase> cityCases) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (TestCase tc : cityCases) {
            for (GeoPoint p : List.of(tc.currentCoords(), tc.previousCoords())) {
                minLat = Math.min(minLat, p.latitude());
                maxLat = Math.max(maxLat, p.latitude());
                minLon = Math.min(minLon, p.longitude());
                maxLon = Math.max(maxLon, p.longitude());
            }
        }

        double latBuffer = CITY_BBOX_BUFFER_M / 111_320.0;
        double lonBuffer = CITY_BBOX_BUFFER_M
                / (111_320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2)));

        String query = String.format(Locale.US,
                "[out:json][timeout:180][maxsize:536870912];"
                        + "way[\"highway\"~\"%s\"][\"area\"!=\"yes\"](%f,%f,%f,%f);out geom qt;",
                DRIVE_FILTER,
                minLat - latBuffer, minLon - lonBuffer, maxLat + latBuffer, maxLon + lonBuffer);

        log.info("Fetching drivable network for {} ({}x{} km sampled area + {} m buffer)",
                city,
                String.format(Locale.US, "%.1f", (maxLat - minLat) * 111.32),
                String.format(Locale.US, "%.1f",
                        (maxLon - minLon) * 111.32 * Math.cos(Math.toRadians((minLat + maxLat) / 2))),
                CITY_BBOX_BUFFER_M);

        try {
            return client.query(query).ways();
        } catch (IOException e) {
            log.error("Overpass query failed for {}: {}", city, e.getMessage());
            return List.of();
        }
    }

    /** Computes the local orientation window around one test case. */
    private CaseRow localRow(TestCase tc, boolean passed,
                             List<OverpassClient.OsmWay> network, int localRadius) {
        GeoPoint center = tc.currentCoords();
        OrientationEntropy.Result local = OrientationEntropy.compute(network, center, localRadius);
        double meanSegmentLength = local.segmentCount() > 0
                ? local.totalLengthMeters() / local.segmentCount()
                : 0.0;
        return new CaseRow(tc.rowNumber(), tc.city(), passed,
                local.entropy(), local.orientationOrder(), local.segmentCount(), meanSegmentLength);
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    /**
     * Reads row number to PASS/FAIL from an evaluation results CSV
     * ({@code Row;Previous;Current;Target;Detected;Result;City}). ERROR rows are excluded:
     * they record a network failure, not a morphological one.
     */
    private Map<Integer, Boolean> loadOutcomes(Path resultsPath) {
        Map<Integer, Boolean> outcomes = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(resultsPath, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(";");
                if (parts.length < 6) continue;
                try {
                    int row = Integer.parseInt(parts[0].trim());
                    String result = parts[5].trim();
                    if ("PASS".equals(result) || "FAIL".equals(result)) {
                        outcomes.put(row, "PASS".equals(result));
                    }
                } catch (NumberFormatException e) {
                    // Header repeats or comment rows — skip quietly.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read evaluation results: " + resultsPath, e);
        }
        return outcomes;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private void writeCityCsv(List<CityRow> rows, Path path) {
        write(path, w -> {
            w.println("City;Cases;Passes;SuccessRate;Entropy;NormalisedEntropy;OrientationOrder;"
                    + "MeanLocalOrientationOrder;MeanSegmentLengthM;Segments;NetworkLengthKm");
            for (CityRow r : rows) {
                w.printf(Locale.US, "%s;%d;%d;%.4f;%.4f;%.4f;%.4f;%.4f;%.1f;%d;%.1f%n",
                        r.city(), r.cases(), r.passes(), r.successRate(),
                        r.orientation().entropy(), r.orientation().normalisedEntropy(),
                        r.orientation().orientationOrder(),
                        r.meanLocalOrder(), r.meanSegmentLengthM(),
                        r.orientation().segmentCount(), r.networkLengthKm());
            }
        });
    }

    private void writeCaseCsv(List<CaseRow> rows, Path path, int localRadius) {
        write(path, w -> {
            w.printf("# local window radius: %d m%n", localRadius);
            w.println("Row;City;Result;LocalEntropy;LocalOrientationOrder;LocalSegments;MeanSegmentLengthM");
            for (CaseRow r : rows) {
                w.printf(Locale.US, "%d;%s;%s;%.4f;%.4f;%d;%.1f%n",
                        r.row(), r.city(), r.passed() ? "PASS" : "FAIL",
                        r.localEntropy(), r.localOrder(), r.localSegments(), r.meanSegmentLengthM());
            }
        });
    }

    /** Emits the 36-bin length-weighted bearing distribution per city, for polar histograms. */
    private void writeBearingDistributions(List<CityRow> rows, Path path) {
        write(path, w -> {
            w.print("BinCenterDegrees");
            for (CityRow r : rows) w.print(";" + r.city());
            w.println();
            for (int bin = 0; bin < OrientationEntropy.BIN_COUNT; bin++) {
                w.printf(Locale.US, "%d", bin * 10);
                for (CityRow r : rows) {
                    w.printf(Locale.US, ";%.6f", r.orientation().binWeights()[bin]);
                }
                w.println();
            }
        });
    }

    private void write(Path path, java.util.function.Consumer<PrintWriter> body) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            log.warn("Could not create output directory: {}", e.getMessage());
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            body.accept(w);
            log.info("Wrote {}", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    private void report(List<CityRow> cityRows, List<CaseRow> caseRows, int localRadius) {
        System.out.println("=".repeat(96));
        System.out.println("  STREET-NETWORK ORIENTATION ORDER vs DETECTION ACCURACY");
        System.out.println("  phi after Boeing (2019); 1.0 = perfect orthogonal grid, 0.0 = no orientational order");
        System.out.println("=".repeat(96));

        // ---- City level ----
        System.out.printf(Locale.US, "%n  CITY LEVEL (n = %d)%n", cityRows.size());
        System.out.printf(Locale.US, "  %-14s %6s %8s %10s %10s %10s %10s%n",
                "City", "Cases", "Success", "H (nats)", "phi(city)", "phi(local)", "seg len m");
        cityRows.stream()
                .sorted(Comparator.comparingDouble(CityRow::successRate).reversed())
                .forEach(r -> System.out.printf(Locale.US, "  %-14s %6d %7.1f%% %10.4f %10.4f %10.4f %10.1f%n",
                        r.city(), r.cases(), r.successRate() * 100,
                        r.orientation().entropy(), r.orientation().orientationOrder(),
                        r.meanLocalOrder(), r.meanSegmentLengthM()));

        if (cityRows.size() >= 3) {
            double[] rate = cityRows.stream().mapToDouble(CityRow::successRate).toArray();

            System.out.println("\n  Success rate vs each morphological measure:");
            correlate("phi(city)  — one entropy over the whole sampled bbox",
                    cityRows.stream().mapToDouble(r -> r.orientation().orientationOrder()).toArray(), rate);
            correlate("phi(local) — mean of the per-case 500 m windows",
                    cityRows.stream().mapToDouble(CityRow::meanLocalOrder).toArray(), rate);
            correlate("mean segment length",
                    cityRows.stream().mapToDouble(CityRow::meanSegmentLengthM).toArray(), rate);

            System.out.printf(Locale.US,
                    "%n    NOTE: at n = %d every bootstrap interval below spans most of [-1, 1]. None of%n"
                            + "          these correlations is significant, and none can be. The city level is%n"
                            + "          descriptive only; the case-level model is the actual test.%n"
                            + "    NOTE: phi(city) and phi(local) disagree sharply. A single entropy over a 6 km%n"
                            + "          box averages districts whose grids point in different directions, and they%n"
                            + "          cancel; the local windows measure the geometry the detector actually sees.%n",
                    cityRows.size());
        }

        // ---- Case level ----
        List<CaseRow> usable = caseRows.stream()
                .filter(r -> r.localSegments() >= MIN_LOCAL_SEGMENTS)
                .toList();

        System.out.printf(Locale.US, "%n  CASE LEVEL (n = %d of %d; %d dropped for sparse local windows < %d segments)%n",
                usable.size(), caseRows.size(), caseRows.size() - usable.size(), MIN_LOCAL_SEGMENTS);
        System.out.printf(Locale.US, "  local window radius: %d m%n", localRadius);

        if (usable.size() < 30) {
            System.out.println("  Too few usable cases to fit a model.");
            System.out.println("=".repeat(96));
            return;
        }

        double[] passPhi = usable.stream().filter(CaseRow::passed)
                .mapToDouble(CaseRow::localOrder).toArray();
        double[] failPhi = usable.stream().filter(r -> !r.passed())
                .mapToDouble(CaseRow::localOrder).toArray();
        System.out.printf(Locale.US, "  mean local phi | PASS cases: %.4f (n=%d) | FAIL cases: %.4f (n=%d)%n",
                Arrays.stream(passPhi).average().orElse(Double.NaN), passPhi.length,
                Arrays.stream(failPhi).average().orElse(Double.NaN), failPhi.length);

        int[] outcomes = usable.stream().mapToInt(r -> r.passed() ? 1 : 0).toArray();

        // Decile profile: shows whether the effect is a gradient or a floor.
        System.out.println("\n  Pass rate by local-phi decile:");
        printDeciles(usable);

        System.out.println("\n  Model 1 — PASS ~ local orientation order");
        fitAndPrint(usable, outcomes, new String[]{"local phi"},
                r -> new double[]{r.localOrder()});

        System.out.println("  Model 2 — PASS ~ local orientation order + mean segment length");
        System.out.println("    (§8.4 attributes Heraklion's failures to oblique angles AND to short segments;");
        System.out.println("     entering both asks whether the data can tell the two explanations apart)");
        double collinearity = Correlation.pearson(
                usable.stream().mapToDouble(CaseRow::localOrder).toArray(),
                usable.stream().mapToDouble(CaseRow::meanSegmentLengthM).toArray()).coefficient();
        System.out.printf(Locale.US,
                "    CAUTION: the two predictors correlate at r = %.3f. Their individual coefficients%n"
                        + "             in this model are therefore unstable, and neither should be read as%n"
                        + "             outranking the other.%n", collinearity);
        fitAndPrint(usable, outcomes, new String[]{"local phi", "mean seg len (m)"},
                r -> new double[]{r.localOrder(), r.meanSegmentLengthM()});

        // The decile profile is flat above the bottom tail, so a linear term in phi is the wrong
        // functional form. Test the floor directly with an indicator variable.
        double threshold = quantile(usable.stream().mapToDouble(CaseRow::localOrder).toArray(), LOW_ORDER_TAIL);
        System.out.printf(Locale.US,
                "  Model 3 — PASS ~ [local phi in bottom %.0f%%]  (indicator, cut at phi = %.4f)%n",
                LOW_ORDER_TAIL * 100, threshold);
        System.out.println("    (tests a floor effect: severely disordered neighbourhoods hurt, and above");
        System.out.println("     that the network's orientation stops mattering)");
        fitAndPrint(usable, outcomes, new String[]{"phi in bottom decile"},
                r -> new double[]{r.localOrder() <= threshold ? 1.0 : 0.0});

        // Model 3 is defined on phi deciles. If the same floor appears when the tail is cut on
        // segment length instead, the threshold result is not specific to orientation — it marks
        // "finely divided neighbourhood" under either label. Model 4 runs that swap directly.
        double lengthThreshold =
                quantile(usable.stream().mapToDouble(CaseRow::meanSegmentLengthM).toArray(), LOW_ORDER_TAIL);
        System.out.printf(Locale.US,
                "  Model 4 — PASS ~ [mean segment length in bottom %.0f%%]  (indicator, cut at %.1f m)%n",
                LOW_ORDER_TAIL * 100, lengthThreshold);
        System.out.println("    (the same floor test as Model 3, with the tail cut on segment length");
        System.out.println("     instead of orientation order)");
        fitAndPrint(usable, outcomes, new String[]{"seg len in bottom decile"},
                r -> new double[]{r.meanSegmentLengthM() <= lengthThreshold ? 1.0 : 0.0});

        // How far do the two bottom deciles pick out the same cases?
        long inPhiTail = usable.stream().filter(r -> r.localOrder() <= threshold).count();
        long inLenTail = usable.stream().filter(r -> r.meanSegmentLengthM() <= lengthThreshold).count();
        long inBoth = usable.stream()
                .filter(r -> r.localOrder() <= threshold && r.meanSegmentLengthM() <= lengthThreshold)
                .count();
        long inEither = inPhiTail + inLenTail - inBoth;
        System.out.printf(Locale.US,
                "  Overlap of the two bottom deciles: %d cases in the phi tail, %d in the segment-length%n"
                        + "  tail, %d in both (Jaccard %.2f). %s%n%n",
                inPhiTail, inLenTail, inBoth, inEither > 0 ? (double) inBoth / inEither : 0.0,
                inBoth * 2.0 > inPhiTail
                        ? "They largely identify the same neighbourhoods."
                        : "They identify substantially different neighbourhoods.");

        System.out.printf(Locale.US,
                "  Model 5 — PASS ~ both indicators together (n in both tails = %d)%n", inBoth);
        System.out.println("    (asks whether either tail carries information the other does not)");
        fitAndPrint(usable, outcomes, new String[]{"phi in bottom decile", "seg len in bottom decile"},
                r -> new double[]{
                        r.localOrder() <= threshold ? 1.0 : 0.0,
                        r.meanSegmentLengthM() <= lengthThreshold ? 1.0 : 0.0});

        System.out.println("=".repeat(96));
    }

    /** Prints one correlation line, both Pearson and Spearman, for a city-level measure. */
    private void correlate(String label, double[] measure, double[] successRate) {
        System.out.printf(Locale.US, "    %-52s%n", label);
        System.out.println("      Pearson  " + Correlation.pearson(measure, successRate).format("r"));
        System.out.println("      Spearman " + Correlation.spearman(measure, successRate).format("rho"));
    }

    /** Pass rate in each tenth of the local-phi distribution. */
    private void printDeciles(List<CaseRow> rows) {
        List<CaseRow> sorted = rows.stream()
                .sorted(Comparator.comparingDouble(CaseRow::localOrder))
                .toList();
        int size = sorted.size() / 10;
        for (int d = 0; d < 10; d++) {
            int from = d * size;
            int to = d == 9 ? sorted.size() : (d + 1) * size;
            List<CaseRow> chunk = sorted.subList(from, to);
            long passes = chunk.stream().filter(CaseRow::passed).count();
            int bars = (int) Math.round(passes * 40.0 / chunk.size());
            System.out.printf(Locale.US, "    D%-2d  phi <= %.3f   %5.1f%%  (n=%3d)  %s%n",
                    d + 1, chunk.getLast().localOrder(),
                    passes * 100.0 / chunk.size(), chunk.size(), "#".repeat(bars));
        }
    }

    /** Linear-interpolated quantile of a sample. */
    private static double quantile(double[] values, double q) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, sorted.length - 1);
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    private void fitAndPrint(List<CaseRow> rows, int[] outcomes, String[] names,
                             java.util.function.Function<CaseRow, double[]> extractor) {
        double[][] predictors = rows.stream().map(extractor).toArray(double[][]::new);
        try {
            System.out.print(LogisticRegression.fit(predictors, outcomes, names).summary());
        } catch (ArithmeticException e) {
            System.out.println("    Model could not be fitted: " + e.getMessage());
        }
        System.out.println();
    }
}
