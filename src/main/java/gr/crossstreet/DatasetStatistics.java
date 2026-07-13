package gr.crossstreet;

import gr.crossstreet.geo.GeoUtils;
import gr.crossstreet.model.GeoPoint;
import gr.crossstreet.model.TestCase;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts and prints statistics from the annotated test dataset
 */
public class DatasetStatistics {

    public static void main(String[] args) {
        String inputPath = args.length >= 1 ? args[0] : "src/main/resources/test_data_annotation_all.csv";

        BatchEvaluator loader = new BatchEvaluator();
        List<TestCase> testCases = loader.loadCsv(Path.of(inputPath));

        printOverallStats(testCases);
        printPerCityStats(testCases);
        printGpsStepStats(testCases);
        printRoadNameStats(testCases);
        printGeographicCoverage(testCases);
        printBearingDistribution(testCases);
    }

    private static void printOverallStats(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  DATASET OVERVIEW");
        System.out.println("=".repeat(80));
        System.out.printf("  Total test cases:         %d%n", testCases.size());

        Map<String, Long> cityCounts = testCases.stream()
                .collect(Collectors.groupingBy(TestCase::city, LinkedHashMap::new, Collectors.counting()));
        System.out.printf("  Number of cities:         %d%n", cityCounts.size());

        long uniqueRoads = testCases.stream().map(TestCase::targetRoad).distinct().count();
        System.out.printf("  Unique target road names: %d%n", uniqueRoads);

        long duplicateRoads = testCases.stream()
                .collect(Collectors.groupingBy(TestCase::targetRoad, Collectors.counting()))
                .values().stream().filter(c -> c > 1).count();
        System.out.printf("  Roads appearing >1 time:  %d%n", duplicateRoads);
        System.out.println();
    }

    private static void printPerCityStats(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  PER-CITY BREAKDOWN");
        System.out.println("=".repeat(80));

        Map<String, List<TestCase>> byCity = testCases.stream()
                .collect(Collectors.groupingBy(TestCase::city, LinkedHashMap::new, Collectors.toList()));

        System.out.printf("  %-18s %8s %14s %16s%n", "City", "Cases", "Unique Roads", "Coverage (km²)");
        System.out.println("  " + "-".repeat(60));

        for (var entry : byCity.entrySet()) {
            String city = entry.getKey();
            List<TestCase> cases = entry.getValue();
            long uniqueRoads = cases.stream().map(TestCase::targetRoad).distinct().count();
            double coverageKm2 = computeCoverageArea(cases);

            System.out.printf("  %-18s %8d %14d %13.2f%n", city, cases.size(), uniqueRoads, coverageKm2);
        }
        System.out.println();
    }

    private static void printGpsStepStats(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  GPS STEP DISTANCE (previous -> current position)");
        System.out.println("=".repeat(80));

        double[] distances = testCases.stream()
                .mapToDouble(tc -> GeoUtils.haversineDistance(tc.previousCoords(), tc.currentCoords()))
                .sorted()
                .toArray();

        System.out.printf("  Min:    %6.1f m%n", distances[0]);
        System.out.printf("  Max:    %6.1f m%n", distances[distances.length - 1]);
        System.out.printf("  Mean:   %6.1f m%n", Arrays.stream(distances).average().orElse(0));
        System.out.printf("  Median: %6.1f m%n", distances[distances.length / 2]);
        System.out.printf("  Std:    %6.1f m%n", stddev(distances));
        System.out.println();

        // Histogram
        System.out.println("  Distribution:");
        int[] bins = {0, 5, 10, 15, 20, 25, 30, 50, 100};
        for (int i = 0; i < bins.length - 1; i++) {
            int lo = bins[i], hi = bins[i + 1];
            long count = Arrays.stream(distances).filter(d -> d >= lo && d < hi).count();
            String bar = "#".repeat((int) (count * 40.0 / distances.length));
            System.out.printf("  %3d-%3d m: %3d (%5.1f%%) %s%n", lo, hi, count, count * 100.0 / distances.length, bar);
        }
        long above = Arrays.stream(distances).filter(d -> d >= bins[bins.length - 1]).count();
        System.out.printf("  %3d+   m: %3d (%5.1f%%)%n", bins[bins.length - 1], above, above * 100.0 / distances.length);
        System.out.println();
    }

    private static void printRoadNameStats(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  ROAD NAME CHARACTERISTICS");
        System.out.println("=".repeat(80));

        List<String> names = testCases.stream().map(TestCase::targetRoad).toList();

        int[] lengths = names.stream().mapToInt(String::length).sorted().toArray();
        System.out.printf("  Name length (chars):  min=%d, max=%d, mean=%.1f, median=%d%n",
                lengths[0], lengths[lengths.length - 1],
                Arrays.stream(lengths).average().orElse(0), lengths[lengths.length / 2]);

        int[] wordCounts = names.stream().mapToInt(n -> n.trim().split("\\s+").length).sorted().toArray();
        System.out.printf("  Word count per name:  min=%d, max=%d, mean=%.1f%n",
                wordCounts[0], wordCounts[wordCounts.length - 1],
                Arrays.stream(wordCounts).average().orElse(0));

        long singleWord = Arrays.stream(wordCounts).filter(w -> w == 1).count();
        long multiWord = names.size() - singleWord;
        System.out.printf("  Single-word names:    %d (%.1f%%)%n", singleWord, singleWord * 100.0 / names.size());
        System.out.printf("  Multi-word names:     %d (%.1f%%)%n", multiWord, multiWord * 100.0 / names.size());
        System.out.println();

        // Common prefixes in multi-word names (abbreviations, titles)
        Map<String, Long> prefixes = names.stream()
                .map(String::trim)
                .filter(n -> n.contains(" "))
                .map(n -> n.split("\\s+")[0])
                .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

        List<Map.Entry<String, Long>> topPrefixes = prefixes.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        if (!topPrefixes.isEmpty()) {
            System.out.println("  Common prefixes/titles in multi-word names:");
            for (var e : topPrefixes) {
                System.out.printf("    %-20s %d occurrences%n", e.getKey(), e.getValue());
            }
            System.out.println();
        }
    }

