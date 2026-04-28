package gr.crossstreet.geo;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.util.RoadNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Finds cross-street intersections purely from OSM road geometry.
 *
 * <p>Given a user position, forward bearing, and the name of the road they're
 * currently on, this class finds all named roads that geometrically intersect
 * the current road ahead of the user.</p>
 */
public class IntersectionDetector {

    private static final Logger log = LoggerFactory.getLogger(IntersectionDetector.class);
    private static final double METERS_PER_DEGREE_LAT = 111320.0;

    /**
     * Minimum sine of the angle between two roads at their intersection.
     * sin(25°) ≈ 0.42 — roads crossing at less than 25° are treated as
     * continuations (name change) rather than genuine cross-streets.
     */
    private static final double MIN_CROSSING_ANGLE_SIN = Math.sin(Math.toRadians(25.0));

    /**
     * A detected intersection between the current road and a cross-street.
     */
    public record Intersection(
            GeoPoint point,
            double distanceMeters,
            double bearing,
            String roadName
    ) {}

    /**
     * Finds all named roads that intersect the current road ahead of the user.
     *
     * @param userPosition   the user's current GPS position
     * @param forwardBearing the bearing the user is travelling (degrees, 0=N)
     * @param currentRoadName the name of the road the user is on (Latin or Greek)
     * @param roadData       cached Overpass data containing all nearby roads
     * @return intersections sorted by distance from the user, closest first
     */
    public List<Intersection> findForwardIntersections(
            GeoPoint userPosition,
            double forwardBearing,
            String currentRoadName,
            OverpassClient.OverpassData roadData
    ) {
        // 1. Find ways that belong to the current road
        List<OverpassClient.OsmWay> currentRoadWays = new ArrayList<>();
        List<OverpassClient.OsmWay> otherNamedWays = new ArrayList<>();

        for (OverpassClient.OsmWay way : roadData.ways()) {
            String name = resolveRoadName(way.tags());
            if (name == null) continue;

            if (RoadNameMatcher.fuzzyMatch(name, currentRoadName)) {
                currentRoadWays.add(way);
            } else {
                otherNamedWays.add(way);
            }
        }

        if (currentRoadWays.isEmpty()) {
            log.warn("No ways found matching current road '{}'", currentRoadName);
            return List.of();
        }

        log.debug("Found {} ways for current road '{}', {} other named ways",
                currentRoadWays.size(), currentRoadName, otherNamedWays.size());

        // 2. Reference point for flat-earth projection
        double refLat = userPosition.latitude();
        double refLon = userPosition.longitude();
        double cosRefLat = Math.cos(Math.toRadians(refLat));

        // 3. Collect all segments of the current road
        List<double[]> currentSegments = extractSegments(currentRoadWays, refLat, refLon, cosRefLat);

        // 4. For each other named road, check segment-segment intersections
        Map<String, Intersection> closestByRoad = new LinkedHashMap<>();

        for (OverpassClient.OsmWay otherWay : otherNamedWays) {
            String otherName = resolveRoadName(otherWay.tags());
            if (otherName == null) continue;

            List<double[]> otherSegments = extractSegments(List.of(otherWay), refLat, refLon, cosRefLat);

            for (double[] cs : currentSegments) {
                for (double[] os : otherSegments) {
                    double[] intersection = segmentIntersection(
                            cs[0], cs[1], cs[2], cs[3],
                            os[0], os[1], os[2], os[3]);
                    if (intersection == null) continue;

                    // Reject near-collinear crossings: these are road continuations
                    // where the road simply changes name, not a genuine cross-street.
                    double csDx = cs[2] - cs[0], csDy = cs[3] - cs[1];
                    double osDx = os[2] - os[0], osDy = os[3] - os[1];
                    double crossProd = Math.abs(csDx * osDy - csDy * osDx);
                    double csLen = Math.sqrt(csDx * csDx + csDy * csDy);
                    double osLen = Math.sqrt(osDx * osDx + osDy * osDy);
                    if (csLen > 0 && osLen > 0 && crossProd / (csLen * osLen) < MIN_CROSSING_ANGLE_SIN) continue;

                    double ix = intersection[0];
                    double iy = intersection[1];

                    // Convert back to lat/lon
                    double iLat = refLat + iy / METERS_PER_DEGREE_LAT;
                    double iLon = refLon + ix / (METERS_PER_DEGREE_LAT * cosRefLat);
                    GeoPoint iPoint = new GeoPoint(iLat, iLon);

                    // Distance from user
                    double dist = Math.sqrt(ix * ix + iy * iy);

                    // Bearing from user to intersection
                    double bearing = GeoUtils.calculateBearing(userPosition, iPoint);

                    // Check if forward: angle difference < 90 degrees
                    double angleDiff = Math.abs(bearing - forwardBearing);
                    if (angleDiff > 180) angleDiff = 360 - angleDiff;
                    if (angleDiff >= 90) continue;

                    // Keep closest intersection per road name
                    Intersection existing = closestByRoad.get(otherName);
                    if (existing == null || dist < existing.distanceMeters()) {
                        closestByRoad.put(otherName, new Intersection(iPoint, dist, bearing, otherName));
                    }
                }
            }
        }

        List<Intersection> result = closestByRoad.values().stream()
                .sorted(Comparator.comparingDouble(Intersection::distanceMeters))
                .collect(Collectors.toList());

        log.info("Found {} forward intersections from '{}'", result.size(), currentRoadName);
        for (Intersection i : result) {
            log.debug("  {} at {}m, bearing {}°", i.roadName(), String.format("%.1f", i.distanceMeters()), String.format("%.1f", i.bearing()));
        }

        return result;
    }

