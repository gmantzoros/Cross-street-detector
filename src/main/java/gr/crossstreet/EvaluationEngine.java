package gr.crossstreet;

import gr.crossstreet.image.DebugImageSaver;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.TestCase;
import gr.crossstreet.util.RoadNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates a single test case against the cross-street detector.
 *
 * <p>Simple pass/fail: if the detected road matches the target → PASS, otherwise → FAIL.</p>
 */
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final DebugImageSaver debugImageSaver;

    public EvaluationEngine() {
        this.debugImageSaver = null;
    }

    public EvaluationEngine(DebugImageSaver debugImageSaver) {
        this.debugImageSaver = debugImageSaver;
    }

    public enum Outcome { PASS, FAIL, ERROR }

    /**
     * @param testCase     the original test case
     * @param outcome      PASS, FAIL, or ERROR
     * @param detectedRoad the road name that led to this outcome
     * @param errorMessage error details if outcome is ERROR, null otherwise
     */
    public record EvalResult(
            TestCase testCase,
            Outcome outcome,
            String detectedRoad,
            String errorMessage
    ) {}

    /**
     * Evaluates one test case, returning a result that is always PASS, FAIL, or ERROR —
     * never throws.
     */
    public EvalResult evaluate(CrossStreetDetectorApp app, TestCase tc) {
        try {
            return evaluateInternal(app, tc);
        } catch (Exception e) {
            log.error("#{} | ERROR: {}", tc.rowNumber(), e.getMessage(), e);
            return new EvalResult(tc, Outcome.ERROR, "", e.getMessage());
        }
    }

    private EvalResult evaluateInternal(CrossStreetDetectorApp app, TestCase tc) throws Exception {
        DetectionResult detection = app.detect(tc.currentCoords(), tc.previousCoords());
        String detected = detection.roadName().orElse("UNKNOWN");

        log.info("#{} | detected='{}'", tc.rowNumber(), detected);

        if (RoadNameMatcher.fuzzyMatch(detected, tc.targetRoad())) {
            log.info("#{} | PASS | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), detected);
            return new EvalResult(tc, Outcome.PASS, detected, null);
        }

        log.warn("#{} | FAIL | Target: {} | Got: {}", tc.rowNumber(), tc.targetRoad(), detected);
        if (debugImageSaver != null) {
            debugImageSaver.save(tc, detection, app.getLastRoadData(), app.getLastIntersections());
        }
        return new EvalResult(tc, Outcome.FAIL, detected, "Wrong road");
    }

}