    private static void printGeographicCoverage(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  GEOGRAPHIC COVERAGE PER CITY");
        System.out.println("=".repeat(80));

        Map<String, List<TestCase>> byCity = testCases.stream()
                .collect(Collectors.groupingBy(TestCase::city, LinkedHashMap::new, Collectors.toList()));

        for (var entry : byCity.entrySet()) {
            String city = entry.getKey();
            List<TestCase> cases = entry.getValue();

            DoubleSummaryStatistics latStats = cases.stream()
                    .mapToDouble(tc -> tc.currentCoords().latitude())
                    .summaryStatistics();
            DoubleSummaryStatistics lonStats = cases.stream()
                    .mapToDouble(tc -> tc.currentCoords().longitude())
                    .summaryStatistics();

            double latRangeKm = GeoUtils.haversineDistance(
                    new GeoPoint(latStats.getMin(), lonStats.getAverage()),
                    new GeoPoint(latStats.getMax(), lonStats.getAverage())) / 1000.0;
            double lonRangeKm = GeoUtils.haversineDistance(
                    new GeoPoint(latStats.getAverage(), lonStats.getMin()),
                    new GeoPoint(latStats.getAverage(), lonStats.getMax())) / 1000.0;

            System.out.printf("  %s:%n", city);
            System.out.printf("    Latitude:   %.6f to %.6f  (%.2f km)%n",
                    latStats.getMin(), latStats.getMax(), latRangeKm);
            System.out.printf("    Longitude:  %.6f to %.6f  (%.2f km)%n",
                    lonStats.getMin(), lonStats.getMax(), lonRangeKm);
            System.out.printf("    Bounding box area: ~%.2f km²%n", latRangeKm * lonRangeKm);

            // Average pairwise distance between test points (sample if too many)
            double avgSpread = computeAverageSpread(cases);
            System.out.printf("    Avg spread between test points: %.2f km%n", avgSpread / 1000.0);
            System.out.println();
        }
    }

    private static void printBearingDistribution(List<TestCase> testCases) {
        System.out.println("=".repeat(80));
        System.out.println("  BEARING (TRAVEL DIRECTION) DISTRIBUTION");
        System.out.println("=".repeat(80));

        double[] bearings = testCases.stream()
                .mapToDouble(tc -> GeoUtils.calculateBearing(tc.previousCoords(), tc.currentCoords()))
                .toArray();

        // Cardinal direction bins
        String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int[] counts = new int[8];
        for (double b : bearings) {
            int idx = (int) Math.round(b / 45.0) % 8;
            counts[idx]++;
        }

        for (int i = 0; i < 8; i++) {
            String bar = "#".repeat((int) (counts[i] * 40.0 / bearings.length));
            System.out.printf("  %-3s: %3d (%5.1f%%) %s%n", labels[i], counts[i],
                    counts[i] * 100.0 / bearings.length, bar);
        }
        System.out.println();
    }

    // --- Helpers ---

    private static double computeCoverageArea(List<TestCase> cases) {
        DoubleSummaryStatistics latStats = cases.stream()
                .mapToDouble(tc -> tc.currentCoords().latitude()).summaryStatistics();
        DoubleSummaryStatistics lonStats = cases.stream()
                .mapToDouble(tc -> tc.currentCoords().longitude()).summaryStatistics();

        double latRangeKm = GeoUtils.haversineDistance(
                new GeoPoint(latStats.getMin(), lonStats.getAverage()),
                new GeoPoint(latStats.getMax(), lonStats.getAverage())) / 1000.0;
        double lonRangeKm = GeoUtils.haversineDistance(
                new GeoPoint(latStats.getAverage(), lonStats.getMin()),
                new GeoPoint(latStats.getAverage(), lonStats.getMax())) / 1000.0;

        return latRangeKm * lonRangeKm;
    }

    private static double computeAverageSpread(List<TestCase> cases) {
        if (cases.size() < 2) return 0;

        // Sample up to 500 random pairs to keep it fast
        List<GeoPoint> points = cases.stream().map(TestCase::currentCoords).toList();
        Random rng = new Random(42);
        int sampleSize = Math.min(500, points.size() * (points.size() - 1) / 2);
        double sum = 0;
        for (int s = 0; s < sampleSize; s++) {
            int i = rng.nextInt(points.size());
            int j = rng.nextInt(points.size() - 1);
            if (j >= i) j++;
            sum += GeoUtils.haversineDistance(points.get(i), points.get(j));
        }
        return sum / sampleSize;
    }

    private static double stddev(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(variance);
    }
}