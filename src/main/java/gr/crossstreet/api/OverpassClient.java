package gr.crossstreet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.crossstreet.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    /** Number of full passes over the endpoint list before giving up. */
    private static final int MAX_ROUNDS = 3;
    private static final long INITIAL_BACKOFF_MS = 5000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final List<String> endpoints;
    private final String userAgent;
    private final OverpassCache cache;
    private final long rateLimitDelayMs;

    /** Index of the endpoint that last answered successfully; where the next query starts. */
    private int preferredEndpoint = 0;

    /** When the last network request was issued, for rate limiting. 0 = none yet. */
    private long lastRequestAtMs = 0;

    /** Marks a failure that is worth retrying against another endpoint. */
    private static final class TransientFailure extends Exception {
        TransientFailure(String message, Throwable cause) { super(message, cause); }
        TransientFailure(String message) { super(message); }
    }

    /** A lat/lon coordinate from an {@code out geom} geometry array. */
    public record LatLon(double lat, double lon) {}

    /** An OSM way with inline geometry and tags. */
    public record OsmWay(long id, List<LatLon> geometry, Map<String, String> tags) {}

    /** Parsed Overpass response. */
    public record OverpassData(List<OsmWay> ways) {}

    public OverpassClient(AppConfig config) {
        this.endpoints = config.getOverpassApiUrls();
        this.userAgent = config.getOverpassUserAgent();
        this.rateLimitDelayMs = config.getRateLimitDelayMs();
        this.cache = new OverpassCache(Path.of(config.getOverpassCacheDir()), config.isOverpassCacheEnabled());
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .build();
        log.info("Overpass endpoints (in priority order): {}", endpoints);
    }

    /**
     * Executes an Overpass QL query and parses the JSON response.
     * <p>
     * On a timeout or a rate-limit/gateway response the query is retried against the
     * next configured endpoint immediately — a different mirror is far more likely to
     * answer than the one that just refused us. Only after a full pass over every
     * endpoint has failed do we back off exponentially before trying again.
     */
    public OverpassData query(String query) throws IOException {
        log.debug("Overpass query: {}", query);

        Optional<String> cached = cache.get(query);
        if (cached.isPresent()) {
            log.info("Overpass cache hit — no network request");
            return parseResponse(cached.get(), "cache");
        }

        String encodedBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        int totalAttempts = MAX_ROUNDS * endpoints.size();
        TransientFailure lastFailure = null;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            int index = (preferredEndpoint + attempt) % endpoints.size();
            String endpoint = endpoints.get(index);
            try {
                String json = execute(endpoint, encodedBody);
                OverpassData data = parseResponse(json, endpoint);
                // Only successful, parseable responses are cached — never an error or a
                // partial body, which would otherwise be replayed forever.
                cache.put(query, json);
                if (index != preferredEndpoint) {
                    log.info("Switching preferred Overpass endpoint to {}", endpoint);
                    preferredEndpoint = index;
                }
                return data;
            } catch (TransientFailure e) {
                lastFailure = e;
                log.warn("Overpass endpoint {} failed: {}", endpoint, e.getMessage());

                boolean roundComplete = (attempt + 1) % endpoints.size() == 0;
                if (roundComplete && attempt + 1 < totalAttempts) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt / endpoints.size()));
                    log.warn("All {} Overpass endpoints failed — backing off {}ms before round {}/{}",
                            endpoints.size(), backoff, (attempt / endpoints.size()) + 2, MAX_ROUNDS);
                    sleep(backoff);
                }
            }
        }

        throw new IOException("Overpass query failed against all %d endpoints after %d rounds"
                .formatted(endpoints.size(), MAX_ROUNDS), lastFailure);
    }

    /**
     * Performs a single request against one endpoint and returns the raw response body.
     *
     * @throws TransientFailure if another endpoint or a later retry might succeed
     * @throws IOException      if the failure is permanent (bad query, unparseable response)
     */
    private String execute(String endpoint, String encodedBody) throws TransientFailure, IOException {
        throttle();

        Request request = new Request.Builder()
                .url(endpoint)
                .header("User-Agent", userAgent)
                .post(RequestBody.create(encodedBody, FORM))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            // 429 = rate limited, 5xx = mirror overloaded or gateway timeout. Another mirror may serve us.
            if (code == 429 || code >= 500) {
                throw new TransientFailure("HTTP %d %s".formatted(code, response.message()));
            }
            if (!response.isSuccessful()) {
                throw new IOException("Overpass API returned HTTP %d: %s".formatted(code, response.message()));
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new TransientFailure("empty response body");
            }
            return responseBody.string();
        } catch (SocketTimeoutException e) {
            throw new TransientFailure("timed out", e);
        } catch (ConnectException | UnknownHostException e) {
            throw new TransientFailure("unreachable (%s)".formatted(e.getMessage()), e);
        }
    }

    /**
     * Enforces the configured gap between outgoing requests. Called only from
     * {@link #execute}, so queries served from the cache are never delayed.
     */
    private void throttle() throws IOException {
        if (rateLimitDelayMs <= 0) return;

        if (lastRequestAtMs > 0) {
            long elapsed = System.currentTimeMillis() - lastRequestAtMs;
            long remaining = rateLimitDelayMs - elapsed;
            if (remaining > 0) {
                sleep(remaining);
            }
        }
        lastRequestAtMs = System.currentTimeMillis();
    }

    /** Hit/miss totals for the run, for reporting at the end of a batch. */
    public OverpassCache cache() {
        return cache;
    }

    private static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during backoff", e);
        }
    }

    private OverpassData parseResponse(String json, String endpoint) throws IOException {
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

        // A city-centre query returning nothing usually means the endpoint only carries a
        // regional extract rather than the planet — it answers 200 OK but has no data here.
        if (ways.isEmpty()) {
            log.warn("Overpass endpoint {} returned 0 ways — check that it serves planet-wide data", endpoint);
        } else {
            log.debug("Parsed {} ways from Overpass response", ways.size());
        }
        return new OverpassData(ways);
    }
}
