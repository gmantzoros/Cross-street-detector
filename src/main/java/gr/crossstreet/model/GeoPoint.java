package gr.crossstreet.model;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a geographic coordinate (latitude, longitude).
 */
public record GeoPoint(double latitude, double longitude) {

    /**
     * Parses a coordinate string like "38.016959, 24.419875".
     */
    public static GeoPoint parse(String coordString) {
        String[] parts = coordString.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid coordinate format: '%s'. Expected 'lat, lon'.".formatted(coordString));
        }
        return new GeoPoint(
                Double.parseDouble(parts[0].trim()),
                Double.parseDouble(parts[1].trim())
        );
    }

    /**
     * Returns coordinates as a comma-separated string without spaces (for API calls).
     */
    public String toApiString() {
        return latitude + "," + longitude;
    }

    @NotNull
    @Override
    public String toString() {
        return "GeoPoint[lat=%.6f, lon=%.6f]".formatted(latitude, longitude);
    }
}