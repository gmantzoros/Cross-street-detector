package gr.crossstreet.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-disk cache of raw Overpass responses, keyed by the query string.
 * <p>
 * Keying on the full query text rather than on coordinates means every input that
 * can change the response — bounding box, highway filter, output format — is part
 * of the key. Changing the query radius or the set of highway types therefore
 * misses correctly instead of silently replaying data fetched under the old query.
 * <p>
 * Entries never expire. For a parameter sweep that is the desired behaviour: the
 * road data must stay fixed across runs, otherwise a change in results cannot be
 * attributed to the parameter under test rather than to an edit in OpenStreetMap.
 * Delete the cache directory to pick up fresh data.
 * <p>
 * The cache is strictly an optimisation: every failure path degrades to a miss and
 * is logged rather than propagated, so a broken cache can never fail a detection.
 */
public class OverpassCache {

    private static final Logger log = LoggerFactory.getLogger(OverpassCache.class);

    private final Path directory;
    private final boolean enabled;

    private int hits;
    private int misses;

    public OverpassCache(Path directory, boolean enabled) {
        this.directory = directory;
        this.enabled = enabled;

        if (!enabled) {
            log.info("Overpass disk cache disabled");
            return;
        }
        try {
            Files.createDirectories(directory);
            log.info("Overpass disk cache at {}", directory.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not create cache directory {} — caching disabled for this run: {}",
                    directory, e.getMessage());
        }
    }

    /**
     * Returns the cached raw JSON body for a query, or empty on a miss.
     * A corrupt or unreadable entry is deleted and reported as a miss.
     */
    public Optional<String> get(String query) {
        if (!enabled) return Optional.empty();

        Path entry = entryPath(query);
        if (!Files.isRegularFile(entry)) {
            misses++;
            return Optional.empty();
        }

        try (InputStream in = new GZIPInputStream(Files.newInputStream(entry))) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            hits++;
            log.debug("Cache hit: {}", entry.getFileName());
            return Optional.of(json);
        } catch (IOException e) {
            log.warn("Discarding unreadable cache entry {}: {}", entry.getFileName(), e.getMessage());
            deleteQuietly(entry);
            misses++;
            return Optional.empty();
        }
    }

    /**
     * Stores a successful response. Written to a temporary file and moved into place
     * so that an interrupted run cannot leave a truncated entry behind.
     */
    public void put(String query, String json) {
        if (!enabled) return;

        Path entry = entryPath(query);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, "write-", ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            move(temp, entry);
            temp = null;
            log.debug("Cached response as {}", entry.getFileName());
        } catch (IOException e) {
            log.warn("Could not write cache entry for query: {}", e.getMessage());
        } finally {
            if (temp != null) deleteQuietly(temp);
        }
    }

    /** Logs hit/miss totals for the run. */
    public void logStatistics() {
        if (!enabled) return;
        int total = hits + misses;
        if (total == 0) return;
        log.info("Overpass cache: {} hits, {} misses ({}% hit rate)",
                hits, misses, Math.round((hits * 100.0) / total));
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path entryPath(String query) {
        return directory.resolve(sha256(query) + ".json.gz");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing useful to do — the entry will simply be overwritten next time.
        }
    }
}
