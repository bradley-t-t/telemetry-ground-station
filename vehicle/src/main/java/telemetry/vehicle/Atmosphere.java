package telemetry.vehicle;

/**
 * The 1976 US Standard Atmosphere up to 84.852 km geopotential altitude, continued above
 * that as an isothermal layer.
 */
final class Atmosphere {

    record Conditions(double density, double pressure, double temperature, double speedOfSound) {
    }

    private static final double[] BASE_HEIGHT = {0, 11_000, 20_000, 32_000, 47_000, 51_000, 71_000, 84_852};
    private static final double[] LAPSE_RATE = {-0.0065, 0, 0.001, 0.0028, 0, -0.0028, -0.002, 0};
    private static final double[] BASE_TEMPERATURE = new double[BASE_HEIGHT.length];
    private static final double[] BASE_PRESSURE = new double[BASE_HEIGHT.length];

    /** g0 * M / R*, in kelvin per metre. */
    private static final double GMR = 9.80665 * 0.0289644 / 8.3144598;
    private static final double GAS_CONSTANT = 287.053;
    private static final double GAMMA = 1.4;

    static {
        BASE_TEMPERATURE[0] = 288.15;
        BASE_PRESSURE[0] = 101_325;
        for (int i = 0; i < BASE_HEIGHT.length - 1; i++) {
            double depth = BASE_HEIGHT[i + 1] - BASE_HEIGHT[i];
            BASE_TEMPERATURE[i + 1] = BASE_TEMPERATURE[i] + LAPSE_RATE[i] * depth;
            BASE_PRESSURE[i + 1] = pressure(i, depth);
        }
    }

    private Atmosphere() {
    }

    static Conditions at(double altitude) {
        double z = Math.max(altitude, 0);
        double h = Earth.RADIUS * z / (Earth.RADIUS + z);
        int layer = BASE_HEIGHT.length - 1;
        while (h < BASE_HEIGHT[layer]) {
            layer--;
        }
        double above = h - BASE_HEIGHT[layer];
        double temperature = BASE_TEMPERATURE[layer] + LAPSE_RATE[layer] * above;
        double pressure = pressure(layer, above);
        return new Conditions(pressure / (GAS_CONSTANT * temperature), pressure, temperature,
                Math.sqrt(GAMMA * GAS_CONSTANT * temperature));
    }

    private static double pressure(int layer, double above) {
        double base = BASE_TEMPERATURE[layer];
        double lapse = LAPSE_RATE[layer];
        if (lapse == 0) {
            return BASE_PRESSURE[layer] * Math.exp(-GMR * above / base);
        }
        return BASE_PRESSURE[layer] * Math.pow(base / (base + lapse * above), GMR / lapse);
    }
}
