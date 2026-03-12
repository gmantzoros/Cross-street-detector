package gr.crossstreet.image;

import gr.crossstreet.api.OverpassMapRenderer;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves annotated debug images for failed detection cases.
 *
 * <p>Re-fetches the styled map for the given position and overlays:
 * <ul>
 *   <li>Red cross — current position (image center)</li>
 *   <li>White arrow — heading direction (toward previous GPS point)</li>
 *   <li>Yellow circle — primary detected road hit point + road name</li>
 *   <li>Cyan circle — alternative detected road hit point + road name</li>
 *   <li>Caption bar — row number, target road, detected roads</li>
 * </ul>
 * Images are written to {@value #DEBUG_DIR} as {@code case-NNN.png}.</p>
 */
public class DebugImageSaver {

    private static final Logger log = LoggerFactory.getLogger(DebugImageSaver.class);
    private static final String DEBUG_DIR = "debug";

    private final OverpassMapRenderer mapsClient;
    private final double metersPerPixel;

    public DebugImageSaver(OverpassMapRenderer mapsClient, AppConfig config) {
        this.mapsClient = mapsClient;
        this.metersPerPixel = config.getImageScale();
    }

    public void save(TestCase tc, DetectionResult detection) {
        try {
            BufferedImage original = mapsClient.fetchStyledMap(detection.currentPosition());

            BufferedImage canvas = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, null);

            int cx = original.getWidth() / 2;
            int cy = original.getHeight() / 2;

            // White arrow pointing toward previous position (shows where we came from)
            double backBearing = GeoUtils.calculateBearing(tc.currentCoords(), tc.previousCoords());
            drawArrow(g, cx, cy, backBearing);

            // Primary hit — yellow
            if (detection.distanceMeters() > 0) {
                int[] p = toPixel(cx, cy, detection.distanceMeters(), detection.searchAngle());
                drawHitPoint(g, p[0], p[1], new Color(255, 220, 0), "P: " + detection.roadName().orElse("?"));
            }

            // Alternative hit — cyan
            detection.alternativeDistanceMeters().ifPresent(altDist -> {
                int[] p = toPixel(cx, cy, altDist, detection.alternativeAngle());
                drawHitPoint(g, p[0], p[1], Color.CYAN, "A: " + detection.alternativeRoadName().orElse("?"));
            });

            // Red cross at center (current position) — drawn last so it's on top
            drawCross(g, cx, cy);

            // Bottom caption
            String caption = "#%03d | Target: %s | P: %s | A: %s".formatted(
                    tc.rowNumber(), tc.targetRoad(),
                    detection.roadName().orElse("?"),
                    detection.alternativeRoadName().orElse("?"));
            drawCaption(g, original.getWidth(), original.getHeight(), caption);

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

    /** Converts metres + geographic angle to image pixel coordinates. */
    private int[] toPixel(int cx, int cy, double meters, double geoAngle) {
        double rad = Math.toRadians((geoAngle + 270.0) % 360.0);
        return new int[]{
            (int) Math.round(cx + (meters / metersPerPixel) * Math.cos(rad)),
            (int) Math.round(cy + (meters / metersPerPixel) * Math.sin(rad))
        };
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
        g.setColor(Color.BLACK);          // shadow
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