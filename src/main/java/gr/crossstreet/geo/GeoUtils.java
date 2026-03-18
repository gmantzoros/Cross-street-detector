package gr.crossstreet.geo;

import gr.crossstreet.model.GeoPoint;

/**
 * Geographic utility methods: bearing calculation and destination point projection.
 */
public final class GeoUtils {

    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtils() {
        // Utility class
    }

    /**
     * Projects a point from a center location at a given distance and bearing.
     *
     * Optimized to precompute repeated sin/cos values.
     *
     * @param center   the starting point
     * @param distance distance in meters
     * @param bearing  bearing in degrees
     * @return the projected GeoPoint
     */
    public static GeoPoint projectPoint(GeoPoint center, double distance, double bearing) {
        double lat1 = Math.toRadians(center.latitude());
        double lon1 = Math.toRadians(center.longitude());

        double angularDistance = distance / EARTH_RADIUS_METERS;
        double bearingRad = Math.toRadians(bearing);

        // Precompute repeated sin/cos values
        double sinLat1 = Math.sin(lat1);
        double cosLat1 = Math.cos(lat1);
        double sinAngular = Math.sin(angularDistance);
        double cosAngular = Math.cos(angularDistance);
        double cosBearing = Math.cos(bearingRad);
        double sinBearing = Math.sin(bearingRad);

        // Latitude of projected point
        double lat2 = Math.asin(sinLat1 * cosAngular + cosLat1 * sinAngular * cosBearing);

        // Longitude of projected point
        double lon2 = lon1 + Math.atan2(
                sinBearing * sinAngular * cosLat1,
                cosAngular - sinLat1 * Math.sin(lat2)
        );

        // Normalize longitude to [-180, 180]
        lon2 = (lon2 + Math.PI) % (2 * Math.PI);
        if (lon2 < 0) lon2 += 2 * Math.PI;
        lon2 -= Math.PI;

        return new GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }

    /**
     * Calculates the forward bearing from point A to point B in degrees [0, 360).
     * <p>
     * Optimized to precompute repeated sin/cos values for performance.
     */
    public static double calculateBearing(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lon1 = Math.toRadians(from.longitude());
        double lat2 = Math.toRadians(to.latitude());
        double lon2 = Math.toRadians(to.longitude());

        double dLon = lon2 - lon1;

        // Precompute repeated trig values
        double cosLat1 = Math.cos(lat1);
        double sinLat1 = Math.sin(lat1);
        double cosLat2 = Math.cos(lat2);

        double y = Math.sin(dLon) * cosLat2;
        double x = cosLat1 * Math.sin(lat2) - sinLat1 * cosLat2 * Math.cos(dLon);

        double bearing = Math.toDegrees(Math.atan2(y, x));

        return normalizeAngle(bearing);
    }

    /**
     * Calculates the great-circle distance between two points using the Haversine formula.
     *
     * @return distance in meters
     */
    public static double haversineDistance(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude() - a.longitude());

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }

    private static double normalizeAngle(double angle) {
        angle = angle % 360.0;
        return angle < 0 ? angle + 360.0 : angle;
    }
}