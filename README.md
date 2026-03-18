# Cross-Street Detector

Geometry-based cross-street detection for blind pedestrian navigation. Given two consecutive GPS positions, the system automatically detects the current road and identifies the nearest cross-street ahead by computing segment-segment intersections between the current road and all other named roads using OpenStreetMap data from the Overpass API.

No API keys or paid services are required. All road data comes from OpenStreetMap (open data), and all computation is performed locally.

## How It Works

Given two consecutive GPS positions (previous and current):

1. **Bearing calculation** — computes the user's forward bearing (walking direction) from the previous position to the current position using spherical trigonometry
2. **Road data retrieval** — fetches all nearby road geometries from the Overpass API within a configurable radius (default 200m), with spatial caching to avoid redundant queries
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

### REST API Server (default)

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

Run the batch evaluator against the included test dataset (100 real-world cases from Greek cities):

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator
```

Custom input/output paths:

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator input.csv output.csv
```

Results are written to `results/evaluation-results.csv` and debug images for failed cases are saved to `debug/`.

## Configuration

All tunable parameters are in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `overpass.api.url` | `https://overpass-api.de/api/interpreter` | Overpass API endpoint |
| `overpass.map.query.radius` | `200` | Radius in meters for road data queries |
| `http.connect.timeout.seconds` | `10` | HTTP connection timeout |
| `http.read.timeout.seconds` | `30` | HTTP read timeout |

Properties support `${ENV_VAR}` placeholders that are resolved from environment variables at startup.

## Project Structure

```
src/main/java/gr/crossstreet/
├── ApiServer.java                   # REST API server (Javalin) — default entry point
├── CrossStreetDetectorApp.java      # Detection pipeline orchestrator and CLI entry point
├── BatchEvaluator.java              # Batch runner for CSV test cases
├── EvaluationEngine.java            # Per-case evaluation logic
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
│   └── OverpassClient.java          # Overpass API client with retry logic
└── util/
    ├── GreekTransliterator.java     # Greek-to-Latin transliteration (ELOT 743-like)
    └── RoadNameMatcher.java         # Fuzzy Greek road name matching (shared utility)
```

## Tech Stack

- **Java 21** — records, text blocks, pattern matching
- **Maven** — build and dependency management
- **Javalin 6** — lightweight REST API server
- **OkHttp 4** — HTTP client for Overpass API calls
- **Jackson** — JSON parsing for API responses and REST serialization
- **SLF4J + Logback** — structured logging
- **OpenStreetMap / Overpass API** — sole external data source (open access, no API key)

## License

MIT