    /**
     * Finds the nearest named road to a given position.
     * Used when the current road name is not known (standalone mode).
     */
    public Optional<String> findNearestRoad(GeoPoint position, OverpassClient.OverpassData roadData) {
        double minDist = Double.MAX_VALUE;
        String closestName = null;

        double refLat = position.latitude();
        double refLon = position.longitude();
        double cosRefLat = Math.cos(Math.toRadians(refLat));

        for (OverpassClient.OsmWay way : roadData.ways()) {
            String name = resolveRoadName(way.tags());
            if (name == null) continue;

            List<OverpassClient.LatLon> geom = way.geometry();
            for (int i = 0; i < geom.size() - 1; i++) {
                double dist = pointToSegmentDistance(
                        0, 0,
                        toLocalX(geom.get(i), refLat, refLon, cosRefLat),
                        toLocalY(geom.get(i), refLat),
                        toLocalX(geom.get(i + 1), refLat, refLon, cosRefLat),
                        toLocalY(geom.get(i + 1), refLat));
                if (dist < minDist) {
                    minDist = dist;
                    closestName = name;
                }
            }
        }

        if (closestName != null) {
            log.info("Nearest road to {}: '{}' ({}m)", position, closestName, String.format("%.1f", minDist));
        }
        return Optional.ofNullable(closestName);
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    private List<double[]> extractSegments(List<OverpassClient.OsmWay> ways,
                                            double refLat, double refLon, double cosRefLat) {
        List<double[]> segments = new ArrayList<>();
        for (OverpassClient.OsmWay way : ways) {
            List<OverpassClient.LatLon> geom = way.geometry();
            for (int i = 0; i < geom.size() - 1; i++) {
                double x1 = toLocalX(geom.get(i), refLat, refLon, cosRefLat);
                double y1 = toLocalY(geom.get(i), refLat);
                double x2 = toLocalX(geom.get(i + 1), refLat, refLon, cosRefLat);
                double y2 = toLocalY(geom.get(i + 1), refLat);
                segments.add(new double[]{x1, y1, x2, y2});
            }
        }
        return segments;
    }

    private static double toLocalX(OverpassClient.LatLon p, double refLat, double refLon, double cosRefLat) {
        return (p.lon() - refLon) * METERS_PER_DEGREE_LAT * cosRefLat;
    }

    private static double toLocalY(OverpassClient.LatLon p, double refLat) {
        return (p.lat() - refLat) * METERS_PER_DEGREE_LAT;
    }

    /**
     * Standard 2D segment-segment intersection using cross products.
     * Returns the intersection point [x, y] in local meters, or null if segments don't intersect.
     */
    private static double[] segmentIntersection(
            double ax1, double ay1, double ax2, double ay2,
            double bx1, double by1, double bx2, double by2
    ) {
        double dx1 = ax2 - ax1, dy1 = ay2 - ay1;
        double dx2 = bx2 - bx1, dy2 = by2 - by1;

        double denom = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denom) < 1e-10) return null; // parallel or collinear

        double t = ((bx1 - ax1) * dy2 - (by1 - ay1) * dx2) / denom;
        double u = ((bx1 - ax1) * dy1 - (by1 - ay1) * dx1) / denom;

        if (t < 0 || t > 1 || u < 0 || u > 1) return null; // intersection outside segments

        return new double[]{ax1 + t * dx1, ay1 + t * dy1};
    }

    /**
     * Distance from point (px,py) to line segment (ax,ay)-(bx,by) in local meters.
     */
    private static double pointToSegmentDistance(double px, double py,
                                                  double ax, double ay,
                                                  double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return Math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay));

        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        double ex = px - projX, ey = py - projY;
        return Math.sqrt(ex * ex + ey * ey);
    }

    // -------------------------------------------------------------------------
    // Road name resolution and matching
    // -------------------------------------------------------------------------

    /**
     * Resolves a road name from OSM tags, preferring the Greek name tag.
     */
    public static String resolveRoadName(Map<String, String> tags) {
        String name = tags.get("name");
        if (name != null && !name.isBlank()) return name;
        return tags.get("name:en");
    }

}
