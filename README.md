# Cross-Street Detector

Geometry-based cross-street detection for blind pedestrian navigation. Given two consecutive GPS positions and the name of the road the user is walking on, the system identifies the nearest cross-street ahead by computing segment-segment intersections between the current road and all other named roads using OpenStreetMap data from the Overpass API.

No API keys or paid services are required. All road data comes from OpenStreetMap (open data), and all computation is performed locally.

## How It Works

Given two consecutive GPS positions (previous and current) and the current road name:

1. **Bearing calculation** — computes the user's forward bearing (walking direction) from the previous position to the current position using spherical trigonometry
2. **Road data retrieval** — fetches all nearby road geometries from the Overpass API within a configurable radius (default 200m), with spatial caching to avoid redundant queries
3. **Intersection detection** — projects all road geometries into a local 2D meter-based coordinate system, then performs segment-segment intersection tests between the current road's segments and all other named roads' segments
4. **Forward filtering** — discards any intersections that lie behind the user (bearing difference >= 90 degrees from forward direction)
5. **Result selection** — returns the closest forward intersection, along with the cross-street name resolved from OSM tags

If the current road name is not provided, the system auto-detects it by finding the nearest named road to the user's position using point-to-segment distance calculations.

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

### Single Detection

Run with default test coordinates (Athens, Greece):

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar
```

Run with custom coordinates:

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar "38.016959, 24.419875" "38.016989, 24.419658"
```

Run with custom coordinates and explicit current road name:

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar "38.016959, 24.419875" "38.016989, 24.419658" "Gerestou"
```

Example output:

```
DetectionResult {
  currentPosition      = GeoPoint[lat=38.016989, lon=24.419658]
  targetPoint          = GeoPoint[lat=38.016871, lon=24.419103]
  distance             = 50.35 m
  bearingToIntersection = 254.95°
  road                 = Theochari Kotsika
}
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
| `overpass.road.query.radius` | `50` | Radius for road name resolution queries |
| `http.connect.timeout.seconds` | `10` | HTTP connection timeout |
| `http.read.timeout.seconds` | `30` | HTTP read timeout |

Properties support `${ENV_VAR}` placeholders that are resolved from environment variables at startup.

## Project Structure

```
src/main/java/gr/crossstreet/
├── CrossStreetDetectorApp.java      # Entry point and detection pipeline orchestrator
├── BatchEvaluator.java              # Batch runner for CSV test cases
├── EvaluationEngine.java            # Per-case evaluation with fuzzy matching
├── config/
│   └── AppConfig.java               # Singleton configuration with env var substitution
├── model/
│   ├── GeoPoint.java                # Lat/lon coordinate record
│   ├── BearingAngles.java           # Left/right search angle pair
│   ├── DetectionResult.java         # Detection output with road name
│   └── TestCase.java                # Batch evaluation test case record
├── geo/
│   ├── GeoUtils.java                # Bearing, Haversine distance, point projection
│   └── IntersectionDetector.java    # Segment-segment intersection & fuzzy name matching
├── image/
│   └── DebugImageSaver.java         # Annotated debug image renderer for failed cases
├── api/
│   └── OverpassClient.java          # Overpass API client with retry logic
└── util/
    └── GreekTransliterator.java     # Greek-to-Latin transliteration (ELOT 743-like)
```

## Tech Stack

- **Java 21** — records, text blocks, pattern matching
- **Maven** — build and dependency management
- **OkHttp 4** — HTTP client for Overpass API calls
- **Jackson** — JSON parsing for API responses
- **SLF4J + Logback** — structured logging
- **OpenStreetMap / Overpass API** — sole external data source (open access, no API key)

## License

MIT