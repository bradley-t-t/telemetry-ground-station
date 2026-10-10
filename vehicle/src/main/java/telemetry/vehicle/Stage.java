package telemetry.vehicle;

/** One stage: its engines, propellant, pressurisation, avionics and state of motion. */
final class Stage {

    private static final double AVIONICS_WARMUP = 300.0;
    private static final double NOZZLE_HEATING = 12.0;
    private static final double NOZZLE_COOLING = 120.0;

    final String name;
    final Engine[] engines;
    final Pressurization pressurization;
    private final double dryMass;
    private final double propellantCapacity;
    private double propellant;
    private double attachedMass;

    double throttle = 1.0;
    Vec2 position = Earth.launchSite();
    Vec2 velocity = Vec2.ZERO;
    Vec2 thrustDirection = new Vec2(0, 1);
    /** Non-gravitational acceleration, what an accelerometer reads, in m/s^2. */
    double sensedAcceleration = Earth.G0;
    double busVoltage = 28.1;
    double avionicsTemperature = 24.0;
    double nitrogenPressure;
    double nozzleTemperature = 20.0;

    Stage(String name, Engine[] engines, double dryMass, double propellant, double attachedMass,
          Pressurization pressurization, double nitrogenPressure) {
        this.name = name;
        this.engines = engines;
        this.dryMass = dryMass;
        this.propellantCapacity = propellant;
        this.propellant = propellant;
        this.attachedMass = attachedMass;
        this.pressurization = pressurization;
        this.nitrogenPressure = nitrogenPressure;
    }

    Engine engine(int number) {
        return engines[number - 1];
    }

    double mass() {
        return dryMass + propellant + attachedMass;
    }

    double propellant() {
        return propellant;
    }

    double propellantFraction() {
        return propellant / propellantCapacity;
    }

    void release(double mass) {
        attachedMass = Math.max(0, attachedMass - mass);
    }

    boolean anyEngineCommanded() {
        for (Engine engine : engines) {
            if (engine.commanded()) {
                return true;
            }
        }
        return false;
    }

    int enginesInMainstage() {
        int count = 0;
        for (Engine engine : engines) {
            if (engine.mainstage()) {
                count++;
            }
        }
        return count;
    }

    void shutdownAll() {
        for (Engine engine : engines) {
            engine.command(false);
        }
    }

    double thrust(double ambientPressure) {
        if (propellant <= 0) {
            return 0;
        }
        double total = 0;
        for (Engine engine : engines) {
            total += engine.thrust(throttle, ambientPressure);
        }
        return total;
    }

    /** Thrust at full throttle from the engines that are running now. */
    double fullThrust(double ambientPressure) {
        double total = 0;
        for (Engine engine : engines) {
            total += engine.thrust(1.0, ambientPressure);
        }
        return total;
    }

    void updateEngines(double dt) {
        for (Engine engine : engines) {
            engine.update(dt);
        }
    }

    void consume(double dt) {
        double flow = 0;
        for (Engine engine : engines) {
            flow += engine.massFlow(throttle);
        }
        double used = Math.min(propellant, flow * dt);
        propellant -= used;
        pressurization.update(dt, used / propellantCapacity, propellantFraction());
    }

    void updateAvionics(double dt, boolean flying) {
        double gimbalLoad = enginesInMainstage() > 0 ? 0.3 : 0;
        busVoltage = 28.1 - gimbalLoad;
        double warmTarget = flying ? 38.0 : 24.0;
        avionicsTemperature += (warmTarget - avionicsTemperature) * (1 - Math.exp(-dt / AVIONICS_WARMUP));

        double output = engines[0].massFlow(1.0) > 0 ? throttle : 0;
        boolean burning = output > 0.05;
        double nozzleTarget = burning ? 620 + 500 * output : 20;
        double timeConstant = burning ? NOZZLE_HEATING : NOZZLE_COOLING;
        nozzleTemperature += (nozzleTarget - nozzleTemperature) * (1 - Math.exp(-dt / timeConstant));
    }

    void useNitrogen(double barPerSecond, double dt) {
        nitrogenPressure = Math.max(0, nitrogenPressure - barPerSecond * dt);
    }
}
