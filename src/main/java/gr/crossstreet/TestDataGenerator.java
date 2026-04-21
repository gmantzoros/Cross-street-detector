package gr.crossstreet;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.api.OverpassClient.LatLon;
import gr.crossstreet.api.OverpassClient.OsmWay;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates an annotation-ready CSV dataset for academic evaluation of the cross-street detector.
 *
 * <p>Queries OSM for named roads in multiple Greek cities, walks along road geometries to produce
 * consecutive GPS point pairs (~10-20m apart, simulating pedestrian walking), and filters for
 * points near intersections. Outputs a CSV with Google Maps direction links so the user can
 * manually annotate the expected cross street.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.TestDataGenerator [output.csv]
 * </pre>
 */
public class TestDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(TestDataGenerator.class);

    private static final double TARGET_PAIR_DISTANCE_M = 15.0;
    /** Minimum distance from each intersection endpoint when placing mid-block pairs. */
    private static final double MIN_SETBACK_M = 30.0;
    private static final int MAX_PAIRS_PER_ROAD = 4;
    private static final long OVERPASS_DELAY_MS = 5000;
    private static final int MAX_CITY_RETRIES = 3;
    private static final long CITY_RETRY_DELAY_MS = 30000;

    /** City definitions: name, center lat, center lon, query radius, target case count. */
    private record CityConfig(String name, double lat, double lon, int radiusMeters, int targetCount) {}

    /** A generated point pair ready for annotation. */
    private record PointPair(GeoPoint previous, GeoPoint current, String cityName) {}

    private static final List<CityConfig> CITIES = List.of(
            new CityConfig("Athens", 37.9838, 23.7275, 3000, 150),
            new CityConfig("Thessaloniki", 40.6401, 22.9444, 2500, 100),
            new CityConfig("Patras", 38.2466, 21.7346, 2000, 80),
            new CityConfig("Heraklion", 35.3387, 25.1442, 2000, 60),
            new CityConfig("Larissa", 39.6372, 22.4202, 2000, 60),
            new CityConfig("Volos", 39.3616, 22.9420, 2000, 50)
    );

    private final OverpassClient overpassClient;

    public TestDataGenerator() {
        this.overpassClient = new OverpassClient(AppConfig.getInstance());
    }

    public static void main(String[] args) {
        String outputPath = args.length >= 1 ? args[0] : "results/annotation-dataset.csv";
        TestDataGenerator generator = new TestDataGenerator();
        List<PointPair> allPairs = generator.generateAll();
        generator.writeCsv(allPairs, Path.of(outputPath));
        log.info("Generated {} total point pairs", allPairs.size());
    }

    /**
     * Generates point pairs for all configured cities.
     */
    public List<PointPair> generateAll() {
        List<PointPair> allPairs = new ArrayList<>();

        for (CityConfig city : CITIES) {
            log.info("Processing city: {} (target: {} cases)", city.name(), city.targetCount());

            boolean success = false;
            for (int attempt = 1; attempt <= MAX_CITY_RETRIES && !success; attempt++) {
                try {
                    if (attempt > 1) {
                        log.info("Retrying {} (attempt {}/{}) after {}s cooldown...",
                                city.name(), attempt, MAX_CITY_RETRIES, CITY_RETRY_DELAY_MS / 1000);
                        sleep(CITY_RETRY_DELAY_MS);
                    }
                    List<PointPair> cityPairs = generateForCity(city);
                    allPairs.addAll(cityPairs);
                    log.info("Generated {} pairs for {}", cityPairs.size(), city.name());
                    success = true;
                } catch (IOException e) {
                    log.warn("Attempt {}/{} failed for {}: {}", attempt, MAX_CITY_RETRIES, city.name(), e.getMessage());
                }
            }
            if (!success) {
                log.error("All {} attempts failed for {}, skipping", MAX_CITY_RETRIES, city.name());
            }
        }

        return allPairs;
    }

    /**
     * Generates point pairs for a single city.
     */
    private List<PointPair> generateForCity(CityConfig city) throws IOException {
        // Query OSM for named roads in the city area
        String query = "[out:json][timeout:60];"
                + "way[\"highway\"~\"primary|secondary|tertiary|residential\"][\"name\"]"
                + "(around:" + city.radiusMeters() + "," + city.lat() + "," + city.lon() + ");"
                + "out geom;";

        OverpassClient.OverpassData data = overpassClient.query(query);
        List<OsmWay> ways = data.ways();
        log.info("  Fetched {} ways for {}", ways.size(), city.name());

        sleep();

        // Build a set of intersection nodes (geometry points shared by 2+ ways)
        Set<String> intersectionKeys = findIntersectionNodes(ways);
        log.info("  Found {} intersection nodes", intersectionKeys.size());

        // Generate candidate point pairs along each road
        List<PointPair> candidates = new ArrayList<>();
        for (OsmWay way : ways) {
            String roadName = way.tags().get("name");
            if (roadName == null || roadName.isBlank()) continue;
            if (way.geometry().size() < 2) continue;

            List<PointPair> roadPairs = generatePairsAlongRoad(way, intersectionKeys, city.name());
            candidates.addAll(roadPairs);
        }

        log.info("  Generated {} candidate pairs before sampling", candidates.size());

        // Shuffle and sample to target count
        Collections.shuffle(candidates, new Random(city.name().hashCode()));
        if (candidates.size() > city.targetCount()) {
            candidates = new ArrayList<>(candidates.subList(0, city.targetCount()));
        }

        return candidates;
    }

    /**
     * Finds geometry nodes that are shared by 2+ ways (intersection points).
     * Uses a string key of rounded lat/lon to handle floating-point matching.
     */
    private Set<String> findIntersectionNodes(List<OsmWay> ways) {
        Map<String, Integer> nodeCounts = new HashMap<>();

        for (OsmWay way : ways) {
            // Use a set to avoid counting the same node twice within the same way
            Set<String> wayNodes = new HashSet<>();
            for (LatLon node : way.geometry()) {
                String key = nodeKey(node.lat(), node.lon());
                wayNodes.add(key);
            }
            for (String key : wayNodes) {
                nodeCounts.merge(key, 1, Integer::sum);
            }
        }

        Set<String> intersections = new HashSet<>();
        for (Map.Entry<String, Integer> entry : nodeCounts.entrySet()) {
            if (entry.getValue() >= 2) {
                intersections.add(entry.getKey());
            }
        }
        return intersections;
    }

    /**
     * Creates a string key for a lat/lon to detect shared nodes.
     * OSM nodes shared between ways have identical coordinates, so we round to 7 decimal
     * places (sub-meter precision) to handle any floating-point representation differences.
     */
    private static String nodeKey(double lat, double lon) {
        return "%.7f,%.7f".formatted(lat, lon);
    }

    /**
     * Generates mid-block point pairs along a road's geometry.
     *
     * <p>For each arc between two consecutive intersection nodes, if the arc is long enough,
     * a point pair is placed at least MIN_SETBACK_M from each intersection — i.e., clearly
     * mid-block. This makes annotation unambiguous: the next cross street ahead is obvious.</p>
     */
    private List<PointPair> generatePairsAlongRoad(OsmWay way, Set<String> intersectionKeys, String cityName) {
        List<PointPair> pairs = new ArrayList<>();
        List<LatLon> geometry = way.geometry();

        // Collect indices of intersection nodes in this road's geometry
        List<Integer> intxIndices = new ArrayList<>();
        for (int i = 0; i < geometry.size(); i++) {
            if (intersectionKeys.contains(nodeKey(geometry.get(i).lat(), geometry.get(i).lon()))) {
                intxIndices.add(i);
            }
        }

        // For each arc between consecutive intersection nodes, try to place one mid-block pair
        for (int k = 0; k + 1 < intxIndices.size() && pairs.size() < MAX_PAIRS_PER_ROAD; k++) {
            int i1 = intxIndices.get(k);
            int i2 = intxIndices.get(k + 1);

            // Build list of points along this arc and their cumulative distances
            List<GeoPoint> arc = new ArrayList<>();
            for (int i = i1; i <= i2; i++) arc.add(toGeoPoint(geometry.get(i)));

            double[] cumDist = new double[arc.size()];
            for (int i = 1; i < arc.size(); i++) {
                cumDist[i] = cumDist[i - 1] + GeoUtils.haversineDistance(arc.get(i - 1), arc.get(i));
            }
            double arcLen = cumDist[arc.size() - 1];

            // Arc must be long enough to place both points clearly away from both intersections
            double minArcLen = MIN_SETBACK_M * 2 + TARGET_PAIR_DISTANCE_M;
            if (arcLen < minArcLen) continue;

            // Place curr at MIN_SETBACK from I1, prev TARGET_PAIR_DISTANCE_M before curr.
            // Both points are well within the arc and away from the intersections.
            double currDist = MIN_SETBACK_M + TARGET_PAIR_DISTANCE_M / 2;
            double prevDist = currDist - TARGET_PAIR_DISTANCE_M;

            GeoPoint curr = interpolateAlongArc(arc, cumDist, currDist);
            GeoPoint prev = interpolateAlongArc(arc, cumDist, prevDist);

            pairs.add(new PointPair(prev, curr, cityName));
        }

        return pairs;
    }

    /**
     * Interpolates a point at {@code targetDist} meters from the arc start.
     */
    private GeoPoint interpolateAlongArc(List<GeoPoint> arc, double[] cumDist, double targetDist) {
        targetDist = Math.max(0, Math.min(cumDist[cumDist.length - 1], targetDist));
        for (int i = 1; i < arc.size(); i++) {
            if (cumDist[i] >= targetDist) {
                double segLen = cumDist[i] - cumDist[i - 1];
                if (segLen < 0.001) return arc.get(i);
                double frac = (targetDist - cumDist[i - 1]) / segLen;
                double lat = arc.get(i - 1).latitude() + frac * (arc.get(i).latitude() - arc.get(i - 1).latitude());
                double lon = arc.get(i - 1).longitude() + frac * (arc.get(i).longitude() - arc.get(i - 1).longitude());
                return new GeoPoint(lat, lon);
            }
        }
        return arc.getLast();
    }

    private static GeoPoint toGeoPoint(LatLon ll) {
        return new GeoPoint(ll.lat(), ll.lon());
    }

    /**
     * Writes the generated dataset in the annotation CSV format:
     * Previous Coordinates, Current Coordinates, Target Road (blank), Google Maps Link, City.
     */
    private void writeCsv(List<PointPair> pairs, Path outputPath) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("Could not create output directory: {}", e.getMessage());
        }

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            writer.println("Previous Coordinates;Current Coordinates;Target Road;Google Maps Link;City");

            for (PointPair pair : pairs) {
                String prevCoords = String.format(Locale.US, "%.6f, %.6f",
                        pair.previous().latitude(), pair.previous().longitude());
                String currCoords = String.format(Locale.US, "%.6f, %.6f",
                        pair.current().latitude(), pair.current().longitude());
                String googleMapsLink = String.format(Locale.US,
                        "https://www.google.com/maps/dir/%.6f,%.6f/%.6f,%.6f",
                        pair.previous().latitude(), pair.previous().longitude(),
                        pair.current().latitude(), pair.current().longitude());

                // Target Road left blank for human annotation
                writer.printf(Locale.US, "%s;%s;;%s;%s%n",
                        prevCoords, currCoords, googleMapsLink, pair.cityName());
            }

            log.info("Annotation dataset written to {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to write CSV: {}", e.getMessage(), e);
        }
    }

    private void sleep() {
        sleep(OVERPASS_DELAY_MS);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
