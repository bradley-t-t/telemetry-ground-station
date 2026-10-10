package telemetry.vehicle;

/** Drag area (Cd times reference area) as a function of Mach number for each configuration. */
@FunctionalInterface
interface Aerodynamics {

    double dragArea(double mach);

    /** The full stack, nose first. The 5.2 m fairing sets the reference area. */
    Aerodynamics STACK = mach -> interpolate(mach,
            new double[] {0, 0.6, 0.9, 1.1, 1.3, 2.0, 3.0, 5.0, 10},
            new double[] {0.30, 0.30, 0.42, 0.55, 0.52, 0.40, 0.32, 0.26, 0.24}) * area(5.2);

    /** The booster falling engines first with grid fins out. */
    Aerodynamics BOOSTER = mach -> interpolate(mach,
            new double[] {0, 0.6, 0.9, 1.2, 2.0, 4.0, 10},
            new double[] {1.00, 1.05, 1.25, 1.35, 1.20, 1.05, 1.00}) * area(3.66);

    /** The upper stage on its own. It is above almost all of the air by then. */
    Aerodynamics UPPER = mach -> 0.3 * area(3.66);

    private static double area(double diameter) {
        return Math.PI * diameter * diameter / 4;
    }

    private static double interpolate(double x, double[] xs, double[] ys) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }
}
