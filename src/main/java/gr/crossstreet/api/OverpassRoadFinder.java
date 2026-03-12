package gr.crossstreet.api;

import gr.crossstreet.config.AppConfig;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.util.GreekTransliterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Drop-in replacement for RoadFinderClient.
 * Finds the nearest named road to a given point using the Overpass API.
 * Reuses cached data from the map renderer when possible to avoid extra API calls.
 */
public class OverpassRoadFinder {

    private static final Logger log = LoggerFactory.getLogger(OverpassRoadFinder.class);

    private final OverpassClient overpassClient;
    private final OverpassMapRenderer mapRenderer;
    private final int queryRadius;

    public OverpassRoadFinder(AppConfig config, OverpassMapRenderer mapRenderer) {
        this.overpassClient = new OverpassClient(config);
        this.mapRenderer = mapRenderer;
        this.queryRadius = config.getOverpassRoadQueryRadius();
    }

    /**
     * Finds the road name closest to the given geographic point.
     */
    public Optional<String> findRoadName(GeoPoint point) {
        try {
            OverpassClient.OverpassData data = getCachedOrFetch(point);

            if (data.ways().isEmpty()) {
                log.warn("No named roads found near {}", point);
                return Optional.empty();
            }

            return findClosestRoadName(point, data);

        } catch (IOException e) {
            log.error("Failed to resolve road name for {}: {}", point, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private OverpassClient.OverpassData getCachedOrFetch(GeoPoint point) throws IOException {
        OverpassClient.OverpassData cached = mapRenderer.getLastData();
        GeoPoint cachedCenter = mapRenderer.getLastCenter();

        if (cached != null && cachedCenter != null) {
            double distMeters = haversineDistance(point, cachedCenter);
            if (distMeters < mapRenderer.getQueryRadius() - queryRadius) {
                log.debug("Reusing cached Overpass data for road name lookup ({}m from center)",
                        String.format("%.0f", distMeters));
                return cached;
            }
        }

        log.debug("Cache miss — fetching road data from Overpass for {}", point);
        double latOffset = queryRadius / 111320.0;
        double lonOffset = queryRadius / (111320.0 * Math.cos(Math.toRadians(point.latitude())));
        String query = String.format(Locale.US,
                "[out:json][timeout:10][maxsize:1048576];" +
                        "way[\"highway\"][\"name\"](%f,%f,%f,%f);out geom qt;",
                point.latitude() - latOffset, point.longitude() - lonOffset,
                point.latitude() + latOffset, point.longitude() + lonOffset);
        return overpassClient.query(query);
    }

    private static double haversineDistance(GeoPoint a, GeoPoint b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private Optional<String> findClosestRoadName(GeoPoint point, OverpassClient.OverpassData data) {
        double minDist = Double.MAX_VALUE;
        String closestName = null;

        for (OverpassClient.OsmWay way : data.ways()) {
            String name = resolveRoadName(way.tags());
            if (name == null) continue;

            double dist = perpendicularDistance(point, way.geometry());
            if (dist < minDist) {
                minDist = dist;
                closestName = name;
            }
        }

        if (closestName != null) {
            log.info("Closest road to {}: '{}' (distance: {}m)", point, closestName, String.format("%.1f", minDist));
            return Optional.of(closestName);
        }

        return Optional.empty();
    }

    /**
     * Prefers name:en (Latin) over name (often Greek script).
     * Falls back to transliterating the Greek name to Latin.
     */
    private String resolveRoadName(Map<String, String> tags) {
        String nameEn = tags.get("name:en");
        if (nameEn != null && !nameEn.isBlank()) return nameEn;
        String name = tags.get("name");
        if (name == null) return null;
        return GreekTransliterator.transliterate(name);
    }

    /**
     * Computes the minimum perpendicular distance from a point to any segment of a way's geometry.
     */
    private double perpendicularDistance(GeoPoint point, List<OverpassClient.LatLon> geometry) {
        double minDist = Double.MAX_VALUE;

        for (int i = 0; i < geometry.size() - 1; i++) {
            OverpassClient.LatLon a = geometry.get(i);
            OverpassClient.LatLon b = geometry.get(i + 1);

            double dist = pointToSegmentDistance(
                    point.latitude(), point.longitude(),
                    a.lat(), a.lon(),
                    b.lat(), b.lon());
            minDist = Math.min(minDist, dist);
        }

        return minDist;
    }

    /**
     * Approximate distance in meters from a point to a line segment, using flat-earth projection.
     */
    private double pointToSegmentDistance(double pLat, double pLon,
                                          double aLat, double aLon,
                                          double bLat, double bLon) {
        double cosLat = Math.cos(Math.toRadians(pLat));

        double px = (pLon - aLon) * 111320 * cosLat;
        double py = (pLat - aLat) * 111320;
        double bx = (bLon - aLon) * 111320 * cosLat;
        double by = (bLat - aLat) * 111320;

        double lenSq = bx * bx + by * by;
        if (lenSq == 0) {
            return Math.sqrt(px * px + py * py);
        }

        double t = Math.max(0, Math.min(1, (px * bx + py * by) / lenSq));
        double projX = t * bx;
        double projY = t * by;

        double dx = px - projX;
        double dy = py - projY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}