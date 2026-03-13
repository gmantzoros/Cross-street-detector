# Cross-Street Detector — Technical Documentation

## Table of Contents

1. [Introduction and Motivation](#1-introduction-and-motivation)
2. [System Overview](#2-system-overview)
3. [Detection Pipeline](#3-detection-pipeline)
   - 3.1 [Bearing Calculation](#31-bearing-calculation)
   - 3.2 [Map Data Acquisition and Rendering](#32-map-data-acquisition-and-rendering)
   - 3.3 [Radial Image Scanning](#33-radial-image-scanning)
   - 3.4 [Geographic Point Projection](#34-geographic-point-projection)
   - 3.5 [Road Name Resolution](#35-road-name-resolution)
4. [Alternative Road Mechanism](#4-alternative-road-mechanism)
5. [Caching Strategy](#5-caching-strategy)
6. [Greek Street Name Handling](#6-greek-street-name-handling)
   - 6.1 [Transliteration](#61-transliteration)
   - 6.2 [Fuzzy Matching](#62-fuzzy-matching)
7. [Batch Evaluation System](#7-batch-evaluation-system)
8. [Debug Image Generation](#8-debug-image-generation)
9. [Configuration and Calibration](#9-configuration-and-calibration)
10. [Data Models](#10-data-models)
11. [External Dependencies and Infrastructure](#11-external-dependencies-and-infrastructure)
12. [Design Decisions and Trade-offs](#12-design-decisions-and-trade-offs)
13. [Test Dataset](#13-test-dataset)

---

## 1. Introduction and Motivation

The Cross-Street Detector is a software system designed to assist blind and visually impaired pedestrians in urban navigation. When a person walks along a street, the system determines the name of the nearest cross-street — the intersecting road they are approaching or passing. This information is critical for spatial orientation: knowing which cross-street is nearby tells the user where they are along a particular road.

The system operates entirely on open-access data. It requires no proprietary API keys, no commercial map services, and no subscription fees. All geographic data is sourced from OpenStreetMap (OSM) through its public Overpass API, and all map rendering is performed locally using Java's built-in 2D graphics library. This design decision ensures the system is freely reproducible, auditable, and independent of commercial service availability or pricing changes.

The core innovation is an **image-based detection approach**: rather than performing complex geometric intersection calculations on raw road network data, the system renders roads as colored pixels on a black canvas and scans outward from the user's position to find the nearest road in each perpendicular direction. This converts a computationally complex geometric problem — finding the nearest road segment perpendicular to a bearing in a network of polylines — into a simple pixel-scanning operation on a rasterized image.

### Evolution from Google Maps to OpenStreetMap

The system was originally built on the Google Maps platform, using the Google Maps Static API for styled map images, the Google Roads API for snapping coordinates to roads, and the Google Geocoding API for resolving road names. This approach required a paid Google Maps API key with three separate APIs enabled.

The system was subsequently migrated to an entirely open-access architecture:

- **Google Maps Static API** was replaced by **local Java2D rendering** of OpenStreetMap road data fetched via the Overpass API. Instead of requesting a pre-rendered map image from Google, the system queries raw road geometry from OSM and renders it locally.
- **Google Roads API + Geocoding API** were replaced by **direct Overpass QL queries** that return road names along with geometry, enabling road name resolution through perpendicular distance calculation rather than coordinate snapping.

This migration eliminated all external API key requirements and commercial dependencies while maintaining equivalent detection accuracy.

---

## 2. System Overview

### Architecture

The system follows a sequential pipeline architecture. Each stage produces output consumed by the next stage, with no branching or parallel execution within a single detection. The pipeline is deterministic: given the same two GPS coordinates and the same OSM data, it always produces the same result.

```
GPS Input (current, previous)
        │
        ▼
┌─────────────────────────┐
│  1. Bearing Calculation │  → BearingAngles (leftAngle, rightAngle)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│  2. Map Fetch & Render  │  → BufferedImage (1000×1000 px)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│  3. Image Scanning      │  → leftDistance (meters), rightDistance (meters)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│  4. Point Projection    │  → GeoPoint (primary target), GeoPoint (alternative)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│  5. Road Name Query     │  → roadName (primary), alternativeRoadName
└─────────────────────────┘
        │
        ▼
    DetectionResult
```

### Source Code Organization

| Package | Class | Responsibility |
|---------|-------|----------------|
| `gr.crossstreet` | `CrossStreetDetectorApp` | Main entry point; orchestrates the 5-step pipeline |
| `gr.crossstreet` | `BatchEvaluator` | Reads CSV test data, runs detections, writes results |
| `gr.crossstreet` | `EvaluationEngine` | Evaluates one test case; implements fuzzy matching |
| `gr.crossstreet.config` | `AppConfig` | Singleton configuration loader from `application.properties` |
| `gr.crossstreet.model` | `GeoPoint` | Immutable latitude/longitude coordinate (Java 21 record) |
| `gr.crossstreet.model` | `BearingAngles` | Pair of left/right search angles (record) |
| `gr.crossstreet.model` | `DetectionResult` | Full pipeline output: positions, distances, road names (record) |
| `gr.crossstreet.model` | `TestCase` | Single row from evaluation CSV (record) |
| `gr.crossstreet.geo` | `GeoUtils` | Bearing computation, angle normalization, point projection |
| `gr.crossstreet.image` | `ImageProcessor` | Radial pixel scanning for green road detection |
| `gr.crossstreet.image` | `DebugImageSaver` | Annotated debug image output for failed test cases |
| `gr.crossstreet.api` | `OverpassClient` | HTTP client for Overpass API with retry and backoff |
| `gr.crossstreet.api` | `OverpassMapRenderer` | Fetches OSM roads and renders them as a Java2D image |
| `gr.crossstreet.api` | `OverpassRoadFinder` | Finds the closest named road to a geographic point |
| `gr.crossstreet.util` | `GreekTransliterator` | Unicode Greek-to-Latin character mapping |

---

## 3. Detection Pipeline

### 3.1 Bearing Calculation

**Purpose**: Determine the user's walking direction and compute two search angles that are approximately perpendicular to the direction of movement. Cross-streets are, by definition, roads that cross the user's current path, so searching perpendicular to the walking direction is the geometrically correct approach.

**Input**: Two consecutive GPS positions — `current` (where the user is now) and `previous` (where they were a moment ago).

**Algorithm**:

1. **Forward bearing** is computed from `previous` to `current` using the spherical bearing formula:

```
Δlon = lon_current - lon_previous

y = sin(Δlon) × cos(lat_current)
x = cos(lat_previous) × sin(lat_current) - sin(lat_previous) × cos(lat_current) × cos(Δlon)

bearing = atan2(y, x)    [result in radians, converted to degrees]
```

This formula accounts for the spherical geometry of the Earth. The resulting bearing is measured in degrees clockwise from true north (0° = north, 90° = east, 180° = south, 270° = west).

2. **Search angles** are derived by adding and subtracting the configured angle offset (default: 25°) from the forward bearing:

```
leftAngle  = bearing - angleOffset
rightAngle = bearing + angleOffset
```

Both angles are normalized to the range [0°, 360°) using modular arithmetic.

**Why 25° offset instead of 90°?**: The search angles are not exactly perpendicular (90°) to the walking direction. A 25° offset from the forward bearing means the system searches at roughly ±65° from the forward direction — closer to perpendicular but biased slightly forward. This is intentional: in real urban environments, cross-streets do not always meet at exact right angles. The slight forward bias also accounts for GPS drift and the fact that the user may be approaching the intersection at a slight diagonal. The 25° value was empirically calibrated through batch evaluation on the test dataset.

**Implementation**: All trigonometric functions use precomputed `sin` and `cos` values to avoid redundant calculations. The `normalizeAngle()` method ensures angles wrap correctly across the 0°/360° boundary.

### 3.2 Map Data Acquisition and Rendering

**Purpose**: Create a visual representation of the road network centered on the user's position. Roads appear as green pixels on a black background, enabling simple pixel-based detection of road locations.

This stage has two sub-steps: fetching road geometry from OpenStreetMap, and rendering that geometry as a raster image.

#### 3.2.1 Overpass API Query

The system queries the Overpass API — a read-only API for OpenStreetMap data — to retrieve all road geometries within a configurable radius (default: 150 meters) of the user's current position.

**Bounding box calculation**: The query requires a geographic bounding box (south, west, north, east). This is computed from the center point and query radius:

```
latOffset = queryRadius / 111320.0
lonOffset = queryRadius / (111320.0 × cos(latitude))
```

The constant 111320 is the approximate number of meters per degree of latitude at the Earth's surface. The longitude offset is corrected by `cos(latitude)` because lines of longitude converge toward the poles — at higher latitudes, a degree of longitude spans fewer meters. This is the standard flat-Earth approximation for small areas, which is accurate to within fractions of a percent for the radii used (150m).

**Overpass QL query**:

```
[out:json][timeout:10][maxsize:2097152];
way["highway"~"^(motorway|trunk|primary|secondary|tertiary|
    residential|unclassified|living_street|
    motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]
    (south,west,north,east);
out geom qt;
```

Key design decisions in the query:

- **`out:json`**: Requests JSON output rather than XML, for easier parsing with Jackson.
- **`timeout:10`**: Server-side timeout of 10 seconds. This prevents queries from hanging indefinitely on a public shared server.
- **`maxsize:2097152`**: Limits response to 2 MB. This prevents unexpectedly large responses in dense urban areas from consuming excessive memory.
- **`highway` filter**: The regex matches 13 specific highway types. This deliberately **excludes** footways (`footway`), cycle paths (`cycleway`), service roads (`service`), pedestrian zones (`pedestrian`), and paths (`path`). The rationale is that cross-streets meaningful for navigation are vehicular roads with names, not footpaths.
- **`out geom`**: Requests inline geometry — each way includes its full list of coordinate nodes. This is more efficient than the default behavior of returning only node IDs, which would require a second query (`node(w); out;`) to resolve coordinates.
- **`qt`**: Quicksort output, optimized for faster server-side processing.

**Retry strategy**: The HTTP client (`OverpassClient`) implements automatic retry with exponential backoff for transient failures:

| Attempt | Delay before retry |
|---------|-------------------|
| 0 (first try) | — |
| 1 | 5 seconds |
| 2 | 10 seconds |
| 3 | 20 seconds |

Retries are triggered by: HTTP 429 (Too Many Requests), HTTP 504 (Gateway Timeout), or `SocketTimeoutException`. All other errors fail immediately. This handles the reality of the public Overpass API, which is shared infrastructure subject to rate limiting and occasional overload.

#### 3.2.2 Java2D Rendering

Once road geometries are received, the system renders them as a raster image using Java's built-in `java.awt` and `java.awt.image` packages.

**Canvas setup**:
- Image dimensions: 1000 × 1000 pixels (500 × scale factor 2)
- Image type: `BufferedImage.TYPE_INT_RGB`
- Background: solid black (RGB 0, 0, 0)
- Road color: pure green (RGB 0, 255, 0) — `#00FF00`
- Anti-aliasing: enabled (`RenderingHints.VALUE_ANTIALIAS_ON`)
- Stroke: `BasicStroke` with configurable width (default 6 pixels), round caps (`CAP_ROUND`), and round joins (`JOIN_ROUND`)

**Coordinate transformation** (geographic → pixel):

Each road node's latitude/longitude is projected to pixel coordinates relative to the image center:

```
xPixel = centerX + (lon - centerLon) × 111320 × cos(centerLat) / metersPerPixel
yPixel = centerY - (lat - centerLat) × 111320 / metersPerPixel
```

Where:
- `centerX`, `centerY` = 500, 500 (center of the 1000×1000 image)
- `centerLon`, `centerLat` = the user's current position
- `111320` = meters per degree of latitude
- `cos(centerLat)` = Mercator correction for longitude
- `metersPerPixel = 0.265` = the calibrated pixel scale
- The Y-axis is **inverted** (subtracted rather than added) because screen coordinates increase downward while latitude increases northward

This is an equirectangular projection (also called plate carrée), which is a standard approximation for small areas. At the scale of 150 meters, the maximum distortion is negligible (less than 0.01%).

**Road drawing**: Each OSM way is drawn as a polyline by connecting its geometry nodes with `Graphics2D.drawPolyline()`. The anti-aliasing creates slight color gradation at road edges, but the green pixel detection threshold (described in Section 3.3) is tuned to handle this.

**Why render locally instead of fetching a pre-rendered image?** There are several reasons:
1. **No API key required**: Pre-rendered tile services either require API keys or have strict usage policies.
2. **Complete control over styling**: The system needs roads in exact green on exact black. Third-party tile services render labels, building outlines, terrain, and other features that would interfere with pixel scanning.
3. **Consistent rendering**: The same road data always produces the same pixels, eliminating variability from different tile server versions or styles.
4. **Selective road types**: The system can filter which highway types to render, excluding footpaths and service roads that would create false detections.

### 3.3 Radial Image Scanning

**Purpose**: Determine the pixel distance from the image center to the nearest road in a given direction. This is the core detection algorithm — it converts the visual road map into a distance measurement.

**Algorithm**: A ray is cast from the center of the image outward along the specified geographic angle. The ray advances pixel by pixel until it either finds a green pixel (road detected) or exits the image bounds (no road in this direction).

**Angle conversion**: Geographic angles (0° = north, clockwise) must be converted to screen angles (0° = east, counter-clockwise Y) for the pixel coordinate system:

```
screenAngle = (geographicAngle + 270.0) % 360.0
```

This rotation accounts for the fact that in screen coordinates, the positive X-axis points right (east) and the positive Y-axis points down (south), while in geographic coordinates, 0° points north.

**Starting position**: The scan begins not at the exact center but at a configurable offset (default: 50 pixels) from the center:

```
startX = centerX + skipPixels × cos(screenAngle)
startY = centerY + skipPixels × sin(screenAngle)
```

The 50-pixel skip zone serves two purposes:
1. **Avoids the user's own road**: The user is standing on a road, and that road passes through the center of the image. Without the skip zone, the scanner would immediately detect the user's current road rather than the cross-street.
2. **Avoids rendering artifacts**: The exact center may have aliasing or stroke overlap artifacts from multiple converging roads.

At `metersPerPixel = 0.265`, 50 pixels corresponds to approximately 13.25 meters — enough to clear the user's current road (typically 6-10 meters wide) plus a margin.

**Marching loop**:

```
x = startX
y = startY
while (x, y) is within image bounds:
    color = image.getRGB(round(x), round(y))
    red   = (color >> 16) & 0xFF
    green = (color >> 8) & 0xFF
    blue  = color & 0xFF

    if green > 200 AND red < 50 AND blue < 50:
        pixelDistance = sqrt((x - centerX)² + (y - centerY)²)
        return pixelDistance × metersPerPixel

    x += stepSize × cos(screenAngle)
    y += stepSize × sin(screenAngle)
```

**Green pixel detection thresholds**: A pixel is considered a "road pixel" if:
- Green channel > 200 (out of 255)
- Red channel < 50
- Blue channel < 50

These thresholds are deliberately lenient on the green channel (200 instead of 255) to accommodate anti-aliasing: at road edges, the green value fades gradually due to sub-pixel blending. The red and blue thresholds are strict (< 50) to avoid false positives from non-road artifacts.

**Step size**: The scan advances by 1.0 pixel per iteration (configurable). This ensures no road narrower than 1 pixel is missed. For the default road width of 6 pixels, the scanner is guaranteed to detect any road it crosses.

**Distance conversion**: When a green pixel is found, its Euclidean distance from the image center is computed and multiplied by `metersPerPixel` (0.265) to convert from pixels to meters.

The scan is performed independently for both the left and right search angles, producing two distance measurements (or none, if no road is found in a direction).

### 3.4 Geographic Point Projection

**Purpose**: Convert the detected pixel distance and angle back to a geographic coordinate (latitude/longitude) that can be queried for a road name.

**Algorithm**: The haversine inverse formula (also called the direct geodesic problem) computes the destination point given a starting point, bearing, and distance:

```
angularDistance = distance / R        [R = 6,371,000 meters, Earth's mean radius]

lat₂ = arcsin(sin(lat₁) × cos(angularDistance) +
              cos(lat₁) × sin(angularDistance) × cos(bearing))

lon₂ = lon₁ + arctan2(sin(bearing) × sin(angularDistance) × cos(lat₁),
                       cos(angularDistance) - sin(lat₁) × sin(lat₂))
```

Where:
- `(lat₁, lon₁)` = the user's current position (in radians)
- `bearing` = the search angle that detected the road (in radians)
- `distance` = the detected distance in meters (from the image scan)
- `(lat₂, lon₂)` = the projected point on or near the cross-street

The longitude is normalized to the range [-180°, 180°] to handle wrap-around at the antimeridian.

**Why spherical projection instead of flat-Earth?**: Although the distances involved are small (typically 10-100 meters), the spherical formula is used for correctness. The computational cost is negligible (a few trigonometric operations), and it eliminates any accumulation of error in batch processing or future use cases at larger scales.

### 3.5 Road Name Resolution

**Purpose**: Determine the name of the road that passes through or near the projected geographic point.

**Algorithm**: The system queries the Overpass API for all named roads within a configurable radius (default: 50 meters) of the projected point, then selects the closest one by perpendicular distance.

**Overpass QL query** (if no cache hit):

```
[out:json][timeout:10][maxsize:1048576];
way["highway"]["name"](south,west,north,east);
out geom qt;
```

This query differs from the map-rendering query in two ways:
1. **Requires `"name"` tag**: Only roads with an explicit name are returned. Unnamed roads (common for service roads, alleys) are excluded since they provide no useful navigation information.
2. **No highway type filter**: All highway types are considered for name resolution, including those excluded from rendering (e.g., a named pedestrian street could be a valid cross-street for orientation purposes).
3. **Smaller response limit** (1 MB vs 2 MB): Name resolution queries cover a smaller area and return fewer results.

**Closest road selection** uses perpendicular distance from the query point to each road segment. For each way in the response:

1. The way's geometry is a series of coordinate nodes forming a polyline.
2. For each consecutive pair of nodes (A, B) forming a segment:

```
cosLat = cos(P.latitude)

// Convert to local meters (flat-Earth approximation)
px = (P.lon - A.lon) × 111320 × cosLat
py = (P.lat - A.lat) × 111320
bx = (B.lon - A.lon) × 111320 × cosLat
by = (B.lat - A.lat) × 111320

// Parametric projection of P onto segment A→B
t = clamp(0, 1, (px × bx + py × by) / (bx² + by²))

// Closest point on segment
projX = t × bx
projY = t × by

// Perpendicular distance
distance = sqrt((px - projX)² + (py - projY)²)
```

3. The minimum distance across all segments of all ways determines the closest road.

**Name resolution preference**: If a road has both a `name:en` (English) tag and a `name` (native) tag, the English name is preferred. If only the native Greek name (`name` tag) is available, it is transliterated to Latin characters using the `GreekTransliterator` (see Section 6.1).

---

## 4. Alternative Road Mechanism

### The Problem

When scanning perpendicular to the user's walking direction, there are always two possible directions: left and right. One direction leads to the cross-street the user is approaching; the other leads away from it — and may detect the user's current road, a different cross-street, or no road at all.

The system selects the **closer** detection as the primary result because, in most cases, the cross-street being approached is nearer than whatever is in the opposite direction. However, this heuristic is not always correct:
- The user might be closer to a parallel road on one side than to the cross-street on the other
- The user's current road, if it curves, might appear in the perpendicular scan before the actual cross-street

### The Solution

The system resolves road names for **both** directions — the closer (primary) and the farther (alternative). The `DetectionResult` record carries both:

```java
record DetectionResult(
    GeoPoint currentPosition,
    GeoPoint targetPoint,         // primary (closer) projected point
    double distanceMeters,        // primary distance
    double searchAngle,           // primary angle
    Optional<String> roadName,    // primary road name
    Optional<String> alternativeRoadName,  // secondary (farther) road name
    OptionalDouble alternativeDistanceMeters,
    double alternativeAngle
)
```

During batch evaluation, the `EvaluationEngine` implements fallback logic:

1. If the **primary** road name matches the target cross-street → **PASS**
2. If the primary road name matches the user's **current** road (an "acceptable" but incorrect result — it means we detected the street we're already on) → try the **alternative**
3. If the primary road name matches **neither** the target nor the current road → also try the **alternative**
4. If the alternative matches the target → **PASS** (marked as `alternativeUsed = true`)
5. Otherwise → **FAIL**

This mechanism significantly improves detection accuracy by recovering from cases where the initial direction heuristic (closer = cross-street) is incorrect.

---

## 5. Caching Strategy

The system implements two levels of spatial caching to minimize Overpass API calls, which is important both for performance (each query takes 1-3 seconds) and for respecting the public API's rate limits.

### 5.1 Map Render Cache

**Location**: `OverpassMapRenderer`

The map renderer caches the most recent Overpass API response (raw road geometry data) along with the center coordinate for which it was fetched.

**Cache validity**: Before issuing a new query, the renderer computes the haversine distance between the new center and the cached center. If this distance is less than `queryRadius × 0.3` (default: 150 × 0.3 = 45 meters), the cached data is reused.

**Rationale**: The cached data covers a circular area of 150-meter radius. As long as the new center is within 45 meters of the old center, all roads within the visible image area (approximately 133 meters from center to edge at 0.265 m/px × 500 px) are guaranteed to be covered. The 0.3 factor provides a generous margin.

**Benefit**: In batch evaluation with sequential test cases in the same neighborhood, or in real-time use where the user takes multiple GPS readings while walking, this cache dramatically reduces API calls. Consecutive detections within a ~45-meter radius share a single API response.

### 5.2 Road Finder Cache Reuse

**Location**: `OverpassRoadFinder`

The road finder attempts to reuse the map renderer's cached data before issuing its own query. Since the map renderer already has road geometry with tags (including names) for a 150-meter radius, the road finder checks whether its query point falls within the useful coverage:

```
distance from query point to cached center < mapRenderer.queryRadius - roadFinder.queryRadius
= 150 - 50 = 100 meters
```

If the query point is within 100 meters of the map renderer's cached center, the road finder filters the cached data for named roads and computes perpendicular distances without making any additional API calls.

This means a typical detection (map fetch + road name query) may require only **one** API call instead of two, and consecutive detections in the same area may require **zero** API calls.

---

## 6. Greek Street Name Handling

The system was developed and tested primarily in Greece, where street names are natively in Greek script (e.g., "Θεοχάρη Κοτσικά"). OpenStreetMap data in Greece typically includes both Greek names (`name` tag) and sometimes English/Latin transliterations (`name:en` tag). The test dataset uses Latin transliterations. This creates a matching problem: the detected name and the expected name may be different romanizations of the same Greek name.

### 6.1 Transliteration

**Class**: `GreekTransliterator`

When the OSM `name:en` tag is not available, the system transliterates the Greek `name` tag to Latin characters. The transliteration follows a mapping inspired by the ELOT 743 standard (the official Greek government transliteration standard), with the following features:

**Digraph handling**: Greek has several letter combinations that produce sounds different from the individual letters. These are mapped first (before single characters) to prevent incorrect decomposition:

| Greek | Latin | Phonetic Reason |
|-------|-------|-----------------|
| μπ | b | Represents /b/ sound in Greek |
| ντ | nt | Represents /nd/ or /nt/ |
| γκ | gk | Represents /g/ sound |
| γγ | ng | Represents /ŋg/ |
| ου | ou | Represents /u/ vowel |
| αυ | av | Represents /av/ or /af/ |
| ευ | ev | Represents /ev/ or /ef/ |
| αι | ai | Represents /e/ vowel |
| ει | ei | Represents /i/ vowel |
| οι | oi | Represents /i/ vowel |
| τσ | ts | Represents /ts/ affricate |
| τζ | tz | Represents /dz/ affricate |

**Single character mapping**: All 24 Greek letters (both uppercase and lowercase) are mapped, plus accented variants:

| Greek | Latin | Greek | Latin |
|-------|-------|-------|-------|
| Α/α | A/a | Ν/ν | N/n |
| Β/β | V/v | Ξ/ξ | X/x |
| Γ/γ | G/g | Ο/ο | O/o |
| Δ/δ | D/d | Π/π | P/p |
| Ε/ε | E/e | Ρ/ρ | R/r |
| Ζ/ζ | Z/z | Σ/σ/ς | S/s/s |
| Η/η | I/i | Τ/τ | T/t |
| Θ/θ | Th/th | Υ/υ | Y/y |
| Ι/ι | I/i | Φ/φ | F/f |
| Κ/κ | K/k | Χ/χ | Ch/ch |
| Λ/λ | L/l | Ψ/ψ | Ps/ps |
| Μ/μ | M/m | Ω/ω | O/o |

Accented vowels (ά, έ, ή, ί, ό, ύ, ώ) and diaeresis variants (ϊ, ϋ, ΐ, ΰ) are mapped to their unaccented equivalents.

**Algorithm**: The transliterator scans the input string character by character, attempting a 2-character digraph match first. If no digraph matches, it falls back to a single-character mapping. Unmapped characters (spaces, punctuation, digits, already-Latin characters) pass through unchanged.

### 6.2 Fuzzy Matching

**Class**: `EvaluationEngine`

Even after transliteration, different sources may romanize the same Greek name differently. For example:
- "Theoxari Kotsika" vs "Theocharous Kotsika" (different transliteration of χ)
- "Elaiwn" vs "Eleon" (different transliteration of αι and ω)
- "P Tsaldari" vs "Panagi Tsaldari" (abbreviated first word)

The fuzzy matching system uses a multi-stage strategy, applied in order:

#### Stage 1: Normalization

Both strings are first normalized to a canonical form that collapses common transliteration variants:

```
1. Trim whitespace, convert to lowercase, collapse multiple spaces
2. Apply multi-character substitutions (order matters — longer patterns first):
   - "ou" → "u"    (ου/υ variants)
   - "ch" → "k"    (χ variants: ch, k)
   - "th" → "t"    (θ/η variants: th, t)
   - "ph" → "f"    (φ variants: ph, f)
   - "ai" → "e"    (αι → ε equivalence)
   - "ei" → "i"    (ει → ι equivalence)
   - "oi" → "i"    (οι → ι equivalence)
3. Apply single-character substitutions:
   - "x" → "k"     (χ written as x)
   - "w" → "o"     (ω written as w)
```

After normalization, "Theoxari" and "Teokari" would both normalize to the same string, resolving transliteration differences.

#### Stage 2: Exact Match

If normalized strings are identical → match.

#### Stage 3: Substring Containment

If either normalized string contains the other → match.

This handles abbreviated names: "P Tsaldari" (after normalization: "p tsaldari") contains and is contained within the normalized form of "Panagi Tsaldari". This is common in Greek street names where first names are often abbreviated to initials.

#### Stage 4: Last-Word Levenshtein

The last word of Greek street names is typically the most distinctive (the surname). If the Levenshtein distance between the last words of both strings is ≤ 2 → match.

This handles cases where the surname is spelled slightly differently but the prefix differs substantially, e.g., "Dimosthenous" vs "Dimosthenous" (exact) or "Elaiwn" vs "Eleon" where after normalization the last words are close.

#### Stage 5: Proportional Edit Distance

As a final fallback, the full Levenshtein distance between normalized strings is compared against a proportional threshold:

```
maxAllowed = max(2, floor(min(len(normA), len(normB)) × 0.3))
```

If the edit distance is within this threshold → match. This allows approximately 30% of characters to differ, which accommodates significant transliteration variations while avoiding false positives with completely different street names.

#### Levenshtein Distance Implementation

The classic dynamic programming algorithm is used:

```
dp[0][j] = j    (inserting j characters)
dp[i][0] = i    (deleting i characters)

dp[i][j] = min(
    dp[i-1][j] + 1,              // deletion
    dp[i][j-1] + 1,              // insertion
    dp[i-1][j-1] + cost           // substitution (cost = 0 if equal, 1 if different)
)
```

Time complexity: O(m × n) where m and n are string lengths. Space complexity: O(m × n) for the full matrix. Given that street names are typically 5-25 characters, this is computationally insignificant.

---

## 7. Batch Evaluation System

### Purpose

The batch evaluator serves as the system's validation framework. In the absence of automated unit tests, it provides a systematic way to measure detection accuracy across a corpus of known-correct test cases.

### Input Format

The test dataset (`src/main/resources/test-data.csv`) uses semicolon-delimited CSV:

```
Previous Coordinates;Current Coordinates;Current Road;Target Road;Result;City
38.016959, 24.419875;38.016989, 24.419658;Gerestou;Theoxari Kotsika;;Karystos
```

| Column | Description |
|--------|-------------|
| Previous Coordinates | GPS position before the current one (latitude, longitude) |
| Current Coordinates | The user's current GPS position |
| Current Road | The name of the road the user is currently walking on |
| Target Road | The expected cross-street (ground truth) |
| Result | Left empty in input; filled by evaluator |
| City | The city where this test case is located |

The semicolon delimiter was chosen over comma because the coordinate values themselves contain commas separating latitude and longitude.

### Evaluation Flow

For each test case:

1. Parse the CSV row into a `TestCase` record
2. Run `CrossStreetDetectorApp.detect()` with the current and previous coordinates
3. Apply the evaluation logic in `EvaluationEngine.evaluate()`:
   - Check primary road against target (fuzzy match)
   - If no match, try alternative road against target
   - Record outcome as PASS, FAIL, or ERROR
4. Wait 3000 ms before the next test case (Overpass API rate limiting)

### Output Format

Results are written to `evaluation-results.csv`:

```
Row;Previous Coordinates;Current Coordinates;Current Road;Target Road;Detected Road;Result;Iterations;AltUsed;City
1;"38.016959,24.419875";"38.016989,24.419658";Gerestou;Theoxari Kotsika;Theoxari Kotsika;PASS;0;NO;Karystos
```

| Column | Description |
|--------|-------------|
| Row | Sequential test case number |
| Detected Road | The road name(s) detected by the system |
| Result | PASS, FAIL, or ERROR |
| Iterations | Forward-step retries used (always 0 in current version) |
| AltUsed | YES if the alternative direction produced the correct answer |

### Summary Statistics

After all test cases are processed, the evaluator prints:
- Total number of tests
- Number and percentage of PASS, FAIL, and ERROR results
- Number of cases where the alternative direction was used

---

## 8. Debug Image Generation

**Class**: `DebugImageSaver`

For failed test cases, the system generates annotated PNG images saved to the `debug/` directory. These images overlay detection information on the rendered road map, enabling visual inspection of why a detection failed.

**Annotations drawn on the debug image**:

1. **Red crosshair** at the image center — marks the user's current position
2. **White arrow** from center toward the back-bearing direction — shows the user's movement direction (pointing where they came from)
3. **Yellow circle with label** at the primary detection point — shows where the primary road was detected, with the detected road name
4. **Cyan circle with label** at the alternative detection point — shows the alternative direction detection
5. **Caption bar** at the bottom — dark background strip with text showing: test case number, target road, primary detected road, and alternative detected road

**Pixel coordinate calculation for detection points**:

```
screenAngle = (geographicAngle + 270.0) % 360.0    [same conversion as image scanner]
x = centerX + (meters / metersPerPixel) × cos(screenAngle)
y = centerY + (meters / metersPerPixel) × sin(screenAngle)
```

These debug images are invaluable for understanding failure modes: they visually show whether the road was present but missed, whether the scan angle was off, whether the wrong road was closer, or whether the road name resolution returned an unexpected result.

---

## 9. Configuration and Calibration

### Configuration System

**Class**: `AppConfig`

The configuration is loaded from `application.properties` on the classpath using `java.util.Properties`. It is implemented as a thread-safe singleton with lazy initialization.

**Environment variable substitution**: Property values containing `${VAR_NAME}` patterns are resolved against system environment variables at load time. This allows sensitive values (if any were needed) to be externalized without hardcoding them in the properties file.

**Type-safe accessors**: The `AppConfig` class provides typed getter methods for each property, with sensible defaults:

```java
public int getMapQueryRadius()     // default: 150
public int getRoadQueryRadius()    // default: 50
public double getMetersPerPixel()  // default: 0.265
public int getAngleOffset()        // default: 25
// ... etc.
```

### Critical Calibration: Meters Per Pixel

The `image.scale` property (default: 0.265) is the single most critical calibration value in the system. It defines how many meters each pixel represents in the rendered image.

**Derivation**: At OSM zoom level 18 and mid-latitudes (~38° N, which covers Greece), the ground resolution is approximately 0.265 meters per pixel. This was derived from the standard Web Mercator tile resolution formula:

```
resolution = (156543.03 × cos(latitude)) / 2^zoom
           = (156543.03 × cos(38°)) / 2^18
           = (156543.03 × 0.788) / 262144
           ≈ 0.471 meters/pixel (at zoom 18, latitude 38°)
```

However, the actual value used (0.265) was empirically calibrated to match the rendering scale of the Java2D output, which accounts for the `staticmap.scale=2` factor and the specific projection math used in the renderer.

**Impact of miscalibration**: If this value is incorrect, all detected distances will be proportionally wrong. A 10% error in `image.scale` produces a 10% error in the projected point's distance from center, which could cause the road name query to find a different road. The value was validated by comparing known road positions against detected pixel positions in the debug images.

### Angle Offset Calibration

The `detection.angle.offset` (default: 25°) controls how far from the forward bearing the search angles are placed. This value was empirically optimized:

- **Too small** (e.g., 5°): The search directions are nearly parallel to the walking direction, detecting roads ahead rather than to the side
- **Too large** (e.g., 45°): The search directions are too perpendicular, potentially missing cross-streets that intersect at acute angles
- **25°**: An empirically determined compromise that works well across the test dataset's variety of intersection geometries

### Skip Pixels Calibration

The `image.skip.pixels` (default: 50) defines the dead zone around the image center. At 0.265 m/px, this equals ~13.25 meters.

- **Too small**: Risks detecting the user's own road (false positive for current street)
- **Too large**: Misses nearby cross-streets, especially at compact intersections where the cross-street may be only 15-20 meters away
- **50 pixels (~13.25m)**: Sufficient to clear typical 2-lane roads (6-8m wide) with margin, while still detecting cross-streets at distances ≥13.25m

---

## 10. Data Models

The system uses Java 21 records for all data transfer objects. Records are immutable value types with automatic implementations of `equals()`, `hashCode()`, and `toString()`.

### GeoPoint

```java
public record GeoPoint(double latitude, double longitude)
```

Represents a geographic coordinate in WGS 84 (the GPS coordinate system). Provides:
- `parse(String input)`: Parses comma-separated "lat, lon" format (with optional whitespace)
- `toApiString()`: Returns "lat,lon" for use in API query parameters

### BearingAngles

```java
public record BearingAngles(double leftAngle, double rightAngle)
```

Holds the two search angles computed from the user's movement direction. Both angles are in degrees [0°, 360°), measured clockwise from true north.

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

The complete output of one detection pipeline run. Contains both the primary result (closer direction) and the alternative (farther direction).

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

A single evaluation test case parsed from the CSV dataset.

### OverpassClient Internal Records

```java
record LatLon(double lat, double lon)
record OsmWay(long id, List<LatLon> geometry, Map<String, String> tags)
record OverpassData(List<OsmWay> ways)
```

Internal representations of parsed Overpass API responses. `OsmWay` carries both the road's geometry (list of coordinate nodes) and its OSM tags (including name, highway type, etc.).

---

## 11. External Dependencies and Infrastructure

### Runtime Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| OkHttp | 4.12.0 | HTTP client for Overpass API requests. Chosen for its connection pooling, automatic GZIP, and clean API. |
| Jackson Databind | 2.17.0 | JSON parsing for Overpass API responses. Provides object-binding (JSON → Java records). |
| SLF4J API | 2.0.12 | Logging facade. Decouples application logging from the logging implementation. |
| Logback Classic | 1.5.3 | Logging implementation. Provides configurable console and file logging. |
| Java2D | (JDK built-in) | 2D graphics rendering for road map images. Part of `java.desktop` module. |

### Build Dependencies

| Tool | Version | Purpose |
|------|---------|---------|
| Maven | 3.9+ | Build lifecycle, dependency management |
| maven-compiler-plugin | 3.13.0 | Java 21 compilation |
| maven-shade-plugin | 3.5.2 | Fat JAR packaging with all dependencies embedded |

### External Services

| Service | URL | Usage |
|---------|-----|-------|
| Overpass API | `https://overpass-api.de/api/interpreter` | Road data queries. Public, free, no authentication required. |

The Overpass API is the **sole external dependency** at runtime. It is a public read-only interface to the OpenStreetMap database, operated by volunteers and community organizations. The system is designed to work with any Overpass API endpoint — the URL is configurable in `application.properties`, allowing use of private instances if needed.

### Build Output

The build produces a single fat JAR (`Cross-street-detector-1.0-SNAPSHOT.jar`) that contains all compiled classes and all runtime dependencies. This JAR is self-contained and can be distributed and executed on any system with Java 21+ installed, without any additional setup.

---

## 12. Design Decisions and Trade-offs

### Image-Based vs. Geometric Detection

The most fundamental design decision is the choice to detect cross-streets through image scanning rather than geometric computation on road network data.

**Geometric approach** (not used): Parse road geometries, compute intersection points between lines, find the nearest intersection perpendicular to the bearing. This requires complex computational geometry (line-segment intersection, perpendicular projection, handling of curved roads as polylines).

**Image-based approach** (used): Render roads as pixels, scan outward to find the first green pixel. The complexity of road network topology is "baked into" the rendered image, and detection reduces to a simple pixel walk.

**Trade-offs**:
- Image-based is **simpler to implement** and **easier to debug** (visual inspection of rendered images)
- Image-based has **limited precision** — resolution is bounded by the pixel grid (0.265 meters at default scale)
- Image-based is **robust to road geometry complexity** — curved roads, roundabouts, and complex intersections are handled automatically by the renderer without special-case logic
- Geometric would be **more precise** but significantly more complex to implement correctly for all road geometries

### Closer-Direction Heuristic with Alternative Fallback

The system assumes the closer perpendicular detection is the cross-street and the farther one is secondary. This is correct in the majority of cases (a pedestrian approaching an intersection is usually closer to the cross-street than to whatever is behind them). Rather than implementing more complex logic to determine which direction is "forward" relative to the intersection, the system simply resolves both directions and uses the alternative as a fallback.

### Public Overpass API vs. Local Database

Using the public Overpass API means the system requires an internet connection and is subject to rate limiting. An alternative would be to use a local OSM database (e.g., PostGIS with osm2pgsql). The public API was chosen for simplicity and accessibility — it requires no local database setup, no data imports, and no maintenance. The caching strategy mitigates the performance and rate-limiting concerns.

### Fuzzy Matching Approach

The multi-stage fuzzy matching was designed specifically for the Greek transliteration problem. A general-purpose fuzzy matching library (e.g., Apache Commons Text `FuzzyScore`) could have been used, but the custom implementation allows fine-tuned normalization rules specific to Greek romanization variants. This domain-specific approach produces fewer false positives and false negatives than a generic edit-distance threshold would.

### Java 21 Records

All data transfer objects are implemented as Java 21 records rather than traditional POJOs. Records provide:
- Immutability by default (all fields are final)
- Automatic `equals()`, `hashCode()`, and `toString()` implementations
- Compact syntax (one line vs. dozens of lines for equivalent POJO)
- Semantic clarity — a record is a transparent data carrier with no hidden behavior

---

## 13. Test Dataset

The test dataset (`src/main/resources/test-data.csv`) contains **101 test cases** spanning 4 Greek cities:

| City | Test Cases | Description |
|------|-----------|-------------|
| Athens | 68 | Dense urban grid, variety of intersection types, narrow and wide streets |
| Thessaloniki | 19 | Northern Greece, different street patterns, hilly terrain |
| Patra | 10 | Western Greece, coastal city, mix of grid and organic street layouts |
| Karystos | 4 | Small town on Evia island, narrow streets, less dense network |

The dataset covers diverse urban conditions:
- **Grid intersections** (right-angle crossings)
- **Oblique intersections** (non-perpendicular crossings)
- **One-way streets** and narrow residential roads
- **Major arterials** (multi-lane roads) crossing minor streets
- **Dense commercial districts** (Athens center) with closely spaced cross-streets
- **Suburban areas** with wider spacing between intersections

Each test case was manually verified: a human walked the route, recorded GPS coordinates at known locations, and identified the expected cross-street by direct observation. The GPS coordinates represent real-world walking trajectories, including natural GPS drift and positioning error.

The street names in the dataset are Latin transliterations of Greek names, using various romanization conventions (reflecting the inconsistency found in real-world usage). This intentional variety tests the robustness of the fuzzy matching system.