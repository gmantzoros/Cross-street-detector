# Cross-Street Detector

Image-based cross-street detection for blind pedestrian navigation. The system identifies nearby cross-streets by analyzing styled Google Maps static images where roads are highlighted in green, scanning perpendicular to the user's walking direction to find the closest intersecting road.

## How It Works

Given two consecutive GPS positions (previous and current):

1. **Bearing calculation** — determines the user's walking direction and computes two search angles (left and right) perpendicular to movement
2. **Map image retrieval** — fetches a styled Google Maps static image centered on the current position, with roads rendered in pure green (`#00FF00`) and POIs hidden
3. **Image scanning** — scans pixels outward from the image center along both search angles to detect the first green (road) pixel
4. **Point projection** — converts the closer pixel-distance detection back to geographic coordinates using spherical earth projection
5. **Road name resolution** — resolves the projected point to a road name via the Google Roads API (nearest road snap) and Geocoding API (place ID → road name)

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

Run with default test coordinates (Nea Makri, Greece):

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
  currentPosition = GeoPoint[lat=38.016989, lon=24.419658]
  targetPoint     = GeoPoint[lat=38.016871, lon=24.419103]
  distance        = 50.35 m
  searchAngle     = 254.95°
  road            = Theochari Kotsika
}
```

## Configuration

All tunable parameters are externalized in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `staticmap.zoom` | `18` | Google Maps zoom level |
| `staticmap.size` | `500` | Map image dimensions (before scaling) |
| `staticmap.scale` | `2` | Image scale factor (2 = 1000×1000 px) |
| `image.skip.pixels` | `50` | Pixels to skip from center before scanning |
| `image.scale` | `0.265` | Meters per pixel at the configured zoom level |
| `detection.angle.offset` | `25` | Degrees offset from reverse bearing (left/right) |

## Project Structure

```
src/main/java/gr/crossstreet/
├── CrossStreetDetectorApp.java   # Entry point and detection pipeline orchestrator
├── config/
│   └── AppConfig.java            # Singleton configuration (env vars + properties)
├── model/
│   ├── GeoPoint.java             # Lat/lon coordinate record
│   ├── BearingAngles.java        # Left/right search angle pair
│   └── DetectionResult.java      # Detection output with road name
├── geo/
│   └── GeoUtils.java             # Bearing calculation and point projection
├── image/
│   └── ImageProcessor.java       # Green-pixel road detection via ray scanning
└── api/
    ├── GoogleMapsClient.java     # Google Maps Static API client (OkHttp)
    └── RoadFinderClient.java     # Roads API + Geocoding API client (OkHttp + Jackson)
```

## Tech Stack

- **Java 21** — records, text blocks, pattern matching
- **Maven** — build and dependency management
- **OkHttp 4** — HTTP client for all Google API calls
- **Jackson** — JSON parsing for API responses
- **SLF4J + Logback** — structured logging

## License

MIT
