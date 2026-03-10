package gr.crossstreet.geo;

import gr.crossstreet.model.BearingAngles;
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
     * Calculates the bearing from the current point to the previous point (i.e., the reverse
     * direction of movement), then returns left/right search angles offset by the given degrees.
     *
     * @param current     the current position
     * @param previous    the previous position
     * @param angleOffset degrees to offset left and right from the reverse bearing
     * @return left and right search angles in degrees [0, 360)
     */
    public static BearingAngles calculateSearchAngles(GeoPoint current, GeoPoint previous, double angleOffset) {
        double lat1 = Math.toRadians(current.latitude());
        double lon1 = Math.toRadians(current.longitude());
        double lat2 = Math.toRadians(previous.latitude());
        double lon2 = Math.toRadians(previous.longitude());

        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        double bearing = Math.toDegrees(Math.atan2(y, x));

        // Reverse direction: the bearing from current→previous gives the "behind" direction,
        // which is already what the original code computed (bearing + 180)
        bearing = (bearing + 180.0) % 360.0;

        double left = normalizeAngle(bearing - angleOffset);
        double right = normalizeAngle(bearing + angleOffset);

        return new BearingAngles(left, right);
    }

    /**
     * Projects a point from a center location at a given distance and bearing.
     *
     * @param center   the starting point
     * @param distance distance in meters
     * @param bearing  bearing in degrees
     * @return the projected GeoPoint
     */
    public static GeoPoint projectPoint(GeoPoint center, double distance, double bearing) {
        double centerLatRad = Math.toRadians(center.latitude());
        double centerLonRad = Math.toRadians(center.longitude());
        double angularDistance = distance / EARTH_RADIUS_METERS;
        double bearingRad = Math.toRadians(bearing);

        double latRad = Math.asin(
                Math.sin(centerLatRad) * Math.cos(angularDistance)
                        + Math.cos(centerLatRad) * Math.sin(angularDistance) * Math.cos(bearingRad)
        );

        double lonRad = centerLonRad + Math.atan2(
                Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(centerLatRad),
                Math.cos(angularDistance) - Math.sin(centerLatRad) * Math.sin(latRad)
        );

        return new GeoPoint(Math.toDegrees(latRad), Math.toDegrees(lonRad));
    }

    private static double normalizeAngle(double angle) {
        angle = angle % 360.0;
        return angle < 0 ? angle + 360.0 : angle;
    }
}