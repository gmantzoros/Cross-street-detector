package gr.crossstreet.image;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.geo.IntersectionDetector;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Saves annotated debug images for failed detection cases.
 *
 * <p>Renders roads from Overpass geometry data and overlays:
 * <ul>
 *   <li>Top info panel — row number, current road, target road, detected road</li>
 *   <li>Road name labels — small text along each rendered road</li>
 *   <li>Red cross — current position (image center)</li>
 *   <li>White arrow — heading direction</li>
 *   <li>Yellow circle — chosen (closest) intersection point + road name</li>
 *   <li>Gray dots — other forward intersection points + road names</li>
 *   <li>Bottom caption bar — summary</li>
 * </ul>
 * Images are written to {@value #DEBUG_DIR} as {@code case-NNN.png}.</p>
 */
public class DebugImageSaver {

    private static final Logger log = LoggerFactory.getLogger(DebugImageSaver.class);
    private static final String DEBUG_DIR = "debug";
    private static final int IMAGE_SIZE = 1000;
    private static final double METERS_PER_PIXEL = 0.265;
    private static final int ROAD_WIDTH = 6;
    private static final Color COLOR_CHOSEN = new Color(255, 220, 0);
    private static final Color COLOR_OTHER = new Color(160, 160, 160);

    public DebugImageSaver() {
    }

    public void save(TestCase tc, DetectionResult detection, OverpassClient.OverpassData roadData,
                     List<IntersectionDetector.Intersection> intersections) {
        try {
            GeoPoint center = detection.currentPosition();
            double centerLat = center.latitude();
            double centerLon = center.longitude();
            double cosLat = Math.cos(Math.toRadians(centerLat));

            int cx = IMAGE_SIZE / 2;
            int cy = IMAGE_SIZE / 2;

            BufferedImage canvas = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(Color.BLACK);
            g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);

            // Render roads
            g.setColor(new Color(0, 255, 0));
            g.setStroke(new BasicStroke(ROAD_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (OverpassClient.OsmWay way : roadData.ways()) {
                List<OverpassClient.LatLon> geometry = way.geometry();
                if (geometry.size() < 2) continue;

                int[] xPoints = new int[geometry.size()];
                int[] yPoints = new int[geometry.size()];
                for (int i = 0; i < geometry.size(); i++) {
                    OverpassClient.LatLon coord = geometry.get(i);
                    xPoints[i] = cx + (int) Math.round((coord.lon() - centerLon) * 111320 * cosLat / METERS_PER_PIXEL);
                    yPoints[i] = cy - (int) Math.round((coord.lat() - centerLat) * 111320 / METERS_PER_PIXEL);
                }
                g.drawPolyline(xPoints, yPoints, geometry.size());

                // Road name label at midpoint of the way
                String roadName = IntersectionDetector.resolveRoadName(way.tags());
                if (roadName != null) {
                    int mid = geometry.size() / 2;
                    int labelX = xPoints[mid];
                    int labelY = yPoints[mid];
                    drawRoadLabel(g, labelX, labelY, roadName);
                }
            }

            // White arrow pointing in the FORWARD direction
            double forwardBearing = GeoUtils.calculateBearing(tc.previousCoords(), tc.currentCoords());
            drawArrow(g, cx, cy, forwardBearing);

            // All intersection points
            if (intersections != null) {
                for (int i = intersections.size() - 1; i >= 0; i--) {
                    IntersectionDetector.Intersection ix = intersections.get(i);
                    int px = cx + (int) Math.round((ix.point().longitude() - centerLon) * 111320 * cosLat / METERS_PER_PIXEL);
                    int py = cy - (int) Math.round((ix.point().latitude() - centerLat) * 111320 / METERS_PER_PIXEL);

                    if (i == 0) {
                        // Chosen (closest) intersection — yellow
                        drawHitPoint(g, px, py, COLOR_CHOSEN, ix.roadName());
                    } else {
                        // Other intersections — gray, smaller
                        drawSmallDot(g, px, py, COLOR_OTHER, ix.roadName());
                    }
                }
            }

            // Red cross at center (current position)
            drawCross(g, cx, cy);

            // Top info panel
            String detected = detection.roadName().orElse("?");
            String topInfo = "#%03d | Current: %s | Target: %s | Detected: %s".formatted(
                    tc.rowNumber(), tc.currentRoad(), tc.targetRoad(), detected);
            drawTopPanel(g, IMAGE_SIZE, topInfo);

            // Bottom caption
            String caption = "#%03d | Target: %s | Detected: %s".formatted(
                    tc.rowNumber(), tc.targetRoad(), detected);
            drawCaption(g, IMAGE_SIZE, IMAGE_SIZE, caption);

            g.dispose();

            Path dir = Path.of(DEBUG_DIR);
            Files.createDirectories(dir);
            Path outFile = dir.resolve("case-%03d.png".formatted(tc.rowNumber()));
            ImageIO.write(canvas, "png", outFile.toFile());
            log.info("Debug image saved → {}", outFile);

        } catch (IOException e) {
            log.warn("Could not save debug image for case #{}: {}", tc.rowNumber(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Drawing helpers
    // -------------------------------------------------------------------------

    private void drawRoadLabel(Graphics2D g, int x, int y, String name) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();
        // Shadow
        g.setColor(new Color(0, 0, 0, 180));
        g.drawString(name, x + 1, y + 1);
        // Text
        g.setColor(new Color(200, 200, 255));
        g.drawString(name, x, y);
    }

    private void drawCross(Graphics2D g, int x, int y) {
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(x - 14, y, x + 14, y);
        g.drawLine(x, y - 14, x, y + 14);
    }

    private void drawHitPoint(Graphics2D g, int x, int y, Color color, String label) {
        int r = 10;
        g.setColor(color);
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(x - r, y - r, r * 2, r * 2);
        g.fillOval(x - 3, y - 3, 6, 6);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int lx = x + r + 4;
        int ly = y + fm.getAscent() / 2;
        g.setColor(Color.BLACK);
        g.drawString(label, lx + 1, ly + 1);
        g.setColor(color);
        g.drawString(label, lx, ly);
    }

    private void drawSmallDot(Graphics2D g, int x, int y, Color color, String label) {
        g.setColor(color);
        g.fillOval(x - 4, y - 4, 8, 8);

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int lx = x + 6;
        int ly = y + fm.getAscent() / 2;
        g.setColor(Color.BLACK);
        g.drawString(label, lx + 1, ly + 1);
        g.setColor(color);
        g.drawString(label, lx, ly);
    }

    private void drawArrow(Graphics2D g, int cx, int cy, double geoAngle) {
        double rad = Math.toRadians((geoAngle + 270.0) % 360.0);
        int ex = (int) Math.round(cx + 70 * Math.cos(rad));
        int ey = (int) Math.round(cy + 70 * Math.sin(rad));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(cx, cy, ex, ey);
        double a = Math.atan2(ey - cy, ex - cx);
        int ah = 10;
        g.drawLine(ex, ey, (int)(ex - ah * Math.cos(a - 0.45)), (int)(ey - ah * Math.sin(a - 0.45)));
        g.drawLine(ex, ey, (int)(ex - ah * Math.cos(a + 0.45)), (int)(ey - ah * Math.sin(a + 0.45)));
    }

    private void drawTopPanel(Graphics2D g, int w, String text) {
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int pad = 6;
        int bw = fm.stringWidth(text) + pad * 2;
        int bh = fm.getHeight() + pad;
        int bx = (w - bw) / 2;
        int by = 8;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRoundRect(bx, by, bw, bh, 8, 8);
        g.setColor(new Color(255, 200, 100));
        g.drawString(text, bx + pad, by + fm.getAscent() + pad / 2);
    }

    private void drawCaption(Graphics2D g, int w, int h, String text) {
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int pad = 6;
        int bw = fm.stringWidth(text) + pad * 2;
        int bh = fm.getHeight() + pad;
        int bx = (w - bw) / 2;
        int by = h - bh - 8;
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(bx, by, bw, bh, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(text, bx + pad, by + fm.getAscent() + pad / 2);
    }
}
