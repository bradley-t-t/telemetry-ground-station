package telemetry.protocol;

/**
 * Caution (yellow) and warning (red) bounds for a channel. Any bound may be {@code NaN},
 * meaning unset; comparisons against NaN are always false, so unset bounds never trip.
 */
public record Limits(double redLow, double yellowLow, double yellowHigh, double redHigh) {

    public static final Limits NONE = new Limits(Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public boolean isDefined() {
        return !Double.isNaN(redLow) || !Double.isNaN(yellowLow)
                || !Double.isNaN(yellowHigh) || !Double.isNaN(redHigh);
    }

    public Severity classify(double value) {
        if (value < redLow || value > redHigh) {
            return Severity.WARNING;
        }
        if (value < yellowLow || value > yellowHigh) {
            return Severity.CAUTION;
        }
        return Severity.NOMINAL;
    }
}
