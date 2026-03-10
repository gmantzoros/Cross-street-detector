package gr.crossstreet.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Centralized configuration loaded from application.properties.
 * The Google Maps API key is resolved from the GOOGLE_MAPS_API_KEY environment variable.
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
     */
    private void resolveEnvironmentVariables() {
        properties.forEach((key, value) -> {
            String val = value.toString();
            if (val.startsWith("${") && val.endsWith("}")) {
                String envKey = val.substring(2, val.length() - 1);
                String envValue = System.getenv(envKey);
                if (envValue != null) {
                    properties.setProperty(key.toString(), envValue);
                } else {
                    log.warn("Environment variable '{}' is not set (referenced by property '{}')", envKey, key);
                }
            }
        });
    }

    public String getApiKey() {
        String key = properties.getProperty("google.maps.api.key");
        if (key == null || key.startsWith("${")) {
            throw new IllegalStateException(
                    "Google Maps API key not configured. Set the GOOGLE_MAPS_API_KEY environment variable.");
        }
        return key;
    }

    public int getStaticMapScale() {
        return getInt("staticmap.scale", 2);
    }

    public int getStaticMapZoom() {
        return getInt("staticmap.zoom", 18);
    }

    public int getStaticMapSize() {
        return getInt("staticmap.size", 500);
    }

    public String getRoadColor() {
        return properties.getProperty("staticmap.road.color", "0x00ff00");
    }

    public int getSkipPixels() {
        return getInt("image.skip.pixels", 50);
    }

    public double getStepSize() {
        return getDouble("image.step.size", 1.0);
    }

    public double getImageScale() {
        return getDouble("image.scale", 0.265);
    }

    public double getAngleOffset() {
        return getDouble("detection.angle.offset", 25.0);
    }

    public int getConnectTimeoutSeconds() {
        return getInt("http.connect.timeout.seconds", 5);
    }

    public int getReadTimeoutSeconds() {
        return getInt("http.read.timeout.seconds", 5);
    }

    private int getInt(String key, int defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    private double getDouble(String key, double defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? Double.parseDouble(val) : defaultValue;
    }
}