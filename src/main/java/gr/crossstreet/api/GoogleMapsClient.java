package gr.crossstreet.api;

import gr.crossstreet.config.AppConfig;
import gr.crossstreet.model.GeoPoint;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetches styled static map images from the Google Maps Static API.
 * Roads are rendered in green (#00FF00) and POIs are hidden for clean image processing.
 */
public class GoogleMapsClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsClient.class);
    private static final String STATIC_MAP_BASE_URL = "https://maps.googleapis.com/maps/api/staticmap";

    private final OkHttpClient httpClient;
    private final AppConfig config;

    public GoogleMapsClient(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .build();
    }

    /**
     * Downloads a styled static map image centered on the given point.
     *
     * @param center the geographic center of the map
     * @return the map image as a BufferedImage
     * @throws IOException if the download or image parsing fails
     */
    public BufferedImage fetchStyledMap(GeoPoint center) throws IOException {
        String sizeParam = config.getStaticMapSize() + "x" + config.getStaticMapSize();

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(STATIC_MAP_BASE_URL)).newBuilder()
                .addQueryParameter("center", center.toApiString())
                .addQueryParameter("scale", String.valueOf(config.getStaticMapScale()))
                .addQueryParameter("zoom", String.valueOf(config.getStaticMapZoom()))
                .addQueryParameter("size", sizeParam)
                .addQueryParameter("format", "png")
                // Black base for everything — eliminates stray green from parks/water/terrain
                .addQueryParameter("style", "feature:all|color:0x000000")
                // Hide all labels (they can bleed green pixels into non-road areas)
                .addQueryParameter("style", "feature:all|element:labels|visibility:off")
                // Color road geometry pure green — exact geometry only, no labels
                .addQueryParameter("style", "feature:road|element:geometry|color:" + config.getRoadColor())
                .addQueryParameter("key", config.getApiKey())
                .build();

        log.debug("Fetching static map: {}", url);

        Request request = new Request.Builder().url(url).get().build();

        int maxAttempts = config.getRetryMaxAttempts();
        long delayMs = config.getRetryInitialDelayMs();
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    try (InputStream body = response.body().byteStream()) {
                        BufferedImage image = ImageIO.read(body);
                        if (image == null) {
                            throw new IOException("Failed to decode image from Static Maps API response");
                        }
                        log.info("Fetched map image: {}x{} pixels for center {}", image.getWidth(), image.getHeight(), center);
                        return image;
                    }
                }

                int code = response.code();
                lastException = new IOException("Static Maps API returned HTTP %d: %s"
                        .formatted(code, response.message()));

                if (!isRetryable(code) || attempt == maxAttempts) {
                    throw lastException;
                }
            } catch (IOException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw e;
                }
            }

            log.warn("Static Maps API attempt {}/{} failed, retrying in {}ms: {}",
                    attempt, maxAttempts, delayMs, lastException.getMessage());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw lastException;
            }
            delayMs *= 2; // exponential backoff
        }

        throw lastException; // unreachable, but satisfies compiler
    }

    private static boolean isRetryable(int httpCode) {
        return httpCode == 429 || httpCode >= 500;
    }
}