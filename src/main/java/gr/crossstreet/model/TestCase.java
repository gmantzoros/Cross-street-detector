package gr.crossstreet.model;

/**
 * Represents a single test case from the evaluation dataset.
 */
public record TestCase(
        int rowNumber,
        GeoPoint previousCoords,
        GeoPoint currentCoords,
        String currentRoad,
        String targetRoad,
        String city
) {
}