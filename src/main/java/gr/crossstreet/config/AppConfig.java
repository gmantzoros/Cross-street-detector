package gr.crossstreet.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Centralized configuration loaded from application.properties.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String PROPERTIES_FILE = "application.properties";

    private final Properties properties;

    private static AppConfig instance;

    private AppConfig() {
        this.properties = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Configuration file '%s' not found on classpath".formatted(PROPERTIES_FILE));
            }
            properties.load(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load configuration", e);
        }
        resolveEnvironmentVariables();
        log.info("Configuration loaded successfully");
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    /**
     * Replaces ${ENV_VAR} placeholders with actual environment variable values.
     * Unresolved placeholders are removed so that callers fall back to their
     * hardcoded defaults instead of receiving the literal "${VAR}" string.
     */
    private void resolveEnvironmentVariables() {
        for (String key : properties.stringPropertyNames()) {
            String val = properties.getProperty(key);
            if (!val.startsWith("${") || !val.endsWith("}")) continue;

            String envKey = val.substring(2, val.length() - 1);
            String envValue = System.getenv(envKey);
            if (envValue != null) {
                properties.setProperty(key, envValue);
            } else {
                log.warn("Environment variable '{}' is not set (referenced by property '{}') — using default", envKey, key);
                properties.remove(key);
            }
        }
    }

    /**
     * Overpass endpoints in priority order. The client tries them in turn and fails
     * over on timeout or rate-limiting, so at least one entry should be a mirror
     * with generous limits.
     */
    public List<String> getOverpassApiUrls() {
        // "overpass.api.url" (singular) is still honoured for backwards compatibility.
        String raw = properties.getProperty("overpass.api.urls", properties.getProperty("overpass.api.url"));
        if (raw == null || raw.isBlank()) {
            return List.of("https://overpass.private.coffee/api/interpreter");
        }
        List<String> urls = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return urls.isEmpty() ? List.of("https://overpass.private.coffee/api/interpreter") : urls;
    }

    public String getOverpassUserAgent() {
        return properties.getProperty("overpass.user.agent",
                "CrossStreetDetector/1.0 (https://github.com/mantzorosg/Cross-street-detector)");
    }

    public int getOverpassMapQueryRadius() {
        return getInt("overpass.map.query.radius", 200);
    }

    public boolean isOverpassCacheEnabled() {
        return Boolean.parseBoolean(properties.getProperty("overpass.cache.enabled", "true"));
    }

    public String getOverpassCacheDir() {
        return properties.getProperty("overpass.cache.dir", "cache");
    }

    /**
     * Pause enforced before each outgoing Overpass request. Applied by the client so
     * that it throttles real network calls only — cached queries are not delayed.
     */
    public long getRateLimitDelayMs() {
        String val = properties.getProperty("overpass.rate.limit.delay.ms");
        return val != null ? Long.parseLong(val) : 3000L;
    }

    public int getConnectTimeoutSeconds() {
        return getInt("http.connect.timeout.seconds", 10);
    }

    public int getReadTimeoutSeconds() {
        return getInt("http.read.timeout.seconds", 30);
    }

    private int getInt(String key, int defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

}