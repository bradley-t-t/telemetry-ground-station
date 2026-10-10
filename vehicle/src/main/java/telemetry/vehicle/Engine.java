package telemetry.vehicle;

/**
 * A throttleable liquid engine. Thrust follows commands through a first-order spool lag,
 * and a fault can cut its output ({@link #fail()}) or erode it ({@link #setHealth(double)}).
 */
final class Engine {

    /** Chamber pressure at full throttle, in bar. */
    static final double NOMINAL_CHAMBER_PRESSURE = 97.0;

    private static final double SPOOL_UP_TIME = 0.35;
    private static final double SPOOL_DOWN_TIME = 0.12;
    private static final double MAINSTAGE_SPOOL = 0.95;

    final int number;
    private final double vacuumThrust;
    private final double seaLevelThrust;
    private final double massFlow;
    private final double bias;
    private boolean commanded;
    private boolean failed;
    private boolean mainstage;
    private double spool;
    private double health = 1.0;

    Engine(int number, double seaLevelThrust, double vacuumThrust, double vacuumIsp, double bias) {
        this.number = number;
        this.seaLevelThrust = seaLevelThrust;
        this.vacuumThrust = vacuumThrust;
        this.massFlow = vacuumThrust / (vacuumIsp * Earth.G0);
        this.bias = bias;
    }

    void command(boolean on) {
        commanded = on;
        if (!on) {
            mainstage = false;
        }
    }

    void fail() {
        failed = true;
    }

    void setHealth(double health) {
        this.health = health;
    }

    void update(double dt) {
        double target = commanded && !failed ? 1 : 0;
        double timeConstant = target > spool ? SPOOL_UP_TIME : SPOOL_DOWN_TIME;
        spool += (target - spool) * (1 - Math.exp(-dt / timeConstant));
        if (commanded && !failed && spool > MAINSTAGE_SPOOL) {
            mainstage = true;
        }
    }

    boolean commanded() {
        return commanded;
    }

    /** Running at full chamber pressure, as the flight computer reports it. */
    boolean mainstage() {
        return mainstage;
    }

    /** Full-throttle thrust at this ambient pressure if the engine were running. */
    double ratedThrust(double ambientPressure) {
        return (vacuumThrust - (vacuumThrust - seaLevelThrust) * ambientPressure / 101_325) * health;
    }

    double thrust(double throttle, double ambientPressure) {
        return ratedThrust(ambientPressure) * throttle * spool;
    }

    double massFlow(double throttle) {
        return massFlow * throttle * spool * health;
    }

    double chamberPressure(double throttle) {
        return NOMINAL_CHAMBER_PRESSURE * (1 + bias) * throttle * spool * health;
    }
}
