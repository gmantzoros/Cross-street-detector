package gr.crossstreet.model;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Encapsulates the result of a cross-street detection attempt.
 */
public record DetectionResult(
        GeoPoint currentPosition,
        GeoPoint targetPoint,
        double distanceMeters,
        double searchAngle,
        Optional<String> roadName
) {
    @NotNull
    @Override
    public String toString() {
        return """
                DetectionResult {
                  currentPosition = %s
                  targetPoint     = %s
                  distance        = %.2f m
                  searchAngle     = %.2f°
                  road            = %s
                }""".formatted(currentPosition, targetPoint, distanceMeters, searchAngle,
                roadName.orElse("unknown"));
    }
}