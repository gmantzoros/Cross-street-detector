package gr.crossstreet.model;

import org.jetbrains.annotations.NotNull;

/**
 * Holds the left and right search angles derived from the movement bearing.
 */
public record BearingAngles(double leftAngle, double rightAngle) {

    @NotNull
    @Override
    public String toString() {
        return "BearingAngles[left=%.2f°, right=%.2f°]".formatted(leftAngle, rightAngle);
    }
}