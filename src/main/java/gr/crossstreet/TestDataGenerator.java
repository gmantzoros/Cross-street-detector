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

    private static final double MIN_PAIR_DISTANCE_M = 10.0;
    private static final double MAX_PAIR_DISTANCE_M = 20.0;
    private static final double TARGET_PAIR_DISTANCE_M = 15.0;
    private static final double INTERSECTION_PROXIMITY_M = 150.0;
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
     * Generates point pairs along a road's geometry, filtering for proximity to intersections.
     */
    private List<PointPair> generatePairsAlongRoad(OsmWay way, Set<String> intersectionKeys, String cityName) {
        List<PointPair> pairs = new ArrayList<>();
        List<LatLon> geometry = way.geometry();

        int emitted = 0;
        double cumulativeDistance = 0.0;
        GeoPoint lastEmittedPoint = null;

        for (int i = 1; i < geometry.size() && emitted < MAX_PAIRS_PER_ROAD; i++) {
            GeoPoint prev = toGeoPoint(geometry.get(i - 1));
            GeoPoint curr = toGeoPoint(geometry.get(i));

            double segmentDist = GeoUtils.haversineDistance(prev, curr);
            cumulativeDistance += segmentDist;

            // We want pairs that are ~10-20m apart
            if (cumulativeDistance < MIN_PAIR_DISTANCE_M) continue;

            // Check if the current point is near an intersection node
            if (!isNearIntersection(curr, intersectionKeys, geometry)) continue;

            // If we need to interpolate to get the right distance, use the segment endpoints
            // For simplicity, use the previous and current geometry nodes as the pair
            // when they are within our distance range
            GeoPoint pairPrev;
            if (cumulativeDistance <= MAX_PAIR_DISTANCE_M) {
                pairPrev = toGeoPoint(geometry.get(i - 1));
            } else {
                // Interpolate a point ~TARGET_PAIR_DISTANCE_M back from current along the segment
                pairPrev = interpolateBack(prev, curr, segmentDist, TARGET_PAIR_DISTANCE_M);
            }

            // Avoid pairs too close to each other on the same road
            if (lastEmittedPoint != null && GeoUtils.haversineDistance(lastEmittedPoint, curr) < 50.0) {
                continue;
            }

            pairs.add(new PointPair(pairPrev, curr, cityName));
            lastEmittedPoint = curr;
            emitted++;
            cumulativeDistance = 0.0;
        }

        return pairs;
    }

    /**
     * Checks whether a point is within INTERSECTION_PROXIMITY_M of any intersection node
     * on this road's geometry.
     */
    private boolean isNearIntersection(GeoPoint point, Set<String> intersectionKeys, List<LatLon> geometry) {
        for (LatLon node : geometry) {
            String key = nodeKey(node.lat(), node.lon());
            if (intersectionKeys.contains(key)) {
                double dist = GeoUtils.haversineDistance(point, toGeoPoint(node));
                if (dist <= INTERSECTION_PROXIMITY_M) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Interpolates a point that is {@code targetDist} meters back from {@code curr}
     * along the segment from {@code prev} to {@code curr}.
     */
    private GeoPoint interpolateBack(GeoPoint prev, GeoPoint curr, double segmentDist, double targetDist) {
        if (segmentDist < 0.001) return prev;
        double fraction = 1.0 - (targetDist / segmentDist);
        if (fraction < 0) fraction = 0;
        double lat = prev.latitude() + fraction * (curr.latitude() - prev.latitude());
        double lon = prev.longitude() + fraction * (curr.longitude() - prev.longitude());
        return new GeoPoint(lat, lon);
    }

    private static GeoPoint toGeoPoint(LatLon ll) {
        return new GeoPoint(ll.lat(), ll.lon());
    }

    /**
     * Writes the generated dataset to a semicolon-delimited CSV compatible with BatchEvaluator.
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
            // Header compatible with BatchEvaluator's parseLine (6 columns, semicolon-delimited)
            writer.println("Previous Coordinates;Current Coordinates;Current Road;Target Road;Google Maps Link;City");

            for (PointPair pair : pairs) {
                String prevCoords = String.format(Locale.US, "%.6f, %.6f", pair.previous().latitude(), pair.previous().longitude());
                String currCoords = String.format(Locale.US, "%.6f, %.6f", pair.current().latitude(), pair.current().longitude());

                String googleMapsLink = String.format(Locale.US,
                        "https://www.google.com/maps/dir/%.6f,%.6f/%.6f,%.6f",
                        pair.previous().latitude(), pair.previous().longitude(),
                        pair.current().latitude(), pair.current().longitude());

                // Current Road left empty (auto-detected); Target Road left empty (for annotation)
                writer.printf("%s;%s;;;%s;%s%n",
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
