package gr.crossstreet.geo;

import gr.crossstreet.api.OverpassClient;
import gr.crossstreet.model.GeoPoint;

import java.util.List;

/**
 * Street-network orientation entropy, after Boeing (2019), <i>"Urban spatial order: street network
 * orientation, configuration, and entropy"</i>, Applied Network Science 4:67.
 *
 * <p>The measure quantifies how ordered a street network's bearings are. Every street segment
 * contributes its compass bearing, weighted by its length, to one of {@value #BIN_COUNT} bins of
 * 10°. Because a street has no inherent direction of travel, each segment is counted twice — at
 * bearing θ and at θ+180° — so the distribution is symmetric. Shannon entropy H over that
 * distribution is then:</p>
 *
 * <pre>
 *   H = -Σ p_i · ln(p_i)
 * </pre>
 *
 * <p>H is bounded below by ln(4) ≈ 1.386 (a perfect four-way orthogonal grid, in which all length
 * falls into four equally weighted bins) and above by ln(36) ≈ 3.584 (bearings spread uniformly
 * over every bin). Boeing rescales it into an <b>orientation order</b> φ ∈ [0, 1]:</p>
 *
 * <pre>
 *   φ = 1 - ((H - H_min) / (H_max - H_min))²
 * </pre>
 *
 * <p>φ ≈ 1 denotes a perfectly gridded city; φ ≈ 0 a network with no orientational bias at all.
 * For reference, Boeing reports φ ≈ 0.90 for Chicago and φ ≈ 0.003 for Charlotte.</p>
 *
 * <h2>Binning</h2>
 * <p>Bins are centred on the cardinal directions rather than starting at them: bin 0 spans
 * [355°, 5°). This matters for grid cities aligned close to north — without the offset a
 * north–south street would split its length across two adjacent bins and register as more
 * disordered than it is. The implementation follows OSMnx's approach of histogramming into
 * {@value #BIN_COUNT}×2 half-width bins, rotating by one, and summing adjacent pairs.</p>
 */
public final class OrientationEntropy {

    /** Number of 10° bins the compass is divided into. */
    public static final int BIN_COUNT = 36;

    /** Entropy of a uniform distribution over all bins — maximum possible disorder. */
    public static final double MAX_ENTROPY = Math.log(BIN_COUNT);

    /** Entropy of a perfect four-way orthogonal grid — minimum realistic disorder. */
    public static final double MIN_ENTROPY = Math.log(4);

    private OrientationEntropy() {
        // Utility class
    }

    /**
     * Orientation statistics for one street network.
     *
     * @param entropy           Shannon entropy H in nats, over the 36-bin bearing distribution
     * @param orientationOrder  Boeing's φ ∈ [0, 1]; 1 = perfect grid, 0 = no orientational order
     * @param normalisedEntropy H / ln(36) ∈ [0, 1], the raw entropy as a fraction of its maximum
     * @param segmentCount      number of straight segments (vertex pairs) that contributed
     * @param totalLengthMeters summed length of those segments, before the bidirectional doubling
     * @param binWeights        length-weighted share of each bin, summing to 1; index i is centred
     *                          on bearing i×10°
     */
    public record Result(
            double entropy,
            double orientationOrder,
            double normalisedEntropy,
            int segmentCount,
            double totalLengthMeters,
            double[] binWeights
    ) {
        /** True when too little network was found for the entropy to mean anything. */
        public boolean isDegenerate() {
            return segmentCount == 0 || totalLengthMeters <= 0;
        }
    }

    /** Computes orientation entropy over every segment of the given ways. */
    public static Result compute(List<OverpassClient.OsmWay> ways) {
        return compute(ways, null, Double.POSITIVE_INFINITY);
    }

    /**
     * Computes orientation entropy over the segments lying within {@code radiusMeters} of
     * {@code center}, measured from each segment's midpoint. Passing a null centre includes
     * every segment.
     *
     * <p>Restricting the radius yields a <em>local</em> orientation order for one neighbourhood,
     * which is what a per-test-case analysis needs: a city-wide φ averages over districts a given
     * GPS trace never visits.</p>
     */
    public static Result compute(List<OverpassClient.OsmWay> ways, GeoPoint center, double radiusMeters) {
        // Half-width bins, so that after rotating by one and pairing them the resulting
        // 10° bins are centred on 0°, 10°, 20° ... rather than starting there.
        double[] halfBins = new double[BIN_COUNT * 2];
        double halfBinWidth = 360.0 / (BIN_COUNT * 2);

        int segmentCount = 0;
        double totalLength = 0;

        for (OverpassClient.OsmWay way : ways) {
            List<OverpassClient.LatLon> geom = way.geometry();
            for (int i = 0; i + 1 < geom.size(); i++) {
                GeoPoint a = new GeoPoint(geom.get(i).lat(), geom.get(i).lon());
                GeoPoint b = new GeoPoint(geom.get(i + 1).lat(), geom.get(i + 1).lon());

                double length = GeoUtils.haversineDistance(a, b);
                // Duplicate consecutive vertices carry no bearing information.
                if (length <= 0) continue;

                if (center != null && radiusMeters != Double.POSITIVE_INFINITY) {
                    GeoPoint midpoint = new GeoPoint((a.latitude() + b.latitude()) / 2,
                            (a.longitude() + b.longitude()) / 2);
                    if (GeoUtils.haversineDistance(center, midpoint) > radiusMeters) continue;
                }

                double bearing = GeoUtils.calculateBearing(a, b);

                // A street is undirected: count it at both θ and θ+180°.
                halfBins[(int) (bearing / halfBinWidth) % halfBins.length] += length;
                halfBins[(int) (((bearing + 180.0) % 360.0) / halfBinWidth) % halfBins.length] += length;

                segmentCount++;
                totalLength += length;
            }
        }

        // Rotate right by one half-bin, then fold adjacent pairs into the final 10° bins.
        double[] bins = new double[BIN_COUNT];
        double weightSum = 0;
        for (int i = 0; i < BIN_COUNT; i++) {
            int lower = Math.floorMod(2 * i - 1, halfBins.length);
            bins[i] = halfBins[lower] + halfBins[2 * i];
            weightSum += bins[i];
        }

        if (weightSum <= 0) {
            return new Result(0, 0, 0, 0, 0, bins);
        }

        double entropy = 0;
        for (int i = 0; i < BIN_COUNT; i++) {
            bins[i] /= weightSum;
            if (bins[i] > 0) {
                entropy -= bins[i] * Math.log(bins[i]);
            }
        }

        return new Result(entropy, orientationOrder(entropy), entropy / MAX_ENTROPY,
                segmentCount, totalLength, bins);
    }

    /**
     * Rescales an entropy in nats to Boeing's orientation order φ ∈ [0, 1].
     * Values are clamped, since a network can in principle concentrate into fewer than four
     * bins (a single straight road) and so undershoot the grid baseline H_min.
     */
    public static double orientationOrder(double entropy) {
        double normalised = (entropy - MIN_ENTROPY) / (MAX_ENTROPY - MIN_ENTROPY);
        double order = 1.0 - normalised * normalised;
        return Math.clamp(order, 0.0, 1.0);
    }
}
