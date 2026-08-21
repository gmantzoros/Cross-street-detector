package gr.crossstreet;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.geo.IntersectionDetector;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Cross-Street Detector — Geometry-based variant.
 *
 * Given two consecutive GPS positions (previous and current),
 * this application finds cross-street intersections using road geometry data from Overpass:
 * 1. Computes the forward bearing from previous to current position
 * 2. Fetches road data from Overpass API (with spatial caching)
 * 3. Finds forward intersections with the current road using segment-segment math
 */
public class CrossStreetDetectorApp {

    private static final Logger log = LoggerFactory.getLogger(CrossStreetDetectorApp.class);

    private final AppConfig config;
    private final OverpassClient overpassClient;
    private final IntersectionDetector detector;

    // Spatial cache: reuse road data when the new query center is close to the previous one
    private OverpassClient.OverpassData lastRoadData;
    private GeoPoint lastCenter;
    private int lastQueryRadius;

    // Last intersection list for debug image rendering
    private List<IntersectionDetector.Intersection> lastIntersections = List.of();

    public CrossStreetDetectorApp() {
        this.config = AppConfig.getInstance();
        this.overpassClient = new OverpassClient(config);
        this.detector = new IntersectionDetector();
    }

    public CrossStreetDetectorApp(OverpassClient overpassClient) {
        this.config = AppConfig.getInstance();
        this.overpassClient = overpassClient;
        this.detector = new IntersectionDetector();
    }

    public static void main(String[] args) {
        if (args.length == 1) {
            System.err.println("Usage: CrossStreetDetectorApp <previousLatLon> <currentLatLon>");
            System.err.println("Example: CrossStreetDetectorApp \"37.988891, 23.741949\" \"37.988988, 23.741867\"");
            System.exit(1);
        }

        CrossStreetDetectorApp app = new CrossStreetDetectorApp();

        GeoPoint previous = args.length >= 2
                ? GeoPoint.parse(args[0])
                : GeoPoint.parse("37.988891, 23.741949");

        GeoPoint current = args.length >= 2
                ? GeoPoint.parse(args[1])
                : GeoPoint.parse("37.988988, 23.741867");

        try {
            DetectionResult result = app.detect(current, previous);
            log.info("Detection complete:\n{}", result);
        } catch (Exception e) {
            log.error("Detection failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Runs the geometry-based cross-street detection pipeline.
     *
     * @param current  the current GPS position
     * @param previous the previous GPS position (used to determine heading)
     * @return the detection result containing the target point and road name
     * @throws IOException if the Overpass API call fails
     */
    public synchronized DetectionResult detect(GeoPoint current, GeoPoint previous) throws IOException {
        // Step 1: Compute forward bearing
        double forwardBearing = GeoUtils.calculateBearing(previous, current);
        log.info("Forward bearing: {}°", String.format("%.1f", forwardBearing));

        // Step 2: Fetch road data from Overpass (with spatial caching)
        int queryRadius = config.getOverpassMapQueryRadius();
        OverpassClient.OverpassData roadData = fetchRoadDataCached(current, queryRadius);
        this.lastRoadData = roadData;

        // Step 3: Auto-detect current road
        Optional<String> detected = detector.findNearestRoad(current, roadData);
        if (detected.isEmpty()) {
            log.warn("Could not auto-detect current road name");
            return emptyResult(current);
        }
        String currentRoadName = detected.get();
        log.info("Auto-detected current road: '{}'", currentRoadName);

        // Step 4: Find forward intersections
        List<IntersectionDetector.Intersection> intersections =
                detector.findForwardIntersections(current, forwardBearing, currentRoadName, roadData);
        this.lastIntersections = intersections;

        if (intersections.isEmpty()) {
            log.warn("No forward intersections found");
            return emptyResult(current);
        }

        // Return closest intersection
        IntersectionDetector.Intersection closest = intersections.getFirst();
        log.info("Closest intersection: '{}' at {}m, bearing {}°",
                closest.roadName(), String.format("%.1f", closest.distanceMeters()),
                String.format("%.1f", closest.bearing()));

        return new DetectionResult(
                current,
                closest.point(),
                closest.distanceMeters(),
                closest.bearing(),
                Optional.of(closest.roadName())
        );
    }

    /** Logs Overpass cache hit/miss totals — useful at the end of a batch run. */
    public void logCacheStatistics() {
        overpassClient.cache().logStatistics();
    }

    public synchronized OverpassClient.OverpassData getLastRoadData() {
        return lastRoadData;
    }

    public synchronized List<IntersectionDetector.Intersection> getLastIntersections() {
        return lastIntersections;
    }

    /**
     * Returns cached road data if the new center is within 30% of the previous query radius,
     * otherwise fetches fresh data from Overpass.
     */
    private OverpassClient.OverpassData fetchRoadDataCached(GeoPoint center, int queryRadius) throws IOException {
        if (lastRoadData != null && lastCenter != null) {
            double dist = GeoUtils.haversineDistance(center, lastCenter);
            double threshold = lastQueryRadius * 0.3;
            if (dist <= threshold) {
                log.info("Spatial cache hit: center moved {}m (threshold {}m), reusing cached road data",
                        String.format("%.1f", dist), String.format("%.1f", threshold));
                return lastRoadData;
            }
        }

        log.info("Fetching road data from Overpass (center={}, radius={}m)", center, queryRadius);
        OverpassClient.OverpassData data = fetchRoadData(center, queryRadius);
        this.lastCenter = center;
        this.lastQueryRadius = queryRadius;
        return data;
    }

    /**
     * Returns the Overpass road data for a given center and radius.
     */
    public OverpassClient.OverpassData fetchRoadData(GeoPoint center, int queryRadius) throws IOException {
        double latOffset = queryRadius / 111320.0;
        double lonOffset = queryRadius / (111320.0 * Math.cos(Math.toRadians(center.latitude())));
        String query = String.format(Locale.US,
                "[out:json][timeout:10][maxsize:2097152];" +
                        "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
                        "residential|unclassified|living_street|pedestrian|service|" +
                        "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"](%f,%f,%f,%f);out geom qt;",
                center.latitude() - latOffset, center.longitude() - lonOffset,
                center.latitude() + latOffset, center.longitude() + lonOffset);
        return overpassClient.query(query);
    }

    private DetectionResult emptyResult(GeoPoint current) {
        return new DetectionResult(current, current, 0, 0, Optional.empty());
    }
}
