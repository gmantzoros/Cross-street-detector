# Cross-Street Detector

Geometry-based cross-street detection for blind pedestrian navigation. Given two consecutive GPS positions, the system automatically detects the current road and identifies the nearest cross-street ahead by computing segment-segment intersections between the current road and all other named roads using OpenStreetMap data from the Overpass API.

No API keys or paid services are required. All road data comes from OpenStreetMap (open data), and all computation is performed locally.

## How It Works

Given two consecutive GPS positions (previous and current):

1. **Bearing calculation** — computes the user's forward bearing (walking direction) from the previous position to the current position using spherical trigonometry
2. **Road data retrieval** — fetches all nearby road geometries from the Overpass API within a configurable radius (default 200m), served from an on-disk cache when the same query has been made before, and failing over across several Overpass mirrors when one is slow or rate-limiting
3. **Current road detection** — auto-detects the road the user is walking on by finding the nearest named road to the current position using point-to-segment distance calculations
4. **Intersection detection** — projects all road geometries into a local 2D meter-based coordinate system, then performs segment-segment intersection tests between the current road's segments and all other named roads' segments
5. **Forward filtering** — discards any intersections that lie behind the user (bearing difference >= 90 degrees from forward direction)
6. **Result selection** — returns the closest forward intersection, along with the cross-street name resolved from OSM tags

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- No API keys needed

## Setup

Clone the repository and build:

```bash
git clone https://github.com/nickoarg/cross-street-detector.git
cd cross-street-detector
mvn clean package
```

## Usage

### REST API Server

> **Not available on this branch.** `ApiServer.java` has been removed on `openaccess-noimage`, but `pom.xml` still declares `gr.crossstreet.ApiServer` as the shade plugin's `Main-Class`, so `java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar` fails with `ClassNotFoundException`. Use the CLI or batch entry points below. The section below documents the API as it exists on `master`.

The fat JAR starts a Javalin HTTP server on port 8080:

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar
```

Start on a custom port:

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar 3000
```

#### Endpoints

**`GET /detect`** — Detect the nearest cross-street ahead.

Query parameters:
- `prev` — previous GPS position as `lat,lon`
- `curr` — current GPS position as `lat,lon`

Example:

```bash
curl "http://localhost:8080/detect?prev=37.988891,23.741949&curr=37.988988,23.741867"
```

Response:

```json
{
  "currentPosition": {"latitude": 37.988988, "longitude": 23.741867},
  "targetPoint": {"latitude": 37.988871, "longitude": 23.741103},
  "distance": 50.35,
  "bearingToIntersection": 254.95,
  "roadName": "Theochari Kotsika"
}
```

**`GET /health`** — Health check (returns `{"status": "ok"}`).

### CLI Mode

Run the detection pipeline directly (bypasses the API server):

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.CrossStreetDetectorApp "37.988891, 23.741949" "37.988988, 23.741867"
```

Run with default test coordinates (Athens, Greece):

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.CrossStreetDetectorApp
```

### Batch Evaluation

Run the batch evaluator against the included test dataset (500 annotated real-world cases from Athens, Thessaloniki, Patras, Larissa, Volos, and Heraklion):

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator
```

Custom input/output paths:

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator \
  src/main/resources/test_data_annotation_all.csv results/evaluation-results_all.csv
```

Per-city datasets are also included (`test_data_annotation_athens.csv`, `..._volos.csv`, and so on). Results default to `results/evaluation-results_all_sens10.csv`; debug images for failed cases are saved to `debug/`.

