package telemetry.vehicle;

/**
 * Helium pressurisation for one stage's two propellant tanks. While the bottle holds
 * enough pressure the regulators keep both tanks at their set points. Below that the
 * ullage expands as propellant drains and tank pressure falls with it.
 */
final class Pressurization {

    private static final double REGULATOR_MARGIN = 15.0;
    private static final double INITIAL_ULLAGE = 0.03;

    private final double loxSetPoint;
    private final double rp1SetPoint;
    /** How far the bottle pressure falls over a full tank of propellant. */
    private final double bottleSwing;
    private double bottle;
    private double lox;
    private double rp1;
    private double leakRate;

    Pressurization(double bottle, double bottleSwing, double loxSetPoint, double rp1SetPoint) {
        this.bottle = bottle;
        this.bottleSwing = bottleSwing;
        this.loxSetPoint = loxSetPoint;
        this.rp1SetPoint = rp1SetPoint;
        this.lox = loxSetPoint;
        this.rp1 = rp1SetPoint;
    }

    void startLeak(double barPerSecond) {
        leakRate = barPerSecond;
    }

    void update(double dt, double fractionUsed, double fractionRemaining) {
        bottle = Math.max(1.0, bottle - bottleSwing * fractionUsed - leakRate * dt);
        boolean regulated = bottle > Math.max(loxSetPoint, rp1SetPoint) + REGULATOR_MARGIN;
        if (regulated) {
            lox = loxSetPoint;
            rp1 = rp1SetPoint;
        } else if (fractionUsed > 0) {
            double ullage = 1 - fractionRemaining + INITIAL_ULLAGE;
            double expansion = fractionUsed / ullage;
            lox -= lox * expansion;
            rp1 -= rp1 * expansion;
        }
    }

    double bottle() {
        return bottle;
    }

    double lox() {
        return lox;
    }

    double rp1() {
        return rp1;
    }
}
