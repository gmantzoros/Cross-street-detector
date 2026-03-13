# Cross-Street Detector

Image-based cross-street detection for blind pedestrian navigation using OpenStreetMap data. Given two consecutive GPS positions, the system renders a local road map, scans perpendicular to the user's walking direction, and identifies the nearest intersecting road — all without requiring any API keys or proprietary services.

## How It Works

Given two consecutive GPS positions (previous and current):

1. **Bearing calculation** — determines the user's walking direction and computes two search angles perpendicular to movement (left and right, offset by a configurable number of degrees)
2. **Map rendering** — queries OpenStreetMap road data via the Overpass API, then renders a 1000x1000 px image locally using Java2D with roads drawn in green (`#00FF00`) on a black background
3. **Image scanning** — scans pixels radially outward from the image center along both search angles to detect the first green (road) pixel in each direction
4. **Point projection** — converts the closer pixel-distance detection back to geographic coordinates using the haversine inverse formula
5. **Road name resolution** — queries the Overpass API for named roads near the projected point and returns the closest road by perpendicular distance

The system also resolves the road in the **alternative direction** (the farther perpendicular detection), providing a fallback when the primary direction detects the user's current road instead of the cross-street.

## Prerequisites

- **Java 21+**
- **Maven 3.9+**

No API keys are needed. All road data comes from OpenStreetMap via the public Overpass API, and map images are rendered locally with Java2D.

## Build & Run

Build the fat JAR:

```bash
mvn clean package
```

Run with default test coordinates:

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar
```

Run with custom coordinates (`"current_lat, current_lon" "previous_lat, previous_lon"`):

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar "38.016989, 24.419658" "38.016959, 24.419875"
```

Run batch evaluation:

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator [input.csv] [output.csv]
```

Default paths: `src/main/resources/test-data.csv` (input) and `results/evaluation-results.csv` (output).

## Configuration

All tunable parameters are in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `overpass.api.url` | `https://overpass-api.de/api/interpreter` | Overpass API endpoint |
| `overpass.map.query.radius` | `150` | Radius (meters) for map data queries |
| `overpass.road.query.radius` | `50` | Radius (meters) for road name queries |
| `render.road.width` | `6` | Road line width in rendered image (pixels) |
| `staticmap.size` | `500` | Base image size (multiplied by scale) |
| `staticmap.scale` | `2` | Image scale factor (500 x 2 = 1000 px) |
| `staticmap.zoom` | `18` | Reference zoom level |
| `image.skip.pixels` | `50` | Pixels to skip from center before scanning |
| `image.step.size` | `1.0` | Pixel step size during radial scan |
| `image.scale` | `0.265` | Meters per pixel (calibrated for zoom 18) |
| `detection.angle.offset` | `25` | Degrees offset from perpendicular for search angles |
| `http.connect.timeout.seconds` | `10` | HTTP connection timeout |
| `http.read.timeout.seconds` | `30` | HTTP read timeout |

## Project Structure

```
src/main/java/gr/crossstreet/
├── CrossStreetDetectorApp.java        # Entry point and detection pipeline
├── BatchEvaluator.java                # Batch test runner
├── EvaluationEngine.java              # Test case evaluation with fuzzy matching
├── config/
│   └── AppConfig.java                 # Singleton config loader
├── model/
│   ├── GeoPoint.java                  # Lat/lon coordinate record
│   ├── BearingAngles.java             # Left/right search angle pair
│   ├── DetectionResult.java           # Detection output with primary + alternative
│   └── TestCase.java                  # Batch evaluation test case record
├── geo/
│   └── GeoUtils.java                  # Bearing calculation and point projection
├── image/
│   ├── ImageProcessor.java            # Radial green-pixel road scanning
│   └── DebugImageSaver.java           # Annotated debug image generation
├── api/
│   ├── OverpassClient.java            # HTTP client for Overpass API (with retry)
│   ├── OverpassMapRenderer.java       # Renders OSM roads as Java2D image + caching
│   └── OverpassRoadFinder.java        # Finds closest named road via Overpass
└── util/
    └── GreekTransliterator.java       # Greek-to-Latin transliteration (ELOT 743)
```

## Tech Stack

- **Java 21** — records, modern language features
- **Maven** — build and dependency management (fat JAR via maven-shade-plugin)
- **OkHttp 4.12** — HTTP client for Overpass API calls
- **Jackson 2.17** — JSON parsing for API responses
- **SLF4J + Logback** — structured logging
- **Java2D** — local map rendering (no external image services)
- **OpenStreetMap / Overpass API** — sole external data source (open access, no API key)

## Test Dataset

The project includes 101 test cases across 4 Greek cities (Athens, Karystos, Thessaloniki, Patra) in `src/main/resources/test-data.csv`. Validation is performed through batch evaluation with fuzzy Greek street name matching.

## License

MIT