A first run over the full dataset issues roughly 450 Overpass requests and takes around 30 minutes, dominated by the rate-limit delay. Once the cache is warm, re-runs issue no network requests and complete in seconds — see [Overpass Access and Caching](#overpass-access-and-caching).

## Overpass Access and Caching

Road data is the only external dependency, and the public Overpass servers are the main source of run-to-run flakiness. Three mechanisms address this.

### Endpoint failover

`overpass.api.urls` holds a comma-separated list of endpoints tried in priority order. A timeout, a `429`, or any `5xx` causes an immediate retry against the *next* endpoint rather than a retry against the one that just refused the request. Only after a full pass over every endpoint fails does the client back off exponentially. Whichever endpoint answers last is remembered and tried first next time, so a batch run that fails over does not keep re-probing a dead mirror.

> **Every configured endpoint must serve planet-wide data.** Regional instances such as `overpass.osm.ch` (Switzerland only) or `overpass.osm.jp` (Japan only) answer `200 OK` with an empty element list for Greek coordinates. Failover cannot distinguish that from a genuine "no roads nearby" result, so a regional mirror would silently fill the results with false negatives instead of producing an error. The client logs a warning whenever an endpoint returns zero ways.

### On-disk response cache

Successful Overpass responses are gzipped to `cache/<sha256>.json.gz`, keyed by a hash of the full query string.

Keying on the query text — rather than on coordinates — means every input that can change the response is part of the key: the bounding box, the highway-type filter, and the output format. Changing `overpass.map.query.radius` or the set of highway types therefore misses correctly, instead of replaying data fetched under the old query.

Entries never expire. For a parameter sweep this is deliberate: the road data must stay fixed across runs, otherwise a change in results cannot be attributed to the parameter under test rather than to an edit in OpenStreetMap. To refetch, delete the directory:

```bash
rm -rf cache/
```

The cache is strictly an optimisation. Only successful, parseable responses are stored, writes are atomic so an interrupted run cannot leave a truncated entry, and every failure path degrades to a cache miss — a broken cache can never fail a detection.

Because the geometry parameters in `IntersectionDetector` (such as `MIN_CROSSING_ANGLE_DEG`) do not appear in the Overpass query, a sensitivity sweep re-uses the cache completely and runs entirely offline after the first pass.

### Rate limiting

`overpass.rate.limit.delay.ms` is enforced by `OverpassClient` immediately before each outgoing request, not by the batch loop. Cache hits and queries served by the in-memory spatial cache are therefore never delayed, and the throttle applies only to real network calls.

## Configuration

All tunable parameters are in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `overpass.api.urls` | `openstreetmap.fr`, `overpass-api.de`, `maps.mail.ru` | Comma-separated endpoints, tried in order. Must be planet-wide instances |
| `overpass.user.agent` | `CrossStreetDetector/1.0 (…)` | Sent on every request; several mirrors require a contactable User-Agent |
| `overpass.map.query.radius` | `200` | Radius in meters for road data queries |
| `overpass.cache.enabled` | `true` | Enables the on-disk response cache |
| `overpass.cache.dir` | `cache` | Cache directory |
| `overpass.rate.limit.delay.ms` | `3000` | Minimum gap between outgoing Overpass requests |
| `http.connect.timeout.seconds` | `5` | HTTP connection timeout. Kept short because the mirrors are dual-stack, so a dead endpoint spends this budget once per address family before failover |
| `http.read.timeout.seconds` | `60` | HTTP read timeout |

Properties support `${ENV_VAR}` placeholders resolved from environment variables at startup. A placeholder whose variable is unset is dropped, so the code falls back to its built-in default rather than receiving the literal `${VAR}` string.

### Self-hosting Overpass

For heavy use, a local Overpass instance removes the rate limits entirely. A Greece extract needs roughly 4–8 GB with metadata disabled:

```bash
docker volume create overpass_greece_db

docker run -d --name overpass_greece \
  -e OVERPASS_META=no \
  -e OVERPASS_MODE=init \
  -e OVERPASS_PLANET_URL=https://download.geofabrik.de/europe/greece-latest.osm.bz2 \
  -e OVERPASS_DIFF_URL=https://download.geofabrik.de/europe/greece-updates/ \
  -e OVERPASS_USE_AREAS=false \
  -e OVERPASS_ALLOW_DUPLICATE_QUERIES=yes \
  -v overpass_greece_db:/db -p 12345:80 \
  wiktorn/overpass-api
```

`OVERPASS_USE_AREAS=false` skips area generation, which is the slow part of initialisation and is unnecessary for bounding-box queries. Initialisation takes 15–40 minutes; then set `overpass.api.urls=http://localhost:12345/api/interpreter`.

## Project Structure

```
src/main/java/gr/crossstreet/
├── CrossStreetDetectorApp.java      # Detection pipeline orchestrator and CLI entry point
├── BatchEvaluator.java              # Batch runner for CSV test cases
├── EvaluationEngine.java            # Per-case evaluation logic
├── DatasetEnrichment.java           # Adds derived columns to the annotated dataset
├── DatasetStatistics.java           # Aggregate statistics over the dataset
├── TestDataGenerator.java           # Generates candidate test cases
├── config/
│   └── AppConfig.java               # Singleton configuration with env var substitution
├── model/
│   ├── GeoPoint.java                # Lat/lon coordinate record
│   ├── DetectionResult.java         # Detection output with road name
│   └── TestCase.java                # Batch evaluation test case record
├── geo/
│   ├── GeoUtils.java                # Bearing, Haversine distance, point projection
│   └── IntersectionDetector.java    # Segment-segment intersection detection
├── image/
│   └── DebugImageSaver.java         # Annotated debug image renderer for failed cases
├── api/
│   ├── OverpassClient.java          # Overpass client with endpoint failover and throttling
│   └── OverpassCache.java           # Gzipped on-disk cache of raw Overpass responses
└── util/
    ├── GreekTransliterator.java     # Greek-to-Latin transliteration (ELOT 743-like)
    └── RoadNameMatcher.java         # Fuzzy Greek road name matching (shared utility)
```

`ApiServer.java` (Javalin REST server) exists on `master` but has been removed on this branch.

## Tech Stack

- **Java 21** — records, text blocks, pattern matching
- **Maven** — build and dependency management
- **Javalin 6** — lightweight REST API server
- **OkHttp 4** — HTTP client for Overpass API calls
- **Jackson** — JSON parsing for API responses and REST serialization
- **SLF4J + Logback** — structured logging
- **OpenStreetMap / Overpass API** — sole external data source (open access, no API key), accessed through mirrored endpoints with an on-disk response cache

## License

MIT