# Cross-Street Detector

An image-based cross-street detection system designed for blind pedestrian navigation assistance. Given two consecutive GPS positions representing a pedestrian's trajectory, the system identifies the nearest intersecting road by analyzing styled Google Maps static images where roads are rendered in pure green against a black background.

## How It Works

The detection pipeline operates in five sequential steps:

1. **Bearing Calculation** -- Computes the user's walking direction from two consecutive GPS fixes and derives two search angles perpendicular (left and right) to the movement bearing, offset by a configurable angle (default 25 degrees)
2. **Map Image Retrieval** -- Fetches a 1000x1000 pixel styled Google Maps Static API image centered on the current position, with roads rendered in pure green (`#00FF00`) on a black background, all labels and POIs hidden
3. **Radial Image Scanning** -- Scans pixels outward from the image center along both search angles to detect the first green (road) pixel, skipping an initial 50-pixel zone around the center to avoid marker noise
4. **Geographic Point Projection** -- Converts the closer pixel-distance detection back to geographic coordinates using spherical Earth projection (Haversine-derived formula, Earth radius = 6,371 km)
5. **Road Name Resolution** -- Resolves the projected geographic point to a road name via the Google Roads API (nearest road snap) followed by the Geocoding API (place ID to road name)

Both directions (left and right of the bearing) are resolved independently. The closer detection is reported as the primary result, and the farther detection as the alternative, providing a fallback when the primary matches the user's current road rather than the cross-street.

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **Google Maps API key** with the following APIs enabled:
  - Maps Static API
  - Roads API
  - Geocoding API

## Setup

Clone the repository:

```bash
git clone https://github.com/YOUR_USERNAME/cross-street-detector.git
cd cross-street-detector
```

Set your API key as an environment variable:

```bash
# Linux / macOS
export GOOGLE_MAPS_API_KEY=your_api_key_here

# Windows (cmd)
set GOOGLE_MAPS_API_KEY=your_api_key_here

# Windows (PowerShell)
$env:GOOGLE_MAPS_API_KEY="your_api_key_here"
```

Build the project:

```bash
mvn clean package
```

## Usage

### Single Detection

Run with default test coordinates (Athens, Greece):

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar
```

Run with custom coordinates (`"previous_lat, previous_lon" "current_lat, current_lon"`):

```bash
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar "38.016959, 24.419875" "38.016989, 24.419658"
```

Example output:

```
DetectionResult {
  currentPosition  = GeoPoint[lat=38.016989, lon=24.419658]
  targetPoint      = GeoPoint[lat=38.016871, lon=24.419103]
  distance         = 50.35 m
  searchAngle      = 254.95°
  road             = Theochari Kotsika
  alternativeRoad  = Gerestou (32.10 m, 304.95°)
}
```

### Batch Evaluation

Run the evaluator against the built-in test dataset (101 Greek intersection test cases):

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator
```

Or specify custom input/output paths:

```bash
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator input.csv results/output.csv
```

The evaluator produces:
- A formatted console report with per-case PASS/FAIL/ERROR outcomes and aggregate statistics
- A semicolon-delimited CSV file compatible with Greek-locale spreadsheet software
- Annotated debug images in `debug/` for failed cases (showing detected road hit points overlaid on the styled map)

## Configuration

All tunable parameters are externalized in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `google.maps.api.key` | `${GOOGLE_MAPS_API_KEY}` | Google Maps API key (resolved from environment) |
| `staticmap.zoom` | `18` | Google Maps zoom level |
| `staticmap.size` | `500` | Map image base dimensions (before scaling) |
| `staticmap.scale` | `2` | Image scale factor (2 = 1000x1000 px output) |
| `staticmap.road.color` | `0x00ff00` | Road rendering color (must match detection threshold) |
| `image.skip.pixels` | `50` | Pixels to skip from center before scanning |
| `image.step.size` | `1.0` | Radial scan step increment (pixels) |
| `image.scale` | `0.265` | Meters per pixel at zoom 18, mid-latitudes |
| `detection.angle.offset` | `25` | Degrees offset left/right from forward bearing |
| `http.connect.timeout.seconds` | `5` | OkHttp connection timeout |
| `http.read.timeout.seconds` | `5` | OkHttp read timeout |

## Project Structure

```
src/main/java/gr/crossstreet/
├── CrossStreetDetectorApp.java   # Entry point and 5-step detection pipeline orchestrator
├── BatchEvaluator.java           # Batch test runner: CSV loading, evaluation, reporting
├── EvaluationEngine.java         # Single-case evaluation logic with fuzzy Greek name matching
├── config/
│   └── AppConfig.java            # Singleton configuration (env var resolution + properties)
├── model/
│   ├── GeoPoint.java             # Lat/lon coordinate record with parsing
│   ├── BearingAngles.java        # Left/right search angle pair
│   ├── DetectionResult.java      # Detection output (primary + alternative)
│   └── TestCase.java             # Test case record (coordinates, roads, city)
├── geo/
│   └── GeoUtils.java             # Bearing calculation, point projection (spherical geometry)
├── image/
│   ├── ImageProcessor.java       # Green-pixel road detection via radial ray scanning
│   └── DebugImageSaver.java      # Annotated debug image generation for failed cases
└── api/
    ├── GoogleMapsClient.java     # Google Maps Static API client (styled map fetching)
    └── RoadFinderClient.java     # Roads API + Geocoding API client (point-to-road-name)
```

## Test Dataset

The project includes 101 hand-curated test cases in `src/main/resources/test-data.csv` covering intersections across three Greek cities:

| City | Cases |
|---|---|
| Athens | 69 |
| Thessaloniki | 23 |
| Patra | 9 |

Each test case contains two consecutive GPS coordinates, the current road name, and the expected cross-street name. The evaluator uses fuzzy string matching with Greek transliteration normalization to compare detected road names against expected values.

## Tech Stack

- **Java 21** -- records, text blocks, pattern matching, enhanced switch
- **Maven** -- build and dependency management (fat JAR via maven-shade-plugin)
- **OkHttp 4.12.0** -- HTTP client for all Google API calls
- **Jackson 2.17.0** -- JSON parsing for Roads and Geocoding API responses
- **SLF4J 2.0.12 + Logback 1.5.3** -- structured logging

## License

MIT