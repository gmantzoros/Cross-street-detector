package gr.crossstreet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.crossstreet.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;

/**
 * Shared Overpass API client used by both the map renderer and the road finder.
 * Sends QL queries to the Overpass API and parses the JSON response into typed records.
 * <p>
 * Uses {@code out geom} format — geometry is embedded directly in way elements,
 * avoiding the expensive {@code >;} recurse step.
 */
public class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 5000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String overpassUrl;

    /** A lat/lon coordinate from an {@code out geom} geometry array. */
    public record LatLon(double lat, double lon) {}

    /** An OSM way with inline geometry and tags. */
    public record OsmWay(long id, List<LatLon> geometry, Map<String, String> tags) {}

    /** Parsed Overpass response. */
    public record OverpassData(List<OsmWay> ways) {}

    public OverpassClient(AppConfig config) {
        this.overpassUrl = config.getOverpassApiUrl();
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .build();
    }

    /**
     * Executes an Overpass QL query and parses the JSON response.
     * Retries on 429, 504, and socket timeouts with exponential backoff.
     */
    public OverpassData query(String query) throws IOException {
        log.debug("Overpass query: {}", query);

        RequestBody body = RequestBody.create("data=" + query, FORM);
        Request request = new Request.Builder()
                .url(overpassUrl)
                .post(body)
                .build();

        for (int attempt = 0; ; attempt++) {
            try {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() == 429 || response.code() == 504) {
                        if (attempt < MAX_RETRIES) {
                            long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                            log.warn("Overpass {} — retrying in {}ms (attempt {}/{})",
                                    response.code(), backoff, attempt + 1, MAX_RETRIES);
                            sleep(backoff);
                            continue;
                        }
                    }
                    if (!response.isSuccessful()) {
                        throw new IOException("Overpass API returned HTTP %d: %s"
                                .formatted(response.code(), response.message()));
                    }

                    assert response.body() != null;
                    String json = response.body().string();
                    return parseResponse(json);
                }
            } catch (SocketTimeoutException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Timeout — retrying (attempt {}/{})", attempt + 1, MAX_RETRIES);
                    continue;
                }
                throw new IOException("Overpass request timed out after %d retries".formatted(MAX_RETRIES), e);
            }
        }
    }

    private static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during backoff", e);
        }
    }

    private OverpassData parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode elements = root.get("elements");

        List<OsmWay> ways = new ArrayList<>();

        if (elements == null || !elements.isArray()) {
            return new OverpassData(ways);
        }

        for (JsonNode element : elements) {
            if (!"way".equals(element.path("type").asText())) continue;

            long id = element.get("id").asLong();

            // Parse inline geometry from "out geom"
            List<LatLon> geometry = new ArrayList<>();
            JsonNode geomNode = element.get("geometry");
            if (geomNode != null && geomNode.isArray()) {
                for (JsonNode coord : geomNode) {
                    geometry.add(new LatLon(coord.get("lat").asDouble(), coord.get("lon").asDouble()));
                }
            }

            // Parse tags
            Map<String, String> tags = new HashMap<>();
            JsonNode tagsNode = element.get("tags");
            if (tagsNode != null && tagsNode.isObject()) {
                tagsNode.fields().forEachRemaining(f -> tags.put(f.getKey(), f.getValue().asText()));
            }

            ways.add(new OsmWay(id, geometry, tags));
        }

        log.debug("Parsed {} ways from Overpass response", ways.size());
        return new OverpassData(ways);
    }
}
