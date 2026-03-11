package gr.crossstreet;

import gr.crossstreet.api.GoogleMapsClient;
import gr.crossstreet.api.RoadFinderClient;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.image.ImageProcessor;
import gr.crossstreet.model.BearingAngles;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Cross-Street Detector — Image-based variant.
 *
 * Given two consecutive GPS positions (previous and current), this application:
 * 1. Computes left/right search angles perpendicular to the movement direction
 * 2. Fetches a styled Google Maps static image centered on the current position
 * 3. Scans the image along both angles to detect the nearest road (green pixels)
 * 4. Projects the closer detection back to geographic coordinates
 * 5. Resolves the road name via the Roads + Geocoding APIs
 */
public class CrossStreetDetectorApp {

    private static final Logger log = LoggerFactory.getLogger(CrossStreetDetectorApp.class);

    private final AppConfig config;
    private final GoogleMapsClient mapsClient;
    private final RoadFinderClient roadFinder;
    private final ImageProcessor imageProcessor;

    public CrossStreetDetectorApp() {
        this.config = AppConfig.getInstance();
        this.mapsClient = new GoogleMapsClient(config);
        this.roadFinder = new RoadFinderClient(config);
        this.imageProcessor = new ImageProcessor(config);
    }

    public static void main(String[] args) {
        CrossStreetDetectorApp app = new CrossStreetDetectorApp();

        // Example coordinates — replace with real input or CLI args
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
     * Runs the full cross-street detection pipeline.
     *
     * @param current  the current GPS position
     * @param previous the previous GPS position (used to determine heading)
     * @return the detection result containing the target point and road name
     * @throws IOException if any API call fails
     */
    public DetectionResult detect(GeoPoint current, GeoPoint previous) throws IOException {
        // Step 1: Calculate search angles
        BearingAngles angles = GeoUtils.calculateSearchAngles(current, previous, config.getAngleOffset());
        log.info("Search angles: {}", angles);

        // Step 2: Fetch styled map image
        BufferedImage mapImage = mapsClient.fetchStyledMap(current);

        // Step 3: Scan image along both angles
        OptionalDouble leftDistance = imageProcessor.findRoadDistance(mapImage, angles.leftAngle());
        OptionalDouble rightDistance = imageProcessor.findRoadDistance(mapImage, angles.rightAngle());

        double leftMeters = leftDistance.orElse(Double.MAX_VALUE);
        double rightMeters = rightDistance.orElse(Double.MAX_VALUE);

        if (leftMeters == Double.MAX_VALUE && rightMeters == Double.MAX_VALUE) {
            log.warn("No road detected in either direction");
            return new DetectionResult(current, current, 0, 0, Optional.empty(), Optional.empty(), OptionalDouble.empty(), 0);
        }

        // Step 4: Pick the closer detection as primary, farther as secondary
        boolean useLeft = leftMeters <= rightMeters;
        double primaryDistance = useLeft ? leftMeters : rightMeters;
        double primaryAngle = useLeft ? angles.leftAngle() : angles.rightAngle();
        double secondaryDistance = useLeft ? rightMeters : leftMeters;
        double secondaryAngle = useLeft ? angles.rightAngle() : angles.leftAngle();

        GeoPoint targetPoint = GeoUtils.projectPoint(current, primaryDistance, primaryAngle);
        log.info("Primary target: {} ({}m, {}°, {})",
                targetPoint, primaryDistance, primaryAngle, useLeft ? "left" : "right");

        // Step 5: Resolve road names for both directions
        Optional<String> roadName = roadFinder.findRoadName(targetPoint);

        Optional<String> alternativeRoadName = Optional.empty();
        if (secondaryDistance != Double.MAX_VALUE) {
            GeoPoint secondaryPoint = GeoUtils.projectPoint(current, secondaryDistance, secondaryAngle);
            log.info("Secondary target: {} ({}m, {}°)", secondaryPoint, secondaryDistance, secondaryAngle);
            alternativeRoadName = roadFinder.findRoadName(secondaryPoint);
        }

        OptionalDouble altDist = secondaryDistance != Double.MAX_VALUE
                ? OptionalDouble.of(secondaryDistance) : OptionalDouble.empty();
        return new DetectionResult(current, targetPoint, primaryDistance, primaryAngle,
                roadName, alternativeRoadName, altDist, secondaryAngle);
    }
}