package gr.crossstreet.model;

/**
 * Represents a single test case from the evaluation dataset.
 */
public record TestCase(
        int rowNumber,
        GeoPoint previousCoords,
        GeoPoint currentCoords,
        String targetRoad,
        String city
) {
}