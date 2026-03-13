# Cross-Street Detector: Technical Documentation

## Table of Contents

1. [Introduction and Motivation](#1-introduction-and-motivation)
2. [Problem Statement](#2-problem-statement)
3. [Approach Overview](#3-approach-overview)
4. [System Architecture](#4-system-architecture)
5. [Detection Pipeline -- Detailed Walkthrough](#5-detection-pipeline----detailed-walkthrough)
   - 5.1 [Step 1: Bearing Calculation](#51-step-1-bearing-calculation)
   - 5.2 [Step 2: Map Image Retrieval](#52-step-2-map-image-retrieval)
   - 5.3 [Step 3: Radial Image Scanning](#53-step-3-radial-image-scanning)
   - 5.4 [Step 4: Geographic Point Projection](#54-step-4-geographic-point-projection)
   - 5.5 [Step 5: Road Name Resolution](#55-step-5-road-name-resolution)
6. [Bidirectional Detection and Alternative Roads](#6-bidirectional-detection-and-alternative-roads)
7. [Batch Evaluation Framework](#7-batch-evaluation-framework)
   - 7.1 [Test Dataset](#71-test-dataset)
   - 7.2 [Evaluation Logic](#72-evaluation-logic)
   - 7.3 [Fuzzy String Matching for Greek Street Names](#73-fuzzy-string-matching-for-greek-street-names)
   - 7.4 [Debug Image Generation](#74-debug-image-generation)
   - 7.5 [Reporting and Output](#75-reporting-and-output)
8. [Configuration System](#8-configuration-system)
9. [Data Models](#9-data-models)
10. [Mathematical Foundations](#10-mathematical-foundations)
    - 10.1 [Forward Bearing (Azimuth) Calculation](#101-forward-bearing-azimuth-calculation)
    - 10.2 [Destination Point Projection (Direct Geodesic Problem)](#102-destination-point-projection-direct-geodesic-problem)
    - 10.3 [Geographic-to-Screen Coordinate Transformation](#103-geographic-to-screen-coordinate-transformation)
    - 10.4 [Meters-per-Pixel Calibration](#104-meters-per-pixel-calibration)
    - 10.5 [Levenshtein Edit Distance](#105-levenshtein-edit-distance)
11. [External API Integration](#11-external-api-integration)
    - 11.1 [Google Maps Static API](#111-google-maps-static-api)
    - 11.2 [Google Roads API](#112-google-roads-api)
    - 11.3 [Google Geocoding API](#113-google-geocoding-api)
12. [Image Processing Pipeline](#12-image-processing-pipeline)
    - 12.1 [Road Color Encoding](#121-road-color-encoding)
    - 12.2 [Green Pixel Detection Threshold](#122-green-pixel-detection-threshold)
    - 12.3 [Radial Scanning Algorithm](#123-radial-scanning-algorithm)
    - 12.4 [Center Skip Zone](#124-center-skip-zone)
13. [Design Decisions and Rationale](#13-design-decisions-and-rationale)
14. [Error Handling and Edge Cases](#14-error-handling-and-edge-cases)
15. [Performance Characteristics](#15-performance-characteristics)
16. [Limitations and Future Work](#16-limitations-and-future-work)
17. [Technology Stack and Dependencies](#17-technology-stack-and-dependencies)
18. [Build and Deployment](#18-build-and-deployment)

---

## 1. Introduction and Motivation

Navigating urban environments presents significant challenges for visually impaired pedestrians. While modern GPS-based navigation systems can guide users along roads, a critical piece of contextual information is often missing: the names of cross-streets at intersections. Sighted pedestrians naturally read street signs to orient themselves, confirm their position, and communicate their location to others. Blind pedestrians lack this capability.

The Cross-Street Detector addresses this gap by providing an automated system that, given a pedestrian's GPS trajectory (two consecutive position fixes), identifies the name of the nearest cross-street perpendicular to the direction of travel. This information can be delivered as audio feedback, enabling blind pedestrians to know which intersection they are approaching or passing.

The system takes a novel image-based approach: rather than performing complex geometric intersection queries on road network graph data, it leverages the Google Maps Static API to render a styled satellite-view image where roads are visually highlighted, then applies computer vision techniques (radial pixel scanning) to detect the nearest road in the perpendicular direction. This approach is conceptually simple, computationally lightweight, and leverages the comprehensive and continuously updated road data maintained by Google Maps.

---

## 2. Problem Statement

Given:
- Two consecutive GPS coordinates representing a pedestrian's recent trajectory: a **previous position** P(lat_1, lon_1) and a **current position** C(lat_2, lon_2)

Determine:
- The **name of the nearest cross-street** intersecting the pedestrian's path, perpendicular to the direction of movement

Constraints:
- The system must work in real-time for pedestrian navigation (response time under a few seconds)
- The system must handle the ambiguity of GPS noise and varying intersection geometries
- The system must correctly handle the case where the nearest detected road is the pedestrian's own road (not a cross-street)
- Street names may be in Greek with varying romanization/transliteration conventions

---

## 3. Approach Overview

The system implements a five-step pipeline that transforms raw GPS coordinates into a cross-street name:

```
GPS Coordinates (previous, current)
        |
        v
  [1] Bearing Calculation -----> Search angles (left, right)
        |
        v
  [2] Map Image Retrieval -----> 1000x1000 px styled map (green roads on black)
        |
        v
  [3] Radial Image Scanning ---> Distance in pixels/meters to nearest road
        |
        v
  [4] Point Projection --------> Geographic coordinates of detected road
        |
        v
  [5] Road Name Resolution ----> Cross-street name (e.g., "Filaretou")
```

The key insight behind this approach is that a styled map image, where roads are rendered in a known color, can serve as a spatial index for road detection. By scanning outward from the user's position along perpendicular directions, the system finds the nearest road crossing the path without needing to parse or query a road network graph directly.

The system performs detection in **both perpendicular directions** (left and right of the bearing). The closer detection is reported as the primary result, while the farther detection serves as an alternative. This bidirectional approach is critical because the closer detected road may sometimes be the user's own road (detected at a slight angle offset), in which case the alternative direction provides the actual cross-street.

---

## 4. System Architecture

The application follows a clean separation of concerns across packages:

```
gr.crossstreet/
├── CrossStreetDetectorApp     # Pipeline orchestrator
├── BatchEvaluator             # Batch evaluation runner
├── EvaluationEngine           # Single-case evaluation + fuzzy matching
│
├── config/
│   └── AppConfig              # Centralized configuration singleton
│
├── model/                     # Immutable data records
│   ├── GeoPoint               # Geographic coordinate
│   ├── BearingAngles          # Search angle pair
│   ├── DetectionResult        # Full detection output
│   └── TestCase               # Evaluation test case
│
├── geo/
│   └── GeoUtils               # Spherical geometry computations
│
├── image/
│   ├── ImageProcessor         # Radial green-pixel scanning
│   └── DebugImageSaver        # Annotated debug image generation
│
└── api/
    ├── GoogleMapsClient       # Maps Static API client
    └── RoadFinderClient       # Roads API + Geocoding API client
```

**Design principles applied:**

- **Single Responsibility**: Each class has one well-defined purpose. `GeoUtils` handles only geographic math. `ImageProcessor` handles only pixel scanning. `RoadFinderClient` handles only API communication for road name resolution.
- **Immutable Data**: All data models are Java 21 records, which are inherently immutable and provide equals/hashCode/toString automatically. This eliminates a large class of state-related bugs.
- **Configuration Externalization**: All tunable parameters live in `application.properties`, not hardcoded in source code. This enables experimentation and calibration without recompilation.
- **Graceful Degradation**: API failures return `Optional.empty()` rather than throwing exceptions, allowing the pipeline to report partial results (e.g., distance detected but road name unknown).

---

## 5. Detection Pipeline -- Detailed Walkthrough

### 5.1 Step 1: Bearing Calculation

**Class:** `GeoUtils`
**Method:** `calculateSearchAngles(GeoPoint current, GeoPoint previous, double angleOffset)`

**Purpose:** Determine the pedestrian's direction of travel and compute two search angles perpendicular to that direction.

**Process:**

1. Calculate the **forward bearing** from the previous position to the current position using the `calculateBearing()` method. This yields an angle in degrees where 0 degrees is true north and angles increase clockwise (standard geographic bearing convention).

2. Compute two search angles by offsetting the forward bearing:
   - **Left angle** = forward bearing - angleOffset (default 25 degrees)
   - **Right angle** = forward bearing + angleOffset (default 25 degrees)

3. Normalize both angles to the range [0, 360) degrees.

**Why not exactly perpendicular (90 degrees)?** The angle offset is set to 25 degrees rather than 90 degrees from the forward bearing. This means the search angles are offset 25 degrees to the left and right of the direction the user is walking. The result is that the scan lines point roughly sideways but with a slight forward lean. This design choice was made because:

- At exactly 90 degrees, the scan might miss cross-streets that are slightly ahead of or behind the pedestrian's exact position
- A 25-degree offset from the forward bearing (equivalently, 65 degrees from pure perpendicular in the backward direction) provides good coverage of intersections in the general sideward direction
- The offset was empirically calibrated through batch evaluation testing

**Example:**
If the pedestrian is walking north (bearing = 0 degrees), the search angles would be:
- Left: 360 - 25 = 335 degrees (north-northwest)
- Right: 0 + 25 = 25 degrees (north-northeast)

These two angles sweep the area ahead and to the sides, covering the likely position of an upcoming cross-street.

---

### 5.2 Step 2: Map Image Retrieval

**Class:** `GoogleMapsClient`
**Method:** `fetchStyledMap(GeoPoint center)`

**Purpose:** Obtain a top-down map image centered on the pedestrian's current position where roads are visually distinguishable from all other map features.

**Process:**

1. Construct a Google Maps Static API URL with the following parameters:
   - `center`: The current GPS coordinate
   - `zoom`: 18 (street-level detail)
   - `size`: 500x500 (base image size)
   - `scale`: 2 (doubles the output to 1000x1000 pixels for higher resolution)
   - `format`: PNG (lossless, preserves exact pixel colors)

2. Apply three critical **style parameters** that transform the map into a machine-readable image:

   | Style Rule | Effect |
   |---|---|
   | `feature:all\|color:0x000000` | Sets everything (land, water, parks, buildings) to black |
   | `feature:all\|element:labels\|visibility:off` | Hides all text labels (which could contain green pixels) |
   | `feature:road\|element:geometry\|color:0x00ff00` | Renders road geometry in pure green (#00FF00) |

3. Execute the HTTP request via OkHttp and parse the PNG response into a `BufferedImage`.

**Why this styling approach?** The styling reduces the map to a binary representation: green pixels are roads, black pixels are everything else. This eliminates the need for complex image segmentation or machine learning-based feature detection. The simplicity of the color scheme (pure green on pure black) makes pixel-level road detection trivial and highly reliable.

**Why zoom level 18?** Zoom level 18 provides street-level detail where individual road geometries are clearly visible and distinguishable. At this zoom level, the image covers approximately 265 meters across (at mid-latitudes around 38 degrees north), which is sufficient to detect cross-streets within a walkable distance while providing enough resolution to distinguish between closely spaced roads.

**Why scale factor 2?** The scale factor of 2 doubles the image from 500x500 to 1000x1000 pixels. This provides higher resolution for pixel scanning, allowing more precise distance measurements. With 1000 pixels spanning approximately 265 meters, the resolution is approximately 0.265 meters per pixel, which is adequate for pedestrian-scale navigation.

**Image characteristics:**
- Dimensions: 1000 x 1000 pixels
- Color space: RGB
- Road pixels: green channel near 255, red and blue channels near 0
- Non-road pixels: all channels near 0 (black)
- Format: PNG (lossless compression preserves exact color values)

---

### 5.3 Step 3: Radial Image Scanning

**Class:** `ImageProcessor`
**Method:** `findRoadDistance(BufferedImage image, double geographicAngle)`

**Purpose:** Starting from the center of the image (representing the pedestrian's current position), scan outward along a specified angle until a road (green pixel) is encountered, and return the distance in meters.

**Process:**

1. **Coordinate system conversion:** Convert the geographic angle (0 degrees = North, clockwise) to screen coordinates (0 degrees = East, Y-axis inverted):
   ```
   screenAngle = (geographicAngle + 270) mod 360
   ```
   This conversion is necessary because geographic bearings use North as the zero reference with clockwise rotation, while screen coordinates use East as the zero reference with the Y-axis pointing downward.

2. **Starting position:** Begin scanning from the center of the image, offset by `skipPixels` (default: 50) pixels in the scan direction:
   ```
   x = centerX + skipPixels * cos(screenAngleRad)
   y = centerY + skipPixels * sin(screenAngleRad)
   ```
   The skip zone avoids false detections from the map's center marker or other artifacts near the exact center of the image.

3. **Radial scan loop:** Step outward pixel-by-pixel along the scan direction:
   ```
   while (x, y) is within image bounds:
       color = getPixelColor(x, y)
       if isRoadPixel(color):
           pixelDistance = sqrt((x - centerX)^2 + (y - centerY)^2)
           return pixelDistance * metersPerPixel
       x += stepSize * cos(screenAngleRad)
       y += stepSize * sin(screenAngleRad)
   ```

4. **Road pixel detection:** A pixel is classified as a road pixel if:
   ```
   green > 200 AND red < 50 AND blue < 50
   ```
   This threshold allows for minor antialiasing artifacts from the map renderer while still reliably distinguishing road pixels from the black background.

5. **Distance conversion:** The pixel distance from the center to the detected road pixel is converted to meters using the calibrated scale factor:
   ```
   distanceMeters = pixelDistance * 0.265
   ```

**Output:** An `OptionalDouble` containing the distance in meters to the nearest road in the given direction, or empty if no road was found before reaching the image boundary.

---

### 5.4 Step 4: Geographic Point Projection

**Class:** `GeoUtils`
**Method:** `projectPoint(GeoPoint center, double distance, double bearing)`

**Purpose:** Convert the detected distance and angle back into geographic coordinates (latitude/longitude) representing the point on the cross-street nearest to the pedestrian.

**Process:**

Given the current position (center), the detected distance in meters, and the search angle (bearing), compute the destination point on the Earth's surface using the direct geodesic problem solution (Haversine-derived formula):

1. Convert inputs to radians
2. Compute the angular distance: `sigma = distance / R` where R = 6,371,000 meters (Earth's mean radius)
3. Compute destination latitude:
   ```
   lat2 = arcsin(sin(lat1) * cos(sigma) + cos(lat1) * sin(sigma) * cos(bearing))
   ```
4. Compute destination longitude:
   ```
   lon2 = lon1 + arctan2(sin(bearing) * sin(sigma) * cos(lat1),
                          cos(sigma) - sin(lat1) * sin(lat2))
   ```
5. Normalize longitude to [-180, 180] degrees

The implementation precomputes repeated trigonometric values (`sin(lat1)`, `cos(lat1)`, `sin(sigma)`, `cos(sigma)`, `sin(bearing)`, `cos(bearing)`) to minimize redundant computation.

**Accuracy:** For the distances involved in this application (typically 10-150 meters), the spherical Earth approximation introduces negligible error (sub-meter accuracy).

---

### 5.5 Step 5: Road Name Resolution

**Class:** `RoadFinderClient`
**Method:** `findRoadName(GeoPoint point)`

**Purpose:** Resolve the projected geographic point to the name of the road at that location.

**Process (two-stage API resolution):**

**Stage 1: Road Snapping (Google Roads API)**
- Endpoint: `https://roads.googleapis.com/v1/nearestRoads`
- Input: The projected geographic point
- Output: A `placeId` identifying the nearest road segment

The Roads API "snaps" the input coordinate to the nearest road in Google's road network. This is important because the projected point from Step 4 may not lie exactly on a road centerline (due to the discrete pixel resolution and projection approximations). The snapping step corrects for this imprecision.

The API response contains an array of `snappedPoints`, each with a `placeId` and an `originalIndex`. The system selects the snapped point with `originalIndex == 0` (corresponding to the input point), falling back to the first entry if no explicit index match is found.

**Stage 2: Name Resolution (Google Geocoding API)**
- Endpoint: `https://maps.googleapis.com/maps/api/geocode/json`
- Input: The `placeId` from Stage 1
- Output: The road name (long_name of the "route" address component)

The Geocoding API resolves the place ID to a full address, from which the system extracts the `address_component` with type `"route"`, which corresponds to the road name. The `long_name` field is used to get the full, unabbreviated road name.

**Why two API calls?** Google does not provide a single API that maps coordinates directly to road names. The Roads API provides accurate road snapping but returns place IDs, not names. The Geocoding API can resolve place IDs to names but doesn't perform road snapping. The two-stage approach combines the strengths of both APIs.

---

## 6. Bidirectional Detection and Alternative Roads

A critical design feature of the system is its **bidirectional detection** strategy. The system does not scan in just one perpendicular direction -- it scans in both directions (left and right of the bearing) and reports both results.

**Why is this necessary?**

When a pedestrian approaches an intersection, the perpendicular scan may detect the user's **own road** rather than the cross-street. This happens because the scan angles are not exactly 90 degrees perpendicular but are offset from the forward bearing. If the user is close to an intersection, a scan line might hit the user's own road at an oblique angle before reaching the cross-street in that direction.

The system handles this by:

1. Scanning both the left and right angles independently
2. Selecting the **closer** detection as the primary result
3. Selecting the **farther** detection as the alternative result
4. Reporting both in the `DetectionResult`

During evaluation (see Section 7), the evaluation engine first checks if the primary road matches the expected cross-street. If the primary instead matches the user's current road (an "acceptable" result meaning the scan hit the user's own road), the engine falls back to the alternative detection. This bidirectional strategy significantly improves accuracy.

**Data model support:**

The `DetectionResult` record carries all information for both detections:

```java
public record DetectionResult(
    GeoPoint currentPosition,       // Where the user is
    GeoPoint targetPoint,           // Projected point of primary detection
    double distanceMeters,          // Distance to primary detection
    double searchAngle,             // Angle of primary detection
    Optional<String> roadName,      // Name of primary detected road
    Optional<String> alternativeRoadName,     // Name of alternative detected road
    OptionalDouble alternativeDistanceMeters, // Distance to alternative detection
    double alternativeAngle                   // Angle of alternative detection
)
```

---

## 7. Batch Evaluation Framework

### 7.1 Test Dataset

The system includes a hand-curated evaluation dataset of **101 test cases** stored in `src/main/resources/test-data.csv`. Each test case represents a known intersection in a Greek city where the expected cross-street name is manually verified.

**Dataset structure (semicolon-delimited CSV):**

| Column | Description | Example |
|---|---|---|
| Previous Coordinates | GPS position before the current one | `37.956799, 23.698016` |
| Current Coordinates | Current GPS position near an intersection | `37.956881, 23.698128` |
| Current Road | Road the pedestrian is walking on | `Kremou` |
| Target Road | Expected cross-street to detect | `Filaretou` |
| Result | (Empty -- computed by evaluator) | |
| City | City where the intersection is located | `Athens` |

**Geographic distribution:**

| City | Number of Cases |
|---|---|
| Athens | 69 |
| Thessaloniki | 23 |
| Patra | 9 |

The dataset covers a diverse range of intersection types, road widths, and urban geometries across three major Greek cities. Coordinate pairs represent small movements (typically 10-200 meters apart), consistent with pedestrian GPS updates.

### 7.2 Evaluation Logic

**Class:** `EvaluationEngine`
**Method:** `evaluate(CrossStreetDetectorApp app, TestCase tc)`

The evaluation engine determines whether the detector correctly identified the expected cross-street for a given test case. The evaluation follows a decision tree:

```
Run detector on (current, previous) coordinates
    |
    v
Primary road matches target road? (fuzzy match)
    |
    +-- YES --> PASS (direct match)
    |
    +-- NO
         |
         v
    Try alternative road:
    Alternative matches target road? (fuzzy match)
         |
         +-- YES --> PASS (alternative used)
         |
         +-- NO --> FAIL
```

**Outcome classification:**

| Outcome | Condition | Description |
|---|---|---|
| PASS (direct) | Primary road fuzzy-matches target | Closest detected road is the expected cross-street |
| PASS (alternative) | Alternative road fuzzy-matches target | Farther detected road is the expected cross-street |
| FAIL | Neither road matches target | Neither detection produced the expected cross-street |
| ERROR | Exception during detection | API failure, timeout, or other runtime error |

The evaluation result is captured in an `EvalResult` record:

```java
record EvalResult(
    TestCase testCase,          // The original test case
    Outcome outcome,            // PASS, FAIL, or ERROR
    String detectedRoad,        // Road name(s) that determined the outcome
    int iterationsUsed,         // Forward steps taken (currently always 0)
    boolean alternativeUsed,    // Whether the alternative direction produced the PASS
    String errorMessage         // Error details if ERROR, null otherwise
)
```

### 7.3 Fuzzy String Matching for Greek Street Names

**Class:** `EvaluationEngine`
**Method:** `fuzzyMatch(String a, String b)`

Comparing detected road names against expected values requires fuzzy matching because:

1. **Transliteration variation**: Greek street names romanized to Latin characters can have multiple valid spellings. For example, the Greek letter chi (X) may be transliterated as "ch", "x", or "k". The Greek letter theta (Θ) may be "th" or "t". The diphthong OU may be "ou" or "u".

2. **Abbreviation**: First names in street names may be abbreviated. For example, "Panagiotou Tsaldari" may appear as "P. Tsaldari" or "P Tsaldari".

3. **Minor spelling differences**: Google's romanization may differ slightly from the test dataset's romanization.

The fuzzy matching algorithm applies **four matching strategies** in order, returning true on the first match:

**Strategy 1: Exact match after normalization**
Both strings are normalized (see below) and compared for exact equality.

**Strategy 2: Substring containment**
After normalization, check if either string contains the other. This catches abbreviation cases where "P Tsaldari" is a substring of "Panagi Tsaldari" (after normalization removes "ou" endings).

**Strategy 3: Last-word Levenshtein distance <= 2**
Extract the last word from each normalized string and compute their Levenshtein edit distance. If the distance is 2 or fewer character edits, the strings match. This leverages the observation that Greek street names typically end with the most distinctive part (family name), and minor transliteration differences in suffixes (e.g., "-ou" vs. "-u") result in small edit distances.

**Strategy 4: Proportional edit distance <= 30%**
Compute the Levenshtein distance of the full normalized strings. If the distance is at most 30% of the shorter string's length (minimum 2), the strings match. This provides a general-purpose fuzzy match for cases not caught by the previous strategies.

**Normalization (`normalize` method):**

The normalization process maps common Greek transliteration variants to a canonical form:

| Original | Canonical | Greek Letter/Diphthong |
|---|---|---|
| `ou` | `u` | υ / ου |
| `ch` | `k` | χ |
| `th` | `t` | θ / η (as "th") |
| `ph` | `f` | φ |
| `ai` | `e` | αι |
| `ei` | `i` | ει |
| `oi` | `i` | οι |
| `x` | `k` | χ (when written as "x") |
| `w` | `o` | ω (when written as "w") |

Multi-character substitutions are applied before single-character ones to prevent double-substitution (e.g., "ch" must be processed before individual "c" or "h" mappings would apply).

Additional normalization steps: lowercase conversion, whitespace normalization (multiple spaces collapsed to one), and trimming.

**Example matches:**

| String A | String B | Matching Strategy |
|---|---|---|
| `Thlemaxou` | `Tilemachou` | Normalized: `tlemaku` vs `tilemaku` → proportional edit distance |
| `P Tsaldari` | `Panagi Tsaldari` | Substring: normalized "p tsaldari" ⊂ "panagi tsaldari" |
| `Elaiwn` | `Eleon` | Normalized: `eleon` vs `eleon` → exact match |

### 7.4 Debug Image Generation

**Class:** `DebugImageSaver`
**Method:** `save(TestCase tc, DetectionResult detection)`

For failed test cases, the system generates annotated debug images that overlay detection results on the original styled map. These images are invaluable for diagnosing why a detection failed and for tuning system parameters.

**Annotation elements drawn on the image:**

| Element | Color | Description |
|---|---|---|
| Cross marker | Red | Current position (image center), 28px span, 2.5px stroke |
| Back-arrow | White | 70px arrow pointing toward previous position (direction of travel origin) |
| Primary hit | Yellow | Circle (10px radius) at the detected primary road point, labeled "P: [road name]" |
| Alternative hit | Cyan | Circle (10px radius) at the detected alternative road point, labeled "A: [road name]" |
| Caption bar | White on black | Bottom bar showing "#NNN \| Target: [expected] \| P: [primary] \| A: [alternative]" |

**Rendering details:**
- Uses Java2D `Graphics2D` with antialiasing enabled for smooth lines
- Text rendered with shadow (black offset +1px) for readability against both dark and light backgrounds
- Caption bar has semi-transparent background (alpha 180/255) with rounded corners (8px radius)
- Font: SansSerif Bold 13pt
- Hit point circles have both an outline (10px radius) and a filled center dot (3px radius)

**Pixel coordinate conversion:**
The detected road distances and angles (in meters and geographic degrees) are converted back to image pixel coordinates using:
```
screenAngleRad = toRadians((geographicAngle + 270) mod 360)
pixelX = centerX + (distanceMeters / metersPerPixel) * cos(screenAngleRad)
pixelY = centerY + (distanceMeters / metersPerPixel) * sin(screenAngleRad)
```

**Output:** PNG files saved to `debug/case-NNN.png` where NNN is the zero-padded test case row number.

### 7.5 Reporting and Output

**Class:** `BatchEvaluator`

The batch evaluator produces two forms of output:

**Console Report:**
A formatted table showing per-case results followed by aggregate statistics:

```
==========================================================================================
  EVALUATION RESULTS
==========================================================================================
  #001 | PASS            | Target: Theoxari Kotsika          | Got: Theochari Kotsika
  #002 | PASS (alt)      | Target: Filaretou                 | Got: Filaretou
  #003 | FAIL            | Target: Akropoleos                | Got: Mystra / Unknown
  ...

==========================================================================================
  SUMMARY
==========================================================================================
  PASS (direct):       65/101
  PASS (alternative):  12/101
  PASS (total):        77/101
  FAIL:                22/101
  ERROR:               2/101
  Success rate:        76.2%
==========================================================================================
```

**CSV Output:**
A semicolon-delimited CSV file (compatible with Greek-locale spreadsheet software like Microsoft Excel) containing:
- Row number, coordinates, road names, detected road, outcome, iterations used, whether alternative was used, and city

---

## 8. Configuration System

**Class:** `AppConfig`

The configuration system uses a **singleton pattern** with lazy initialization and thread-safe access via `synchronized getInstance()`.

**Loading process:**

1. Load `application.properties` from the classpath using `ClassLoader.getResourceAsStream()`
2. Resolve `${ENV_VAR}` placeholders by scanning all property values and replacing patterns matching `${...}` with the corresponding environment variable value
3. Log a warning if a referenced environment variable is not set

**Configuration parameters and their roles:**

| Parameter | Value | Purpose |
|---|---|---|
| `google.maps.api.key` | `${GOOGLE_MAPS_API_KEY}` | Google API authentication, resolved from environment |
| `staticmap.scale` | `2` | Doubles image resolution (500 base -> 1000px output) |
| `staticmap.zoom` | `18` | Street-level map zoom providing ~265m field of view |
| `staticmap.size` | `500` | Base image dimension before scaling |
| `staticmap.road.color` | `0x00ff00` | Road color in hex, must match detection threshold |
| `image.skip.pixels` | `50` | Center zone exclusion radius for scanning |
| `image.step.size` | `1.0` | Scanning step increment in pixels |
| `image.scale` | `0.265` | Meters-per-pixel calibration factor |
| `detection.angle.offset` | `25` | Degrees offset from forward bearing for search angles |
| `http.connect.timeout.seconds` | `5` | OkHttp TCP connection timeout |
| `http.read.timeout.seconds` | `5` | OkHttp response read timeout |

---

## 9. Data Models

All data models are implemented as Java 21 **records**, providing immutability, automatic `equals()`/`hashCode()`/`toString()`, and compact syntax.

### GeoPoint

```java
public record GeoPoint(double latitude, double longitude)
```

Represents a geographic coordinate. Provides:
- `parse(String)`: Parses "lat, lon" format strings
- `toApiString()`: Formats as "lat,lon" (no spaces) for API parameters
- Custom `toString()`: Formats as "GeoPoint[lat=X.XXXXXX, lon=X.XXXXXX]"

### BearingAngles

```java
public record BearingAngles(double leftAngle, double rightAngle)
```

Holds the two search angles derived from the movement bearing. Both angles are in degrees [0, 360).

### DetectionResult

```java
public record DetectionResult(
    GeoPoint currentPosition,
    GeoPoint targetPoint,
    double distanceMeters,
    double searchAngle,
    Optional<String> roadName,
    Optional<String> alternativeRoadName,
    OptionalDouble alternativeDistanceMeters,
    double alternativeAngle
)
```

Complete output of the detection pipeline, carrying both primary and alternative detections. Uses `Optional` and `OptionalDouble` to safely represent cases where no road was detected or no name could be resolved.

### TestCase

```java
public record TestCase(
    int rowNumber,
    GeoPoint previousCoords,
    GeoPoint currentCoords,
    String currentRoad,
    String targetRoad,
    String city
)
```

A single entry from the evaluation dataset.

---

## 10. Mathematical Foundations

### 10.1 Forward Bearing (Azimuth) Calculation

**Purpose:** Determine the direction of travel from point A to point B on the Earth's surface.

**Formula (spherical trigonometry):**

Given two points with coordinates (lat_1, lon_1) and (lat_2, lon_2) in radians:

```
delta_lon = lon_2 - lon_1

y = sin(delta_lon) * cos(lat_2)
x = cos(lat_1) * sin(lat_2) - sin(lat_1) * cos(lat_2) * cos(delta_lon)

bearing = atan2(y, x)
```

The result is converted to degrees and normalized to [0, 360) where 0 degrees is true North and angles increase clockwise.

**Complexity:** O(1) -- constant number of trigonometric operations.

**Implementation optimization:** The method precomputes `cos(lat_1)`, `sin(lat_1)`, and `cos(lat_2)` to avoid redundant evaluations.

### 10.2 Destination Point Projection (Direct Geodesic Problem)

**Purpose:** Given a starting point, distance, and bearing, compute the destination point on the Earth's surface.

**Formula (Haversine-derived):**

Given starting point (lat_1, lon_1) in radians, distance d in meters, bearing theta in radians, and Earth radius R:

```
sigma = d / R                    (angular distance)

lat_2 = arcsin(sin(lat_1) * cos(sigma) + cos(lat_1) * sin(sigma) * cos(theta))

lon_2 = lon_1 + arctan2(sin(theta) * sin(sigma) * cos(lat_1),
                         cos(sigma) - sin(lat_1) * sin(lat_2))
```

Longitude is normalized to [-180, 180] degrees after conversion.

**Assumption:** Spherical Earth model with R = 6,371,000 meters. For the distances involved (typically < 200 meters), the error from this approximation versus an ellipsoidal model is negligible (sub-centimeter).

**Implementation optimization:** Six trigonometric values are precomputed to avoid redundant `Math.sin()` and `Math.cos()` calls: `sin(lat_1)`, `cos(lat_1)`, `sin(sigma)`, `cos(sigma)`, `sin(theta)`, `cos(theta)`.

### 10.3 Geographic-to-Screen Coordinate Transformation

**Purpose:** Convert angles from geographic convention to screen pixel convention for image scanning.

**Geographic convention:**
- 0 degrees = North (up on the map)
- Angles increase clockwise
- 90 degrees = East, 180 degrees = South, 270 degrees = West

**Screen coordinate convention:**
- 0 degrees = East (right on the screen)
- Angles increase clockwise (since Y-axis points downward)
- 90 degrees = South (down), 180 degrees = West, 270 degrees = North (up)

**Conversion formula:**
```
screenAngle = (geographicAngle + 270) mod 360
```

**Derivation:** Geographic North (0 degrees) corresponds to screen "up", which is the negative Y direction. In screen coordinates with 0 degrees pointing right (East), the upward direction is 270 degrees. Adding 270 degrees to the geographic angle and taking modulo 360 achieves the correct mapping:

| Geographic | Screen | Direction |
|---|---|---|
| 0 (North) | 270 | Up |
| 90 (East) | 0 | Right |
| 180 (South) | 90 | Down |
| 270 (West) | 180 | Left |

### 10.4 Meters-per-Pixel Calibration

**Purpose:** Convert pixel distances in the map image to real-world distances in meters.

**Calibration value:** 0.265 meters per pixel

**Derivation:** At Google Maps zoom level 18 and scale factor 2, the resulting 1000x1000 pixel image covers approximately 265 meters at mid-latitudes (approximately 37-40 degrees North, the latitude range of Greece). Therefore:

```
metersPerPixel = 265 / 1000 = 0.265
```

**Latitude dependency:** The Mercator projection used by Google Maps causes the meters-per-pixel ratio to vary with latitude. At the equator, the coverage would be wider; at higher latitudes, narrower. The value 0.265 is calibrated for the Greek latitude range and would require recalibration for significantly different latitudes.

**Zoom dependency:** Changing the zoom level changes the coverage area proportionally (each zoom level doubles/halves the coverage). Changing the zoom level requires recalibrating this value.

### 10.5 Levenshtein Edit Distance

**Purpose:** Quantify the similarity between two strings for fuzzy road name matching.

**Algorithm:** Classic dynamic programming approach computing the minimum number of single-character edits (insertions, deletions, substitutions) needed to transform one string into another.

**Recurrence relation:**

```
dp[0][j] = j                    (base case: insert j characters)
dp[i][0] = i                    (base case: delete i characters)

cost = 0 if s1[i-1] == s2[j-1], else 1

dp[i][j] = min(
    dp[i-1][j] + 1,             (deletion)
    dp[i][j-1] + 1,             (insertion)
    dp[i-1][j-1] + cost         (substitution or match)
)
```

**Complexity:**
- Time: O(m * n) where m and n are string lengths
- Space: O(m * n) for the full DP matrix

For typical Greek street names (< 50 characters), this is computationally negligible.

---

## 11. External API Integration

### 11.1 Google Maps Static API

**Endpoint:** `https://maps.googleapis.com/maps/api/staticmap`

**Purpose:** Obtain a styled top-down map image.

**Request parameters used:**

| Parameter | Value | Purpose |
|---|---|---|
| `center` | `lat,lon` | Center point of the map |
| `zoom` | `18` | Zoom level (street detail) |
| `size` | `500x500` | Base image dimensions |
| `scale` | `2` | Output scaling (1000x1000 actual) |
| `format` | `png` | Lossless image format |
| `style` (x3) | See Section 5.2 | Road highlighting and background clearing |
| `key` | API key | Authentication |

**Response:** Raw PNG image bytes.

**Rate limiting:** No explicit rate limit is documented for the Static Maps API, but the batch evaluator includes a 500ms delay between consecutive requests to be respectful of the service.

### 11.2 Google Roads API

**Endpoint:** `https://roads.googleapis.com/v1/nearestRoads`

**Purpose:** Snap a coordinate to the nearest road segment and obtain its place ID.

**Request parameters:**

| Parameter | Value | Purpose |
|---|---|---|
| `points` | `lat,lon` | The coordinate to snap |
| `key` | API key | Authentication |

**Response structure (JSON):**
```json
{
  "snappedPoints": [
    {
      "location": { "latitude": 37.957, "longitude": 23.698 },
      "originalIndex": 0,
      "placeId": "ChIJ..."
    }
  ]
}
```

The system extracts the `placeId` from the snapped point matching `originalIndex == 0`.

### 11.3 Google Geocoding API

**Endpoint:** `https://maps.googleapis.com/maps/api/geocode/json`

**Purpose:** Resolve a place ID to a structured address containing the road name.

**Request parameters:**

| Parameter | Value | Purpose |
|---|---|---|
| `place_id` | Place ID from Roads API | The road segment to identify |
| `key` | API key | Authentication |

**Response parsing:** The system iterates through `results[0].address_components` and finds the component where `types` contains `"route"`. The `long_name` field of that component is the road name.

**Example response fragment:**
```json
{
  "results": [{
    "address_components": [
      { "long_name": "Filaretou", "types": ["route"] },
      { "long_name": "Athens", "types": ["locality"] }
    ]
  }]
}
```

---

## 12. Image Processing Pipeline

### 12.1 Road Color Encoding

The Google Maps Static API is instructed to render all road geometry in the color `#00FF00` (pure green). In RGB terms:
- Red: 0
- Green: 255
- Blue: 0

This color was chosen because:
1. Pure green has maximum contrast against the black background
2. Pure green is rarely present in natural map features (avoiding false positives)
3. The green channel can be isolated with simple threshold logic

### 12.2 Green Pixel Detection Threshold

A pixel is classified as a road pixel if all three conditions are met:
```
green channel > 200    (approximately 78% of max)
red channel < 50       (approximately 20% of max)
blue channel < 50      (approximately 20% of max)
```

**Why thresholds instead of exact color matching?**

The Google Maps Static API renderer applies antialiasing to road edges, producing pixels along road boundaries with intermediate color values (e.g., a pixel that is 50% road and 50% background might have RGB values around (0, 128, 0)). The threshold of green > 200 ensures that only strongly green pixels (close to the road centerline) are detected, while the red < 50 and blue < 50 thresholds ensure that other colored features (if any survived the styling) are excluded.

The relatively strict green threshold (> 200 rather than > 128) means the detection slightly underestimates road width, but this is acceptable because the goal is distance measurement, not road geometry extraction.

### 12.3 Radial Scanning Algorithm

The scanning algorithm uses a ray-casting approach from the image center along a specified angle:

```
Initialize: x = center + skip * cos(angle), y = center + skip * sin(angle)
Loop:
    if (x, y) out of bounds → return empty (no road found)
    if pixel at (x, y) is green → return distance
    x += step * cos(angle)
    y += step * sin(angle)
```

**Key characteristics:**
- **Linear time complexity:** O(n) where n is the distance from center to image boundary (at most ~500 pixels for a 1000x1000 image)
- **Sub-pixel stepping:** With step size 1.0, the algorithm checks approximately one pixel per step. Reducing step size would increase precision at the cost of scanning speed.
- **First-hit semantics:** The algorithm returns the first road encountered, which is the nearest road in the given direction. This is the desired behavior for cross-street detection.

### 12.4 Center Skip Zone

The scanning begins not at the image center but at an offset of 50 pixels (approximately 13.25 meters) from the center. This exclusion zone serves multiple purposes:

1. **Avoids the user's own road:** The center of the image is the user's current position, which is on a road. Without the skip zone, the scanner would immediately detect the user's own road.
2. **Avoids center marker artifacts:** Although the styled map hides labels and POIs, some maps may render a small center marker that could contain green pixels.
3. **Focuses on cross-streets:** By skipping the immediate vicinity, the scanner looks for the next road outward, which is more likely to be a cross-street.

---

## 13. Design Decisions and Rationale

### Why image-based detection rather than graph-based queries?

The image-based approach offers several advantages:
1. **Simplicity:** No need to parse, store, or query a road network graph. The styled map image serves as a ready-made spatial index.
2. **Completeness:** Google Maps contains the most comprehensive and up-to-date road data available, including minor roads, alleys, and recently constructed roads.
3. **Visual verification:** Debug images provide immediate visual feedback for diagnosing failures.
4. **No local data storage:** The system doesn't need to maintain or update a local road database.

The tradeoff is dependence on Google's API availability and the inherent limitations of pixel-level detection (limited by image resolution and styling accuracy).

### Why Google Maps over OpenStreetMap?

While OpenStreetMap provides freely available road data, Google Maps was chosen for:
1. **Road name accuracy:** Google's road naming data, especially for Greek streets with complex transliteration, tends to be more consistent.
2. **Roads API:** The road snapping capability ensures accurate point-to-road mapping.
3. **Styled Static Maps:** The ability to render custom-styled maps with road-only highlighting is built into the API.

### Why 25-degree angle offset?

The 25-degree offset was determined through empirical testing via batch evaluation. Key observations:
- At exactly perpendicular (90 degrees from bearing), many intersections were missed because the pedestrian was not yet at the exact intersection point
- Smaller offsets (closer to the forward bearing) improved detection of upcoming intersections
- Very small offsets (< 15 degrees) caused the scanner to detect distant roads along the forward direction rather than nearby cross-streets
- 25 degrees provided the best balance between coverage and specificity in the test dataset

### Why fuzzy matching for road names?

Greek transliteration is inherently ambiguous. The same Greek street name can be romanized multiple ways depending on the transliteration system used. The test dataset and Google's API may use different romanization conventions. Fuzzy matching with Greek-specific normalization ensures that transliteration differences don't cause false negatives in evaluation.

### Why Java 21 records?

Records provide:
- Immutability (no setter methods, fields are final)
- Automatic equals/hashCode based on all fields
- Automatic toString for debugging
- Compact syntax that reduces boilerplate
- Clear intent: records are purely data carriers, not behavior-rich objects

---

## 14. Error Handling and Edge Cases

### No road detected

If the radial scan reaches the image boundary without finding a green pixel, `findRoadDistance()` returns `OptionalDouble.empty()`. The pipeline handles this gracefully:
- If both directions return empty, the `DetectionResult` contains zeros and empty Optionals
- If one direction returns empty, the other is used as primary

### API failures

All Google API calls are wrapped in try-catch blocks:
- `GoogleMapsClient.fetchStyledMap()`: Throws `IOException` on HTTP errors or image parsing failures, propagated to caller
- `RoadFinderClient.findRoadName()`: Returns `Optional.empty()` on any failure, logging a warning
- `RoadFinderClient.fetchNearestRoadPlaceId()`: Returns `Optional.empty()` if no snapped points in response
- `RoadFinderClient.resolveRoadNameFromPlaceId()`: Returns `Optional.empty()` if no "route" type address component found

### Evaluation error handling

The `EvaluationEngine.evaluate()` method wraps the entire detection in a try-catch, returning an `ERROR` outcome rather than throwing. This ensures one failed test case doesn't abort the entire batch.

### CSV parsing resilience

The `BatchEvaluator.parseLine()` method:
- Auto-detects semicolon vs. comma delimiter
- Skips lines with fewer than 4 columns
- Logs warnings for unparseable lines and continues with the rest

### Configuration resilience

`AppConfig` provides default values for all numeric properties, so missing properties in the configuration file won't crash the application. Only the API key is required (throws `IllegalStateException` if missing).

---

## 15. Performance Characteristics

### Per-detection time complexity

| Step | Complexity | Typical Duration |
|---|---|---|
| Bearing calculation | O(1) | < 1 ms |
| Map image fetch | O(1) network | 200-500 ms |
| Image scanning (x2) | O(n), n <= 500 | < 5 ms |
| Point projection | O(1) | < 1 ms |
| Road name resolution (x2) | O(1) network | 400-1000 ms |
| **Total** | | **~1-2 seconds** |

The bottleneck is network latency for API calls. The computational steps (bearing, scanning, projection) are negligible.

### Batch evaluation throughput

With a 500ms inter-request delay, a 101-case batch takes approximately:
```
101 cases * (1.5s detection + 0.5s delay) = ~3-4 minutes
```

### Memory usage

- Map images: ~3 MB per 1000x1000 PNG in memory (BufferedImage)
- Only one image is held in memory at a time (no caching)
- Levenshtein DP matrix: O(m*n) where m, n are string lengths (< 2500 cells for typical names)

---

## 16. Limitations and Future Work

### Current limitations

1. **Google API dependency:** The system requires internet connectivity and a valid Google Maps API key. API changes or outages would break functionality.
2. **Latitude calibration:** The meters-per-pixel value (0.265) is calibrated for Greek latitudes (~37-40 degrees N). Usage at significantly different latitudes requires recalibration.
3. **Single-road detection:** The system detects the nearest road in each direction but doesn't identify multi-road intersections (e.g., five-way intersections).
4. **Static GPS positions:** The system requires exactly two GPS fixes. It doesn't perform continuous tracking or sliding-window detection.
5. **No road type filtering:** The system treats all roads equally. It cannot distinguish between a major avenue and a parking lot access road.
6. **Greek-specific evaluation:** The fuzzy matching normalization is tailored for Greek transliteration variants. Other languages would require different normalization rules.

### Potential improvements

1. **Continuous tracking:** Integrate with a GPS stream to provide real-time cross-street announcements as the pedestrian approaches intersections.
2. **Multi-angle scanning:** Scan at multiple angles (not just two) to detect complex intersection geometries.
3. **Road type weighting:** Use different road rendering colors or widths by road type, prioritizing major roads in detection.
4. **Caching:** Cache map images and road names for recently visited areas to reduce API calls and latency.
5. **Offline mode:** Pre-download road data for known routes to enable operation without internet.
6. **Machine learning enhancement:** Train a model on debug images to learn intersection patterns and improve detection accuracy.

---

## 17. Technology Stack and Dependencies

| Component | Technology | Version | Purpose |
|---|---|---|---|
| Language | Java | 21 | Records, text blocks, pattern matching, enhanced switch |
| Build | Apache Maven | 3.9+ | Dependency management, fat JAR packaging |
| HTTP Client | OkHttp | 4.12.0 | All Google API HTTP requests |
| JSON Parser | Jackson Databind | 2.17.0 | Roads API and Geocoding API response parsing |
| Logging API | SLF4J | 2.0.12 | Logging facade (decoupled from implementation) |
| Logging Impl | Logback | 1.5.3 | Structured logging output |
| Packaging | Maven Shade Plugin | 3.5.2 | Fat JAR with all dependencies embedded |
| Compiler | Maven Compiler Plugin | 3.13.0 | Java 21 source/target configuration |
| Image Processing | Java2D (AWT) | Built-in | Image rendering and pixel-level analysis |

**Why OkHttp over java.net.HttpURLConnection?**
OkHttp provides a cleaner API, built-in URL building (`HttpUrl.Builder`), configurable timeouts, connection pooling, and automatic retry on transient failures.

**Why Jackson over org.json?**
Jackson provides a tree-based API (`JsonNode`) similar to org.json but with better performance, streaming support, and the ability to deserialize directly into Java objects if needed in the future.

---

## 18. Build and Deployment

### Building

```bash
mvn clean package
```

This produces a fat JAR at `target/Cross-street-detector-1.0-SNAPSHOT.jar` containing all dependencies (OkHttp, Jackson, SLF4J, Logback) via the Maven Shade Plugin.

### Running single detection

```bash
export GOOGLE_MAPS_API_KEY=your_key
java -jar target/Cross-street-detector-1.0-SNAPSHOT.jar "prev_lat, prev_lon" "curr_lat, curr_lon"
```

### Running batch evaluation

```bash
export GOOGLE_MAPS_API_KEY=your_key
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.BatchEvaluator [input.csv] [output.csv]
```

Default paths:
- Input: `src/main/resources/test-data.csv`
- Output: `results/evaluation-results.csv`

### Requirements

- Java Runtime Environment 21 or later
- Internet connectivity for Google API calls
- Google Maps API key with Maps Static API, Roads API, and Geocoding API enabled