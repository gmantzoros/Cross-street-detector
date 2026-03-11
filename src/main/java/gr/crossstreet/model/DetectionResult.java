package gr.crossstreet.model;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Encapsulates the result of a cross-street detection attempt.
 *
 * <p>Carries both the primary detection (the closer of the two scanned directions)
 * and the alternative detection (the farther direction), so callers can fall back
 * to the alternative when the primary road is not the expected cross-street.</p>
 */
public record DetectionResult(
        GeoPoint currentPosition,
        GeoPoint targetPoint,
        double distanceMeters,
        double searchAngle,
        Optional<String> roadName,
        Optional<String> alternativeRoadName,
        OptionalDouble alternativeDistanceMeters,
        double alternativeAngle
) {
    @NotNull
    @Override
    public String toString() {
        return """
                DetectionResult {
                  currentPosition  = %s
                  targetPoint      = %s
                  distance         = %.2f m
                  searchAngle      = %.2f°
                  road             = %s
                  alternativeRoad  = %s (%.2f m, %.2f°)
                }""".formatted(currentPosition, targetPoint, distanceMeters, searchAngle,
                roadName.orElse("unknown"),
                alternativeRoadName.orElse("unknown"),
                alternativeDistanceMeters.orElse(0.0),
                alternativeAngle);
    }
}