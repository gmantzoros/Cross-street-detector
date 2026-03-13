package gr.crossstreet.model;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Encapsulates the result of a cross-street detection attempt.
 *
 * <p>Contains the closest forward intersection found by the geometry-based detector.</p>
 */
public record DetectionResult(
        GeoPoint currentPosition,
        GeoPoint targetPoint,
        double distanceMeters,
        double bearingToIntersection,
        Optional<String> roadName
) {
    @NotNull
    @Override
    public String toString() {
        return """
                DetectionResult {
                  currentPosition      = %s
                  targetPoint          = %s
                  distance             = %.2f m
                  bearingToIntersection = %.2f°
                  road                 = %s
                }""".formatted(currentPosition, targetPoint, distanceMeters, bearingToIntersection,
                roadName.orElse("unknown"));
    }
}
