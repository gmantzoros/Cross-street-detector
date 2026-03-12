package gr.crossstreet.api;

import gr.crossstreet.config.AppConfig;
import gr.crossstreet.model.GeoPoint;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Drop-in replacement for GoogleMapsClient.
 * Fetches OSM road data via Overpass API and renders a green-on-black map image using Java2D.
 * <p>
 * Optimizations:
 * - Uses bbox instead of around (faster server-side)
 * - Filters to relevant highway types only (no footpaths/cycleways)
 * - Uses out geom (inline geometry, no expensive node recurse)
 * - Adds timeout and maxsize hints
 */
public class OverpassMapRenderer {

    private static final Logger log = LoggerFactory.getLogger(OverpassMapRenderer.class);

    // Highway types relevant for cross-street detection (Overpass regex filter)
    private static final String HIGHWAY_REGEX =
            "^(motorway|trunk|primary|secondary|tertiary|" +
            "residential|unclassified|living_street|motorway_link|trunk_link|" +
            "primary_link|secondary_link|tertiary_link)$";

    private final OverpassClient overpassClient;
    private final int imageSize;
    private final double metersPerPixel;
    private final int roadWidth;
    private final int queryRadius;

    // Cache the last query result so the road finder can reuse it
    private volatile OverpassClient.OverpassData lastData;
    private volatile GeoPoint lastCenter;

    public OverpassMapRenderer(AppConfig config) {
        this.overpassClient = new OverpassClient(config);
        this.imageSize = config.getStaticMapSize() * config.getStaticMapScale();
        this.metersPerPixel = config.getImageScale();
        this.roadWidth = config.getRenderRoadWidth();
        this.queryRadius = config.getOverpassMapQueryRadius();
    }

    public OverpassClient.OverpassData getLastData() {
        return lastData;
    }

    public GeoPoint getLastCenter() {
        return lastCenter;
    }

    public int getQueryRadius() {
        return queryRadius;
    }

    /**
     * Fetches roads near the given center and renders them as green lines on a black background.
     * Reuses cached Overpass data if the new center is close enough that the previous query's
     * bounding box still fully covers the new rendering area.
     */
    public BufferedImage fetchStyledMap(GeoPoint center) throws IOException {
        OverpassClient.OverpassData data;

        if (lastData != null && lastCenter != null && isWithinCache(center)) {
            log.info("Cache HIT — reusing Overpass data (center moved {}m)",
                    String.format("%.0f", haversineDistance(center, lastCenter)));
            data = lastData;
        } else {
            String query = getQuery(center);
            data = overpassClient.query(query);
            this.lastData = data;
            this.lastCenter = center;
        }

        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, imageSize, imageSize);

        g.setColor(new Color(0, 255, 0));
        g.setStroke(new BasicStroke(roadWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double centerLat = center.latitude();
        double centerLon = center.longitude();
        double cosLat = Math.cos(Math.toRadians(centerLat));
        int cx = imageSize / 2;
        int cy = imageSize / 2;

        int roadsDrawn = 0;
        for (OverpassClient.OsmWay way : data.ways()) {
            List<OverpassClient.LatLon> geometry = way.geometry();
            if (geometry.size() < 2) continue;

            int[] xPoints = new int[geometry.size()];
            int[] yPoints = new int[geometry.size()];

            for (int i = 0; i < geometry.size(); i++) {
                OverpassClient.LatLon coord = geometry.get(i);
                xPoints[i] = cx + (int) Math.round((coord.lon() - centerLon) * 111320 * cosLat / metersPerPixel);
                yPoints[i] = cy - (int) Math.round((coord.lat() - centerLat) * 111320 / metersPerPixel);
            }

            g.drawPolyline(xPoints, yPoints, geometry.size());
            roadsDrawn++;
        }

        g.dispose();
        log.info("Rendered {} roads on {}x{} image for center {}", roadsDrawn, imageSize, imageSize, center);
        return image;
    }

    /**
     * Returns true if the new center is close enough to the cached center that
     * the cached data's bounding box fully contains the new center's bounding box.
     * The cached bbox extends queryRadius from lastCenter; the new bbox extends
     * queryRadius from center. For full containment, the distance between centers
     * must be small enough that the new bbox doesn't protrude outside the cached bbox.
     * With a safety margin, this means: distance < queryRadius * 0.3
     * (e.g., for 150m radius, cache is valid if center moved < 45m).
     */
    private boolean isWithinCache(GeoPoint center) {
        double dist = haversineDistance(center, lastCenter);
        // The new rendering needs data queryRadius from center.
        // The cached data covers queryRadius from lastCenter.
        // Safe overlap margin: new center must be well inside the cached area
        // so that roads at the edge of the new view are still covered.
        return dist < queryRadius * 0.3;
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

    @NotNull
    private String getQuery(GeoPoint center) {
        double latOffset = queryRadius / 111320.0;
        double lonOffset = queryRadius / (111320.0 * Math.cos(Math.toRadians(center.latitude())));
        double south = center.latitude() - latOffset;
        double north = center.latitude() + latOffset;
        double west = center.longitude() - lonOffset;
        double east = center.longitude() + lonOffset;

        return String.format(Locale.US,
                "[out:json][timeout:10][maxsize:2097152];" +
                "way[\"highway\"~\"%s\"](%f,%f,%f,%f);out geom qt;",
                HIGHWAY_REGEX, south, west, north, east);
    }
}
