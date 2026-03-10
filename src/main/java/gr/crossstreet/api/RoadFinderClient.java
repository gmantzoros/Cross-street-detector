package gr.crossstreet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.crossstreet.config.AppConfig;
import gr.crossstreet.model.GeoPoint;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a geographic point to the nearest road name using:
 * 1. Google Roads API (nearestRoads) → snapped point with placeId
 * 2. Google Geocoding API → road name from placeId
 */
public class RoadFinderClient {

    private static final Logger log = LoggerFactory.getLogger(RoadFinderClient.class);

    private static final String NEAREST_ROADS_URL = "https://roads.googleapis.com/v1/nearestRoads";
    private static final String GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public RoadFinderClient(AppConfig config) {
        this.apiKey = config.getApiKey();
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .build();
    }

    /**
     * Finds the road name closest to the given geographic point.
     *
     * @param point the target coordinates
     * @return the road name, or empty if it could not be resolved
     */
    public Optional<String> findRoadName(GeoPoint point) {
        try {
            Optional<String> placeId = fetchNearestRoadPlaceId(point);
            if (placeId.isEmpty()) {
                log.warn("No snapped road found near {}", point);
                return Optional.empty();
            }
            return resolveRoadNameFromPlaceId(placeId.get());
        } catch (IOException e) {
            log.error("Failed to resolve road name for {}: {}", point, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Calls the Roads API nearestRoads endpoint to get the placeId of the closest road segment.
     */
    private Optional<String> fetchNearestRoadPlaceId(GeoPoint point) throws IOException {
        HttpUrl url = HttpUrl.parse(NEAREST_ROADS_URL).newBuilder()
                .addQueryParameter("points", point.toApiString())
                .addQueryParameter("key", apiKey)
                .build();

        String responseBody = executeGet(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode snappedPoints = root.get("snappedPoints");

        if (snappedPoints == null || !snappedPoints.isArray() || snappedPoints.isEmpty()) {
            return Optional.empty();
        }

        // Find the snapped point with originalIndex == 0
        for (JsonNode snapped : snappedPoints) {
            if (snapped.has("originalIndex") && snapped.get("originalIndex").asInt() == 0) {
                return Optional.ofNullable(snapped.get("placeId")).map(JsonNode::asText);
            }
        }

        // Fallback: return the first placeId
        return Optional.ofNullable(snappedPoints.get(0).get("placeId")).map(JsonNode::asText);
    }

    /**
     * Calls the Geocoding API to resolve a placeId into a road (route) name.
     */
    private Optional<String> resolveRoadNameFromPlaceId(String placeId) throws IOException {
        HttpUrl url = HttpUrl.parse(GEOCODE_URL).newBuilder()
                .addQueryParameter("place_id", placeId)
                .addQueryParameter("key", apiKey)
                .build();

        String responseBody = executeGet(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode results = root.get("results");

        if (results == null || !results.isArray() || results.isEmpty()) {
            log.warn("Geocoding returned no results for placeId '{}'", placeId);
            return Optional.empty();
        }

        JsonNode addressComponents = results.get(0).get("address_components");
        if (addressComponents == null) {
            return Optional.empty();
        }

        for (JsonNode component : addressComponents) {
            JsonNode types = component.get("types");
            if (types != null) {
                for (JsonNode type : types) {
                    if ("route".equals(type.asText())) {
                        String roadName = component.get("long_name").asText();
                        log.info("Resolved placeId '{}' → road '{}'", placeId, roadName);
                        return Optional.of(roadName);
                    }
                }
            }
        }

        log.warn("No 'route' component found in geocoding results for placeId '{}'", placeId);
        return Optional.empty();
    }

    private String executeGet(HttpUrl url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API call to %s returned HTTP %d: %s"
                        .formatted(url.encodedPath(), response.code(), response.message()));
            }
            return response.body().string();
        }
    }
}