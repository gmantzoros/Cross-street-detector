# Cross-Street Detector: Technical Documentation

## 1. Introduction and Motivation

The Cross-Street Detector is a software system designed to assist blind and visually impaired pedestrians in urban navigation by automatically identifying upcoming cross-streets as the user walks along a road. When a visually impaired person approaches an intersection, the system determines which named road crosses their path ahead, providing essential spatial awareness that sighted pedestrians take for granted.

### 1.1 Problem Statement

Blind pedestrians navigating urban environments face significant challenges at road intersections. While GPS-based navigation applications can provide turn-by-turn directions, they often fail to communicate a fundamental piece of contextual information: the name of the cross-street the user is about to encounter. This information is critical for:

- **Spatial orientation**: Knowing which cross-street is ahead helps the user build a mental map of their surroundings.
- **Wayfinding verification**: Cross-street names serve as landmarks that confirm the user is on the correct route.
- **Communication**: When requesting assistance or reporting location, cross-street names are the standard reference frame (e.g., "I am on Kremou Street, near the intersection with Filaretou").

### 1.2 Approach Overview

The system takes as input two consecutive GPS coordinates (representing the user's previous and current positions). From these inputs, it computes the user's walking direction, automatically detects the road the user is on, and queries OpenStreetMap for all nearby road geometries. It then performs computational geometry operations — specifically, segment-segment intersection testing — to find where the current road intersects with other named roads ahead of the user. The closest such intersection is reported as the result.

The system is accessible both as a REST API (the default entry point) and as a CLI tool.

### 1.3 Design Philosophy: Open Access

This implementation (the `openaccess-noimage` variant) was designed with a deliberate emphasis on open access and reproducibility:

- **No proprietary APIs**: The system relies exclusively on OpenStreetMap data via the Overpass API, which is free, open, and does not require API keys or authentication.
- **No image processing**: Unlike an earlier variant of the system that rendered map images and scanned for road pixels, this version operates purely on road geometry data, eliminating dependencies on image rendering services and reducing computational complexity.
- **Full reproducibility**: Any researcher can clone the repository, build it with Maven, and run it against the included test dataset without any external account setup or API key provisioning.

---

## 2. System Architecture

### 2.1 High-Level Pipeline

The detection pipeline is orchestrated by `CrossStreetDetectorApp.detect()` and consists of four sequential phases:

```
Phase 1: Bearing Computation
   Input:  Previous GPS position, Current GPS position
   Output: Forward bearing (degrees, 0° = North, clockwise)
   Method: Spherical trigonometry (forward azimuth formula)

Phase 2: Road Data Acquisition
   Input:  Current GPS position, Query radius (default 200m)
   Output: Set of OSM way geometries with tags (road names, types)
   Method: Overpass API query with spatial caching

Phase 3: Current Road Detection
   Input:  User position, Road data
   Output: Current road name (auto-detected)
   Method: Nearest named road by point-to-segment distance

Phase 4: Geometry-Based Intersection Detection
   Input:  User position, Forward bearing, Current road name, Road data
   Output: List of cross-street intersections sorted by distance
   Method: 2D segment-segment intersection with crossing-angle and forward-direction filtering
```

### 2.2 Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         ApiServer (Javalin)                      │
│                 REST API entry point (port 8080)                 │
│              GET /detect?prev=lat,lon&curr=lat,lon               │
│              GET /health                                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                    CrossStreetDetectorApp                        │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────────────┐ │
│  │   GeoUtils    │  │ OverpassClient │  │ IntersectionDetector│ │
│  │  (bearing,    │  │  (HTTP + JSON  │  │  (segment math,     │ │
│  │   haversine,  │  │   with retry)  │  │   road detection,   │ │
│  │   projection) │  │                │  │   name resolution)  │ │
│  └──────────────┘  └───────┬────────┘  └─────────────────────┘ │
│                             │                                    │
│                    ┌────────▼────────┐                           │
│                    │  Overpass API   │                           │
│                    │ (OpenStreetMap) │                           │
│                    └─────────────────┘                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      Batch Evaluation                           │
│  ┌────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ BatchEvaluator │  │ EvaluationEngine │  │DebugImageSaver │  │
│  │ (CSV I/O,      │  │ (pass/fail logic)│  │(annotated PNG  │  │
│  │  rate limiting) │  │                  │  │ for failures)  │  │
│  └────────────────┘  └──────────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      Support Components                         │
│  ┌────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │   AppConfig    │  │GreekTransliterator│ │ RoadNameMatcher│  │
│  │ (singleton,    │  │(retained for      │ │ (fuzzy Greek   │  │
│  │  env vars)     │  │ future use)       │ │  name matching)│  │
│  └────────────────┘  └──────────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 Data Flow

1. The user provides two GPS coordinate pairs (previous and current position) via REST API or CLI.
2. `GeoUtils.calculateBearing()` computes the forward azimuth.
3. `CrossStreetDetectorApp.fetchRoadDataCached()` checks the spatial cache; on miss, constructs an Overpass QL query and delegates to `OverpassClient.query()`.
4. The Overpass API returns JSON containing OSM way elements with inline geometry coordinates and tags (including road names).
5. `OverpassClient.parseResponse()` deserializes the JSON into typed Java records (`OsmWay`, `LatLon`, `OverpassData`).
6. `IntersectionDetector.findNearestRoad()` auto-detects the current road by finding the nearest named road to the user's position using point-to-segment distance.
7. `IntersectionDetector.findForwardIntersections()` processes the road data:
   - Classifies ways into "current road" and "other named roads" using fuzzy name matching (`RoadNameMatcher`).
   - Projects all coordinates into a local 2D meter-based system.
   - Tests every pair of segments (current road vs. other road) for geometric intersection.
   - Filters results by forward direction and deduplicates by road name.
8. The closest forward intersection is returned as a `DetectionResult` (serialized as JSON in API mode).

---

## 3. Geographic Computations

### 3.1 Forward Bearing Calculation

The forward bearing (azimuth) from point A to point B on a sphere is computed using the standard formula derived from spherical trigonometry:

```
φ₁ = latitude of A  (radians)
φ₂ = latitude of B  (radians)
Δλ = longitude of B - longitude of A  (radians)

y = sin(Δλ) · cos(φ₂)
x = cos(φ₁) · sin(φ₂) − sin(φ₁) · cos(φ₂) · cos(Δλ)

bearing = atan2(y, x)    [converted to degrees, normalized to [0°, 360°)]
```

This is the initial bearing (sometimes called the forward azimuth) along the great circle from A to B. It represents the compass direction the user is walking in. A bearing of 0° means due North, 90° means due East, 180° means due South, and 270° means due West.

**Implementation**: `GeoUtils.calculateBearing()` (lines 86–105 of `GeoUtils.java`). The method precomputes `sin` and `cos` values of the input latitudes to avoid redundant trigonometric evaluations.

### 3.2 Haversine Distance

The great-circle distance between two points on a sphere is computed using the Haversine formula:

```
φ₁, φ₂ = latitudes in radians
Δφ = φ₂ − φ₁
Δλ = λ₂ − λ₁  (longitudes in radians)

a = sin²(Δφ/2) + cos(φ₁) · cos(φ₂) · sin²(Δλ/2)
d = 2R · arcsin(√a)

where R = 6,371,000 meters (mean Earth radius)
```

This formula is numerically stable for all distances, including very small ones (unlike the spherical law of cosines). It is used in two contexts:

1. **Spatial cache validation**: Determining whether the user has moved far enough from the last query center to warrant a new Overpass API call.
2. **Distance reporting**: Computing the distance from the user to detected intersection points (though in practice, the Euclidean distance in local meters is used for intersection distances due to the small scale involved).

**Implementation**: `GeoUtils.haversineDistance()` (lines 112–121 of `GeoUtils.java`).

### 3.3 Destination Point Projection

Given a starting point, a bearing, and a distance, the system can compute the destination point using the Vincenty direct formula (spherical approximation):

```
φ₂ = arcsin(sin(φ₁) · cos(d/R) + cos(φ₁) · sin(d/R) · cos(θ))
λ₂ = λ₁ + atan2(sin(θ) · sin(d/R) · cos(φ₁), cos(d/R) − sin(φ₁) · sin(φ₂))

where θ = bearing in radians, d = distance in meters, R = Earth radius
```

This method is retained from the earlier image-based design (where it converted pixel distances back to geographic coordinates) and remains available for future use.

**Implementation**: `GeoUtils.projectPoint()` (lines 49–79 of `GeoUtils.java`).

### 3.4 Flat-Earth Projection (Local Meter Coordinates)

For computational geometry operations (segment intersection, distance calculations), geographic coordinates (latitude/longitude in degrees) must be converted to a local Cartesian coordinate system where distances are in meters. The system uses a simplified equirectangular projection centered at the user's current position:

```
x = (longitude − refLongitude) × 111,320 × cos(refLatitude)    [meters, eastward positive]
y = (latitude  − refLatitude)  × 111,320                        [meters, northward positive]
```

Where:
- `111,320` is the approximate number of meters per degree of latitude (derived from Earth's circumference: 40,075 km / 360°).
- The cosine correction factor `cos(refLatitude)` accounts for the convergence of meridians at higher latitudes: at the equator, one degree of longitude equals 111,320 meters, but at latitude 38° (Greece), it equals approximately 111,320 × cos(38°) ≈ 87,740 meters.

**Why this approximation is sufficient**: At the scale of this application (200m query radius), the maximum error introduced by the equirectangular projection compared to a true geodesic calculation is less than 0.01%, which is far below the positional accuracy of consumer GPS devices (typically 3–10 meters).

**Implementation**: `IntersectionDetector.toLocalX()` and `toLocalY()` (lines 197–203 of `IntersectionDetector.java`). The `cosRefLat` value is precomputed once per query to avoid repeated trigonometric calls.

---

## 4. Intersection Detection Algorithm

### 4.1 Overview

The core of the system is the geometry-based intersection detector (`IntersectionDetector.findForwardIntersections()`). This method determines where the road the user is walking on intersects with other named roads, considering only intersections that lie ahead of the user.

### 4.2 Step 1: Road Classification

The method begins by classifying all OSM ways from the Overpass data into two groups:

1. **Current road ways**: Ways whose resolved name fuzzy-matches the provided current road name. A single named road (e.g., "Kremou") may consist of multiple OSM ways due to how OpenStreetMap represents roads — each way is a sequence of nodes, and a long road is typically split into multiple ways at intersections or where attributes change.

2. **Other named ways**: All remaining ways that have a name tag. Unnamed ways (service roads, unnamed alleys) are excluded since they cannot provide meaningful cross-street information.

The road name resolution uses a priority scheme:
- First, use the `name` tag (the native Greek name from OSM). If present and non-blank, use it.
- Otherwise, fall back to `name:en` (the English name tag) if available.

This ensures the system operates entirely in Greek script, matching OSM road names directly against Greek target names without any transliteration step.

### 4.3 Step 2: Segment Extraction

Each OSM way is a polyline — an ordered list of lat/lon coordinates. The system converts each consecutive pair of coordinates into a line segment in local meters:

```
Way with coordinates [P₀, P₁, P₂, P₃] produces segments:
  Segment 0: P₀ → P₁
  Segment 1: P₁ → P₂
  Segment 2: P₂ → P₃
```

All coordinates are projected to the local 2D meter system (Section 3.4) with the user's current position as the origin (0, 0).

### 4.4 Step 3: Segment-Segment Intersection Test

For each pair of segments — one from the current road, one from another named road — the system tests whether they intersect using the standard cross-product method.

Given two line segments:
- Segment A from point (ax₁, ay₁) to (ax₂, ay₂)
- Segment B from point (bx₁, by₁) to (bx₂, by₂)

Define direction vectors:
```
dx₁ = ax₂ − ax₁,   dy₁ = ay₂ − ay₁    (direction of segment A)
dx₂ = bx₂ − bx₁,   dy₂ = by₂ − by₁    (direction of segment B)
```

Compute the cross product of the direction vectors (the denominator):
```
denom = dx₁ · dy₂ − dy₁ · dx₂
```

If `|denom| < 10⁻¹⁰`, the segments are parallel or collinear and do not produce a unique intersection point; the method returns null.

Otherwise, compute the parametric coordinates of the intersection:
```
t = ((bx₁ − ax₁) · dy₂ − (by₁ − ay₁) · dx₂) / denom
u = ((bx₁ − ax₁) · dy₁ − (by₁ − ay₁) · dx₁) / denom
```

The parameter `t` represents where along segment A the intersection occurs (0 = start, 1 = end), and `u` represents where along segment B it occurs. The intersection lies within both segments if and only if:
```
0 ≤ t ≤ 1   AND   0 ≤ u ≤ 1
```

If both conditions hold, the intersection point in local meters is:
```
ix = ax₁ + t · dx₁
iy = ay₁ + t · dy₁
```

**Implementation**: `IntersectionDetector.segmentIntersection()` in `IntersectionDetector.java`.

### 4.5 Step 4: Minimum Crossing Angle Filter

A geometric intersection between two road segments does not by itself confirm that those roads are truly crossing. When a road changes its name — a common occurrence in Greek cities, where a long boulevard may be known by different names in different municipal districts — OpenStreetMap stores it as two separate `OsmWay` objects connected end-to-end at a shared node. Since the names are different, the name classifier (Step 1) places the continuation way in the *other named ways* group. The segment test (Step 3) then finds an intersection at the shared endpoint. Without further filtering, this junction would be reported as a cross-street.

The minimum crossing angle filter rejects these false positives by requiring that the two roads meet at a meaningful angle. The angle θ between two road segments is derived from the magnitude of their cross product:

```
sin(θ) = |dx₁ · dy₂ − dy₁ · dx₂| / (|v₁| · |v₂|)
```

where v₁ = (dx₁, dy₁) is the direction of the current road segment and v₂ = (dx₂, dy₂) is the direction of the candidate cross-street segment.

If `sin(θ) < sin(25°) ≈ 0.42`, the intersection is discarded as a near-collinear continuation:

```
crossProd = |dx₁ · dy₂ − dy₁ · dx₂|
if crossProd / (|v₁| · |v₂|) < MIN_CROSSING_ANGLE_SIN:
    discard intersection    (road continuation, not a genuine cross-street)
```

**Rationale**: A genuine cross-street typically meets the current road at 60°–120°. A road continuation (even with a different name) meets end-to-end at 0°–20°. The 25° threshold sits well between these two cases and is robust to moderate road curvature at junctions.

**Implementation**: The `MIN_CROSSING_ANGLE_SIN` constant in `IntersectionDetector.java`, applied inside `findForwardIntersections()` immediately after each segment intersection is found.

### 4.6 Step 5: Forward Direction Filtering

Not all geometric intersections are relevant — the user only cares about cross-streets that lie ahead of them, not behind. The system computes the bearing from the user's position to each intersection point and compares it to the user's forward bearing:

```
angleDiff = |bearingToIntersection − forwardBearing|
if angleDiff > 180°:
    angleDiff = 360° − angleDiff    (use the shorter arc)

if angleDiff ≥ 90°:
    discard intersection             (it's behind or to the side)
```

This 90-degree threshold defines a forward semicircle: any intersection within ±90° of the user's heading is considered "forward". This is deliberately generous to account for GPS noise, road curvature, and the fact that cross-streets may not be perfectly perpendicular.

### 4.7 Step 6: Deduplication and Sorting

A single cross-street may intersect the current road at multiple points (e.g., if the cross-street consists of multiple OSM ways, or if the current road has a complex geometry). The system keeps only the closest intersection per road name, using a `LinkedHashMap` keyed by the resolved road name.

The final result is a list of `Intersection` records sorted by distance (closest first). Each record contains:
- `point`: The geographic coordinates (lat/lon) of the intersection
- `distanceMeters`: The Euclidean distance from the user in local meters
- `bearing`: The bearing from the user to the intersection
- `roadName`: The resolved name of the cross-street

### 4.8 Computational Complexity

The segment intersection test has complexity O(C × N), where:
- C = total number of segments in current road ways
- N = total number of segments in all other named ways

In practice, with a 200m query radius in an urban Greek context, a typical query returns 50–200 ways with 2–10 segments each, resulting in 100–2000 total segments. The intersection testing completes in under 100ms on modern hardware, which is negligible compared to the Overpass API round-trip time.

---

## 5. Road Name Resolution and Fuzzy Matching

### 5.1 The Challenge of Greek Street Names

Both OSM road names and test dataset target names are in Greek script. String matching between them presents the following challenges:

1. **Accent marks (tonos)**: The same road may appear with or without accent marks in different OSM ways or data sources. For example, "Βιθυνίας" and "Βιθυνιας" are the same name with and without the tonos on ί.

2. **Word order variation**: OSM may store a street name in a different word order than the annotated target. For example, "Κωνσταντίνου Τσαλδάρη" and "Τσαλδάρη Κωνσταντίνου" refer to the same street.

3. **Abbreviations**: Greek street names commonly use abbreviations. "Κωνσταντίνου Παλαμά" is often shortened to "Κ. Παλαμά".

4. **Last-name emphasis**: Greek street names typically consist of a first name and last name. The surname (last word) is the most distinctive and recognizable part.

### 5.2 Road Name Normalization

Before fuzzy matching, road names undergo normalization to produce a canonical Greek form:

```
Step 1: Lowercase the string (Greek Unicode lowercase)
Step 2: Unicode NFD decomposition — decomposes precomposed characters
        (e.g., ά → α + combining tonos mark)
Step 3: Strip all Unicode combining marks (category \p{M}) —
        removes tonos, diaeresis, and other diacritics
Step 4: Collapse consecutive whitespace to a single space
```

The result is an unaccented, lowercase Greek string. Examples:

- "Βιθυνίας" → "βιθυνιας"
- "Κωνσταντίνου Τσαλδάρη" → "κωνσταντινου τσαλδαρη"
- "ΑΓΙΟΥ ΑΡΤΕΜΙΟΥ" → "αγιου αρτεμιου"

**Implementation**: `RoadNameMatcher.normalize()` uses `java.text.Normalizer.normalize(s, Form.NFD)` followed by `replaceAll("\\p{M}", "")`.

### 5.3 Multi-Tier Fuzzy Matching

The fuzzy matching algorithm (`RoadNameMatcher.fuzzyMatch()`, shared by both `IntersectionDetector` and `EvaluationEngine`) employs six matching tiers, evaluated in order of decreasing strictness:

**Tier 1: Exact match after normalization**
```
normalize("Βιθυνίας") = "βιθυνιας"
normalize("Βιθυνιας") = "βιθυνιας"
→ MATCH
```

**Tier 2: Word-set equality (order-independent)**
```
normalize("Κωνσταντίνου Τσαλδάρη") → words: {"κωνσταντινου", "τσαλδαρη"}
normalize("Τσαλδάρη Κωνσταντίνου") → words: {"τσαλδαρη", "κωνσταντινου"}
Sets are equal → MATCH
```

This tier handles the common case where OSM stores a name in reverse word order compared to the annotated target.

**Tier 3: Substring containment**
```
if normalize(A) contains normalize(B), or vice versa → MATCH
```

**Tier 4: Abbreviation matching**
Checks whether each word in the shorter name matches a distinct word in the longer name, either as a prefix (abbreviation) or within edit distance 1 (single-character spelling variant). Matching is **order-independent**: words may appear in any order in either name.

```
shorter = ["νικ", "γκυζη"]
longer  = ["γυζη", "νικολαου"]

"νικ"    is a prefix of "νικολαου"       → matched
"γκυζη"  vs "γυζη": edit distance = 1   → matched (spelling variant)
All words matched → ABBREVIATION MATCH
```

The edit distance allowance of 1 handles single-character spelling variants of the same street name that appear across different OSM ways or annotation sources (e.g., "Γκύζη" / "Γύζη"). The algorithm is bidirectional: it tries both `abbreviationMatchDirectional(A, B)` and `abbreviationMatchDirectional(B, A)`.

**Tier 5: Last-word Levenshtein distance (threshold ≤ 2)**
```
"Παναγή Τσαλδάρη" → last word after normalize: "τσαλδαρη"
"Π Τσαλδάρη"      → last word after normalize: "τσαλδαρη"
Levenshtein distance = 0 → MATCH
```

This tier exploits the observation that in Greek street names, the surname (last word) is the most distinctive identifier.

**Tier 6: Proportional edit distance (threshold ≤ 30%)**
```
maxAllowed = max(2, floor(min(|normA|, |normB|) × 0.3))
if levenshteinDistance(normA, normB) ≤ maxAllowed → MATCH
```

This allows roughly one character error per three characters of the shorter string, with a minimum allowance of 2 edits.

### 5.4 Levenshtein Distance Implementation

The Levenshtein distance (minimum edit distance) is computed using the standard dynamic programming algorithm with O(mn) time and space complexity, where m and n are the string lengths:

```
dp[0][j] = j                    (insertions to create target prefix)
dp[i][0] = i                    (deletions to create empty string)
dp[i][j] = min(
    dp[i-1][j] + 1,             (deletion)
    dp[i][j-1] + 1,             (insertion)
    dp[i-1][j-1] + cost          (substitution, cost=0 if chars equal)
)
```

**Implementation**: `RoadNameMatcher.levenshteinDistance()` in `util/RoadNameMatcher.java`. This is the single shared implementation used by both intersection detection and evaluation.

---

## 6. Overpass API Integration

### 6.1 Query Construction

The system queries the Overpass API to retrieve road geometries within a bounding box centered on the user's position. The Overpass QL query is constructed as follows:

```
[out:json][timeout:10][maxsize:2097152];
way["highway"~"^(motorway|trunk|primary|secondary|tertiary|
residential|unclassified|living_street|pedestrian|service|
motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]
(south,west,north,east);
out geom qt;
```

Key design decisions:

- **`out geom`**: Requests inline geometry for each way element. This avoids the costly `>;` (recurse down) step that would require a second pass to resolve node coordinates. The response includes the full coordinate array directly in each way element.
- **`qt`** (sort by quadtile): Optimizes server-side processing by sorting elements geographically, which is more efficient than the default sort order.
- **`maxsize:2097152`** (2 MB): Sets an explicit memory limit for the query to prevent accidental overuse of server resources.
- **`timeout:10`**: Server-side execution timeout in seconds.
- **Highway type filter**: Uses a regex to match standard road types. The filter includes both primary types (motorway, residential, etc.) and link types (*_link) for highway on/off ramps and connection roads. This excludes non-road features like footways, cycleways, and tracks.

**Bounding box computation**:
```
latOffset = queryRadius / 111,320
lonOffset = queryRadius / (111,320 × cos(latitude))

south = latitude  − latOffset
north = latitude  + latOffset
west  = longitude − lonOffset
east  = longitude + lonOffset
```

The longitude offset includes a cosine correction to ensure the bounding box is approximately square in meters despite the convergence of meridians.

### 6.2 HTTP Client and Retry Logic

The `OverpassClient` uses OkHttp 4 as the HTTP client. Requests are sent as `POST` with the query URL-encoded as form data (`application/x-www-form-urlencoded`).

**Retry strategy** (up to 3 retries):

| Condition | Action |
|-----------|--------|
| HTTP 429 (Too Many Requests) | Retry with exponential backoff: 5s, 10s, 20s |
| HTTP 504 (Gateway Timeout) | Retry with exponential backoff: 5s, 10s, 20s |
| `SocketTimeoutException` | Retry immediately (connection-level timeout) |
| Other HTTP errors | Fail immediately with `IOException` |

The backoff durations are computed as `INITIAL_BACKOFF_MS × 2^attempt`, starting at 5,000ms.

**Configurable timeouts**:
- Connect timeout: 10 seconds (default)
- Read timeout: 30 seconds (default)

### 6.3 Response Parsing

The Overpass API returns JSON with the following structure:

```json
{
  "elements": [
    {
      "type": "way",
      "id": 12345678,
      "geometry": [
        {"lat": 37.956799, "lon": 23.698016},
        {"lat": 37.956850, "lon": 23.698050},
        ...
      ],
      "tags": {
        "highway": "residential",
        "name": "Κρέμου",
        "name:en": "Kremou"
      }
    },
    ...
  ]
}
```

The parser (`OverpassClient.parseResponse()`) iterates through the `elements` array, filtering for `type: "way"`, and extracts:
- `id`: The OSM way ID (long integer)
- `geometry`: An ordered list of `LatLon` coordinate pairs
- `tags`: A map of all OSM tags (road name, highway type, etc.)

The parsed data is encapsulated in typed Java records:
- `LatLon(double lat, double lon)` — a single coordinate
- `OsmWay(long id, List<LatLon> geometry, Map<String, String> tags)` — a road segment
- `OverpassData(List<OsmWay> ways)` — the complete response

---

## 7. Spatial Caching

### 7.1 Motivation

The Overpass API's public endpoint (`overpass-api.de`) applies rate limiting, typically throttling clients to approximately one request per 3 seconds. In batch evaluation scenarios where consecutive test cases are geographically close (e.g., multiple test points along the same street), redundant queries waste time and risk rate-limit penalties.

### 7.2 Cache Strategy

The system implements a simple spatial cache with the following logic:

```
if (cachedData exists AND haversineDistance(newCenter, cachedCenter) ≤ cachedRadius × 0.3):
    return cachedData    (cache hit)
else:
    fetch fresh data from Overpass
    update cache with new data, center, and radius
```

The 30% threshold means the cache is valid as long as the user has moved no more than 30% of the query radius from the previous query center. With the default 200m radius, this allows up to 60m of movement before a new query is triggered.

**Rationale for 30%**: At this threshold, the user is still well within the previously queried area, and all roads relevant to their immediate surroundings are already available in the cache. A higher threshold (e.g., 50%) would risk missing roads near the edge of the original query area.

### 7.3 Implementation

The cache state is stored as three instance fields in `CrossStreetDetectorApp`:
- `lastRoadData`: The most recent `OverpassData` response
- `lastCenter`: The geographic center of the last query
- `lastQueryRadius`: The radius of the last query

Thread safety is ensured by the `synchronized` keyword on the `detect()` method and its accessors, which is necessary when serving concurrent API requests via the Javalin HTTP server.

---

## 8. Auto-Detection of Current Road

### 8.1 Use Case

The system always auto-detects the current road by analyzing the user's position relative to nearby road geometries. There is no option to provide the road name manually — detection is fully automatic.

### 8.2 Algorithm

`IntersectionDetector.findNearestRoad()` computes the minimum perpendicular distance from the user's position (projected to local meters as the origin 0,0) to every segment of every named road in the Overpass data:

```
For each named way:
    For each segment (pair of consecutive coordinates):
        distance = pointToSegmentDistance(userPosition, segment)
        if distance < minimumDistance:
            minimumDistance = distance
            closestRoadName = resolvedName
return closestRoadName
```

### 8.3 Point-to-Segment Distance

The perpendicular distance from a point P to a line segment AB is computed as follows:

```
t = clamp(((P − A) · (B − A)) / |B − A|², 0, 1)
projection = A + t × (B − A)
distance = |P − projection|
```

Where:
- `t` is the parameter along segment AB closest to P (clamped to [0, 1] to stay within the segment)
- If `t = 0`, the closest point is A; if `t = 1`, the closest point is B
- If `0 < t < 1`, the closest point is the perpendicular foot on the segment
- `|B − A|²` is the squared length of the segment (if zero, the segment is a degenerate point)

**Implementation**: `IntersectionDetector.pointToSegmentDistance()` (lines 230–242 of `IntersectionDetector.java`).

---

## 9. Batch Evaluation System

### 9.1 Purpose

The batch evaluation system provides a systematic way to measure the detection algorithm's accuracy against a curated set of ground-truth test cases. Since the project has no automated unit tests, batch evaluation serves as the primary validation mechanism.

### 9.2 Test Dataset

The active test dataset is `src/main/resources/test_data_annotation.csv`. It contains real-world test cases from Athens with **target road names in Greek script** (matching OSM data directly).

Each test case consists of:
- **Previous Coordinates**: The GPS position one step earlier (used to compute direction)
- **Current Coordinates**: The GPS position at the moment of query
- **Target Road**: The expected cross-street name in Greek (ground truth)
- **Google Maps Link**: Directions link for annotation verification
- **City**: The city name (for reporting)

The CSV uses comma delimiters with double-quoted coordinate fields (since coordinates contain commas) and UTF-8 encoding. `BatchEvaluator` also accepts semicolon-delimited files (as produced by `TestDataGenerator`) via automatic delimiter detection.

### 9.3 Evaluation Flow

```
For each test case:
    1. Call CrossStreetDetectorApp.detect(current, previous)
    2. Extract detected road name from result
    3. Fuzzy-match detected name against target name (using RoadNameMatcher)
    4. Record outcome: PASS / FAIL / ERROR
    5. If FAIL: save annotated debug image to debug/case-NNN.png
    6. Wait 1 second (Overpass API rate limiting)

Print summary report:
    Per-case results table
    Aggregate statistics (PASS/FAIL/ERROR counts, success rate)

Write results CSV to results/evaluation-results.csv
```

### 9.4 Debug Image Generation

For each failed test case, `DebugImageSaver` generates an annotated 1000×1000 pixel PNG image that visualizes the detection context:

**Image contents**:
- **Black background**
- **Green polylines**: All roads from the Overpass data, rendered as 6-pixel-wide antialiased lines
- **Road name labels**: Small text labels at the midpoint of each road way
- **Red cross at center**: The user's current position
- **White arrow**: The user's forward heading direction (bearing arrow)
- **Yellow circle + label**: The closest detected intersection (the system's answer)
- **Gray dots + labels**: Other forward intersections that were detected but not chosen
- **Top info panel**: Semi-transparent banner showing row number, current road, target road, and detected road
- **Bottom caption**: Summary bar with test case identification

**Coordinate-to-pixel projection**:
```
pixelX = imageCenter + (lon − centerLon) × 111,320 × cos(centerLat) / 0.265
pixelY = imageCenter − (lat − centerLat) × 111,320 / 0.265
```

The scale factor of 0.265 meters per pixel corresponds to approximately zoom level 18 at mid-latitudes, providing a view radius of approximately 132 meters in each direction from the center.

These debug images are invaluable for diagnosing failure modes: they reveal whether the issue is incorrect road geometry in OSM, a fuzzy matching failure, or a genuine algorithmic limitation.

### 9.5 Output Format

The results CSV (`evaluation-results.csv`) uses semicolons as delimiters for compatibility with European locale Excel installations:

```
Row;Previous Coordinates;Current Coordinates;Target Road;Detected Road;Result;City
```

---

## 10. Academic Test Data Generation

### 10.1 Purpose

The `TestDataGenerator` class automates the creation of an annotation-ready dataset by mining OpenStreetMap road geometries for realistic pedestrian GPS point pairs. It targets approximately 500 cases across six Greek cities.

### 10.2 Design Rationale

The generator produces point pairs that simulate pedestrian walking — two consecutive GPS positions approximately 15 meters apart along a real road. **Critically, pairs are placed mid-block** (well away from intersections), so that when the annotator opens the Google Maps link, it is unambiguous which road comes up next. Placing pairs on or near an intersection would make it unclear whether the annotator should name the road just passed or the road just ahead, leading to annotation inconsistencies.

The actual cross-street name (ground truth) is intentionally left blank for manual annotation by a human via Google Maps, ensuring independent verification.

### 10.3 City Distribution

The generator targets six Greek cities with varying urban layouts:

| City | Target Count | Query Radius | Characteristics |
|------|-------------|--------------|-----------------|
| Athens | 150 | 3000m | Dense urban grid, extensive road network |
| Thessaloniki | 100 | 2500m | Second-largest city, mixed grid and irregular layout |
| Patras | 80 | 2000m | Coastal grid city |
| Heraklion | 60 | 2000m | Crete's largest city, historical center |
| Larissa | 60 | 2000m | Central Greece, regular grid |
| Volos | 50 | 2000m | Coastal city, compact center |

### 10.4 Overpass Query

For each city, the generator queries OSM for named roads of standard highway types within a radius of the city center:

```
[out:json][timeout:60];
way["highway"~"primary|secondary|tertiary|residential"]["name"]
(around:<radius>,<lat>,<lon>);
out geom;
```

The `around` filter is used instead of a bounding box to produce a circular query area centered on the city. Only ways with a `name` tag are returned, since unnamed roads cannot serve as meaningful cross-streets. The `out geom` format provides inline geometry, consistent with the existing `OverpassClient` parser.

### 10.5 Intersection Node Detection

The generator identifies **intersection nodes** — geometry coordinates shared by two or more OSM ways — to define the boundaries between road blocks:

```
For each way in the query results:
    For each geometry node (lat, lon):
        key = round(lat, 7 decimals) + "," + round(lon, 7 decimals)
        increment count for this key (once per way)

Intersection nodes = keys with count ≥ 2
```

OSM ways that share a physical intersection point have identical geometry coordinates at that node. Rounding to 7 decimal places (sub-meter precision) handles any floating-point representation differences while maintaining accuracy.

Rather than placing points *near* intersections, the generator uses these nodes to define **arcs** — road segments between consecutive intersections — and places point pairs in the middle of each arc.

### 10.6 Point Pair Generation Algorithm

For each named road, the generator finds arcs between consecutive intersection nodes and places one mid-block pair per arc:

```
Find all intersection node indices I₁, I₂, I₃, ... in this road's geometry

For each consecutive pair (Iₖ, Iₖ₊₁):
    Build arc = list of geometry points from Iₖ to Iₖ₊₁
    Compute cumulative distances along arc
    arcLen = total arc length

    if arcLen < (MIN_SETBACK × 2 + TARGET_PAIR_DISTANCE):
        skip                   // Arc too short; both points would be too close to intersections

    currDist = MIN_SETBACK + TARGET_PAIR_DISTANCE / 2
    prevDist = currDist − TARGET_PAIR_DISTANCE

    curr = interpolate point at currDist along arc
    prev = interpolate point at prevDist along arc

    emit (prev, curr)

    if emitted ≥ MAX_PAIRS_PER_ROAD:
        stop
```

Key parameters:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Target pair distance | 15m | Realistic pedestrian GPS step spacing |
| Min setback from intersection | 30m | Ensures points are clearly mid-block, not at a crossing |
| Min arc length | 75m | 30m + 15m + 30m; arcs shorter than this cannot fit a mid-block pair |
| Max pairs per road | 4 | Forces road diversity in the dataset |

### 10.7 Arc Interpolation

Points are placed at precise distances along the arc using linear interpolation between geometry nodes:

```
Walk cumulative distances along arc until target distance is reached.
For the segment [Pᵢ₋₁, Pᵢ] where cumDist[i] ≥ targetDist:
    frac = (targetDist − cumDist[i−1]) / (cumDist[i] − cumDist[i−1])
    lat  = Pᵢ₋₁.lat + frac × (Pᵢ.lat − Pᵢ₋₁.lat)
    lon  = Pᵢ₋₁.lon + frac × (Pᵢ.lon − Pᵢ₋₁.lon)
```

This linear interpolation is sufficient given the small distances involved (the error from treating the Earth as flat over 15m is negligible).

### 10.8 Sampling

After generating all candidate pairs for a city, the generator shuffles them using a deterministic seed (the city name's hash code) and truncates to the target count. The deterministic seed ensures reproducibility — running the generator twice with the same OSM data produces the same dataset.

### 10.9 Rate Limiting and Retry Logic

The Overpass API's public endpoint enforces rate limits. The generator implements two layers of protection:

1. **Inter-city delay**: A 5-second pause between city queries to avoid triggering rate limits.
2. **Per-city retry**: If a city query fails (HTTP 429, 504, or timeout), the generator retries up to 3 times with a 30-second cooldown between attempts. This handles transient server-side issues such as the Overpass server being under load.

The `OverpassClient`'s own retry logic (exponential backoff on 429/504) provides an additional layer of resilience within each query attempt.

### 10.10 Output Format

The generator produces a **semicolon-delimited** CSV with the following columns:

```
Previous Coordinates;Current Coordinates;Target Road;Google Maps Link;City
```

- **Previous Coordinates**: The earlier GPS position (e.g., `37.983800, 23.727500`)
- **Current Coordinates**: The current GPS position
- **Target Road**: Left empty — to be filled in by the human annotator in Greek
- **Google Maps Link**: A Google Maps Directions URL from previous→current coordinates
- **City**: The city name for reporting

Semicolons are used as the delimiter so that coordinate values (which contain commas) do not require quoting, keeping the file clean and easy to edit in a spreadsheet.

The Google Maps link uses the format:
```
https://www.google.com/maps/dir/<prevLat>,<prevLon>/<currLat>,<currLon>
```

All coordinate values are formatted with `Locale.US` to ensure dot decimal separators regardless of the system's locale.

### 10.11 Annotation Workflow

1. Open the generated `annotation-dataset.csv` in a spreadsheet application (Google Sheets or Excel)
2. For each row, click the Google Maps Directions link
3. The link shows a route from previous→current — this reveals the **walking direction**
4. The two points are mid-block: look ahead in the walking direction and identify the **next cross-street** — it should be immediately obvious since the points are not near any intersection
5. Type the cross-street name in the **Target Road** column **in Greek** (e.g., `Βιθυνίας`)
6. Save as CSV — the annotated file can be used directly with `BatchEvaluator`

### 10.12 Usage

```bash
# Generate with default output path (results/annotation-dataset.csv)
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.TestDataGenerator

# Generate with custom output path
java -cp target/Cross-street-detector-1.0-SNAPSHOT.jar gr.crossstreet.TestDataGenerator output.csv
```

---

## 11. Configuration System

### 11.1 Architecture

`AppConfig` is a thread-safe singleton that loads configuration from `application.properties` on the classpath at first access. It supports environment variable substitution: any property value of the form `${ENV_VAR}` is replaced with the corresponding system environment variable at load time.

### 11.2 Configuration Parameters

| Property | Default | Type | Description |
|----------|---------|------|-------------|
| `overpass.api.url` | `https://overpass-api.de/api/interpreter` | String | Overpass API endpoint URL |
| `overpass.map.query.radius` | `200` | Integer | Radius in meters for the road geometry query bounding box |
| `http.connect.timeout.seconds` | `10` | Integer | TCP connection timeout for OkHttp client |
| `http.read.timeout.seconds` | `30` | Integer | Read timeout for OkHttp client |

### 11.3 Environment Variable Substitution

To use a different Overpass endpoint (e.g., a self-hosted instance), set the property to an environment variable reference:

```properties
overpass.api.url=${OVERPASS_URL}
```

Then set the environment variable:
```bash
export OVERPASS_URL=http://localhost:12345/api/interpreter
```

The resolution happens once at startup. If the environment variable is not set, a warning is logged and the property retains its `${...}` value.

---

## 12. Data Models

All data models are implemented as Java 21 records (immutable value types with compiler-generated `equals()`, `hashCode()`, and `toString()`).

### 12.1 GeoPoint

```java
public record GeoPoint(double latitude, double longitude)
```

Represents a geographic coordinate pair. Provides:
- `parse(String)`: Parses "lat, lon" strings (with flexible whitespace)
- `toApiString()`: Formats as "lat,lon" (no space, for API calls)

### 12.2 DetectionResult

```java
public record DetectionResult(
    GeoPoint currentPosition,
    GeoPoint targetPoint,
    double distanceMeters,
    double bearingToIntersection,
    Optional<String> roadName
)
```

The output of the detection pipeline. Contains the intersection coordinates, distance, bearing, and the resolved road name. The `roadName` is `Optional.empty()` when no intersection was found.

### 12.3 TestCase

```java
public record TestCase(
    int rowNumber,
    GeoPoint previousCoords,
    GeoPoint currentCoords,
    String targetRoad,
    String city
)
```

A single test case from the batch evaluation dataset. The current road is auto-detected at runtime rather than provided as input.

### 12.4 EvalResult

```java
public record EvalResult(
    TestCase testCase,
    Outcome outcome,
    String detectedRoad,
    String errorMessage
)
```

Defined as an inner record of `EvaluationEngine`. Represents the result of evaluating a single test case. The `outcome` is one of `PASS`, `FAIL`, or `ERROR`.

### 12.5 Overpass Data Records

```java
public record LatLon(double lat, double lon)
public record OsmWay(long id, List<LatLon> geometry, Map<String, String> tags)
public record OverpassData(List<OsmWay> ways)
```

Typed representations of Overpass API response data. `OsmWay` contains the full inline geometry (coordinate list) and all OSM tags.

### 12.6 Intersection

```java
public record Intersection(
    GeoPoint point,
    double distanceMeters,
    double bearing,
    String roadName
)
```

Defined as an inner record of `IntersectionDetector`. Represents a single detected intersection between the current road and a cross-street.

---

## 13. Evolution from Image-Based to Geometry-Based Detection

### 13.1 Original Image-Based Approach

The initial version of the system (not on this branch) used a five-step pipeline:

1. **Bearing calculation**: Same as current.
2. **Map image retrieval**: Fetched a styled Google Maps Static API image where roads were rendered in pure green (#00FF00) on a black background, with all POIs and labels hidden.
3. **Pixel scanning**: From the image center, scanned outward along two angles perpendicular to the user's walking direction (±25° offset from reverse bearing). The scan detected the first green pixel, which indicated the nearest cross-street.
4. **Point projection**: Converted the pixel distance to a geographic coordinate using the image scale (0.265 m/pixel at zoom 18).
5. **Road name resolution**: Used the Google Roads API to snap the projected point to the nearest road, then the Geocoding API to resolve the road's name.

### 13.2 Limitations of the Image-Based Approach

1. **Proprietary API dependency**: Required a Google Maps API key with three enabled APIs (Static Maps, Roads, Geocoding), incurring costs and creating a barrier to reproducibility.
2. **Image rendering artifacts**: Antialiasing, road width variations at different zoom levels, and overlapping roads could produce false positives or missed detections during pixel scanning.
3. **Fixed scanning angles**: The ±25° perpendicular scan was a heuristic that failed for non-perpendicular intersections, T-junctions, or curved roads approaching an intersection at oblique angles.
4. **Single-direction detection**: The algorithm chose the closer of the two perpendicular scan results, meaning it could only detect one direction at a time.
5. **No road name awareness during scanning**: The pixel scan had no knowledge of which road it was detecting — it simply found the nearest green pixel, which could be any road (including the user's own road if the geometry was complex).

### 13.3 Advantages of the Geometry-Based Approach

1. **Open access**: Relies solely on OpenStreetMap data via the free Overpass API. No API keys, no costs, no proprietary dependencies.
2. **Direct intersection computation**: Instead of inferring road positions from pixels, the system computes exact geometric intersections between road segments. This eliminates all image-related artifacts.
3. **Road-aware detection**: The system explicitly identifies which road the user is on and which roads intersect it, providing semantic understanding rather than pixel-level detection.
4. **Multi-intersection detection**: The system finds all forward intersections simultaneously, sorted by distance, rather than being limited to a single perpendicular scan.
5. **Simpler, more maintainable**: The geometry-based approach is conceptually clearer and has fewer tunable parameters (no zoom level, pixel scale, skip pixels, scan angle offset).
6. **Reproducibility**: Any researcher can reproduce results without setting up API keys or configuring image rendering services.

### 13.4 What Was Removed

- `GoogleMapsClient`: HTTP client for Google Maps Static API
- `RoadFinderClient`: HTTP client for Google Roads API and Geocoding API
- `ImageProcessor`: Green-pixel road detection via radial ray scanning
- `OverpassMapRenderer`: Java2D rendering of OSM roads as green-on-black images
- Image-specific configuration: `staticmap.zoom`, `staticmap.size`, `staticmap.scale`, `image.skip.pixels`, `image.scale`, `detection.angle.offset`

### 13.5 What Was Added

- `ApiServer`: REST API server using Javalin, providing HTTP access to the detection pipeline
- `IntersectionDetector`: Full geometry-based intersection detection engine with auto-detection of current road
- `RoadNameMatcher`: Shared fuzzy Greek road name matching utility (extracted from duplicated logic in `IntersectionDetector` and `EvaluationEngine`); operates entirely in Greek script with Unicode accent normalization
- `GreekTransliterator`: Greek-to-Latin transliteration utility (retained but no longer used in the main pipeline; road names are matched in Greek script directly)
- `DebugImageSaver`: Diagnostic image generator for failed cases
- Spatial caching in `CrossStreetDetectorApp`
- Thread-safe `synchronized` detection for concurrent API requests

---

## 14. Technology Stack

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Language | Java | 21 | Records, text blocks, pattern matching |
| Build | Maven | 3.13.0+ | Dependency management, fat JAR packaging |
| Web Server | Javalin | 6.4.0 | Lightweight REST API server |
| HTTP Client | OkHttp | 4.12.0 | Overpass API communication |
| JSON Parser | Jackson Databind | 2.17.0 | Overpass response deserialization and REST JSON |
| JSON Modules | Jackson JDK8 | 2.17.0 | `Optional` serialization support for API responses |
| Logging Facade | SLF4J | 2.0.12 | Structured logging abstraction |
| Logging Impl | Logback | 1.5.3 | Console and file logging |
| External Data | OpenStreetMap | — | Road network data (open access) |
| External API | Overpass API | 0.7.x | OSM data query engine |

The project is built as a fat JAR (uber-JAR) using the Maven Shade Plugin, which bundles all dependencies into a single executable JAR file. The default main class is `ApiServer` (REST API); the CLI entry point (`CrossStreetDetectorApp`) can be invoked via `-cp`.

---

## 15. Limitations and Future Work

### 15.1 Current Limitations

1. **OSM data quality**: The system's accuracy depends entirely on the completeness and correctness of OpenStreetMap road data. Missing roads, unnamed roads, or incorrect geometries will cause detection failures.
2. **GPS accuracy**: Consumer GPS devices typically have 3–10 meter accuracy. If the user is far from their actual position, the bearing calculation may be inaccurate, and the auto-detection of the current road may fail.
3. **Rate limiting**: The public Overpass API has rate limits. Batch evaluation with 100 test cases requires approximately 5 minutes (3s delay between queries) plus API processing time.
4. **Single-shot queries**: While the REST API enables integration with real-time clients, the detection pipeline itself handles single-shot queries. A real-time navigation system would need continuous GPS tracking, bearing smoothing, and incremental updates on the client side.
5. **Greek-specific optimizations**: The fuzzy matching logic (Unicode accent normalization, word-set comparison, last-word Levenshtein) is tailored for Greek street names. Supporting other languages would require adapting the normalization and matching heuristics accordingly.

### 15.2 Potential Improvements

1. **Bearing smoothing**: Using a Kalman filter or moving average over multiple GPS positions to reduce bearing noise.
2. **Confidence scoring**: Assigning a confidence score to each detection based on distance, road geometry quality, and matching tier.
3. **Real-time mode**: Implementing a streaming GPS interface with continuous intersection detection.
4. **Self-hosted Overpass**: Running a local Overpass instance to eliminate rate limiting and reduce latency.
5. **Multi-language support**: Generalizing the transliteration and fuzzy matching systems to support other scripts and languages.
