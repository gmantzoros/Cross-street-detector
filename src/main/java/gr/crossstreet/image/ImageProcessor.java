package gr.crossstreet.image;

import gr.crossstreet.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.OptionalDouble;

/**
 * Processes a styled Google Maps static image to detect the nearest road
 * along a given angular direction from the image center.
 *
 * Roads are expected to be rendered in pure green (#00FF00) via the Maps API style parameter.
 */
public final class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);
    private static final Color ROAD_GREEN = new Color(0, 255, 0);

    private final int skipPixels;
    private final double stepSize;
    private final double metersPerPixel;

    public ImageProcessor(AppConfig config) {
        this.skipPixels = config.getSkipPixels();
        this.stepSize = config.getStepSize();
        this.metersPerPixel = config.getImageScale();
    }

    /**
     * Scans from the center of the image along the specified geographic angle
     * to find the first green (road) pixel.
     *
     * @param image          the styled static map image
     * @param geographicAngle the search direction in geographic degrees (0° = north, clockwise)
     * @return the distance in meters to the first detected road, or empty if no road was found
     */
    public OptionalDouble findRoadDistance(BufferedImage image, double geographicAngle) {
        // Convert geographic angle (0°=N, clockwise) to screen angle (0°=E, clockwise in screen coords)
        double screenAngle = (geographicAngle + 270.0) % 360.0;
        double angleRad = Math.toRadians(screenAngle);

        double centerX = image.getWidth() / 2.0;
        double centerY = image.getHeight() / 2.0;

        // Start position: skip initial pixels to avoid the center marker area
        double x = centerX + skipPixels * Math.cos(angleRad);
        double y = centerY + skipPixels * Math.sin(angleRad);

        log.debug("Scanning at geographic angle {}° (screen angle {}°) from ({}, {})",
                geographicAngle, screenAngle, x, y);

        while (isWithinBounds(image, x, y)) {
            Color pixel = new Color(image.getRGB((int) x, (int) y));

            if (isRoadPixel(pixel)) {
                double pixelDistance = Math.hypot(x - centerX, y - centerY);
                double meters = pixelDistance * metersPerPixel;
                log.info("Road found at pixel ({}, {}), distance: {}px = {}m",
                        (int) x, (int) y, pixelDistance, meters);
                return OptionalDouble.of(meters);
            }

            x += stepSize * Math.cos(angleRad);
            y += stepSize * Math.sin(angleRad);
        }

        log.warn("No road detected along angle {}° — reached image boundary", geographicAngle);
        return OptionalDouble.empty();
    }

    private boolean isRoadPixel(Color color) {
        return color.getGreen() > 200 && color.getRed() < 50 && color.getBlue() < 50;
    }

    private boolean isWithinBounds(BufferedImage image, double x, double y) {
        return x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight();
    }
}