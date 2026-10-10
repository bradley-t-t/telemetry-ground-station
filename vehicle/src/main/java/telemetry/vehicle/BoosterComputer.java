package telemetry.vehicle;

import java.util.Arrays;
import telemetry.protocol.FlightEvent;

/**
 * Flies stage 1: the countdown, the ascent with the second stage on top, then the flip,
 * entry burn and landing burn onto a ship downrange. It also watches its own engines and
 * shuts down any that loses chamber pressure.
 */
final class BoosterComputer {

    enum Mode {
        PAD, ASCENT, COAST, ENTRY_BURN, DESCENT, LANDING_BURN, LANDED, LOST
    }

    static final double IGNITION_LEAD = 3.0;
    static final double SEPARATION_DELAY = 3.0;
    static final double SAFE_TOUCHDOWN_SPEED = 8.0;

    private static final double KICK_SPEED = 55;
    private static final double KICK_ANGLE = 4.3;
    private static final double KICK_DURATION = 8;
    private static final double MAX_PITCH_RATE = 3;
    private static final double MAX_Q_TRIGGER = 21_000;
    private static final double MAX_Q_THROTTLE = 0.8;
    private static final double ACCELERATION_LIMIT = 4.2 * Earth.G0;
    private static final double MIN_THROTTLE = 0.4;
    /** Propellant held back at main engine cutoff for the entry and landing burns. */
    private static final double LANDING_RESERVE = 26_000;
    private static final double ENTRY_ALTITUDE = 56_000;
    private static final double ENTRY_EXIT_SPEED = 950;
    private static final double ENTRY_THROTTLE = 0.7;
    private static final int[] ENTRY_ENGINES = {1, 2, 6};
    /** The landing burn is only considered once drag has slowed the booster below this. */
    private static final double LANDING_GATE_SPEED = 400;
    private static final int LANDING_ENGINE = 1;
    private static final double LANDING_IGNITION_THROTTLE = 0.85;
    private static final double LEG_DEPLOY_ALTITUDE = 600;
    private static final double FLIP_START = 1;
    private static final double FLIP_END = 14;
    private static final double GRID_FIN_DEPLOY = 12;
    private static final double FAILURE_CONFIRM_TIME = 0.5;

    private final Stage stage;
    private final FlightEvents events;
    private final double[] lowPressureSince;
    private Mode mode = Mode.PAD;
    private boolean ignited;
    private boolean gravityTurn;
    private boolean throttledDown;
    private boolean throttledUp;
    private boolean gridFins;
    private boolean legs;
    private double pitch = 90;
    private double kickStart = Double.NaN;
    private double peakDynamicPressure;
    private double cutoffTime = Double.NaN;

    BoosterComputer(Stage stage, FlightEvents events) {
        this.stage = stage;
        this.events = events;
        this.lowPressureSince = new double[stage.engines.length + 1];
        Arrays.fill(lowPressureSince, Double.NaN);
    }

    Mode mode() {
        return mode;
    }

    boolean released() {
        return mode != Mode.PAD;
    }

    boolean gridFinsDeployed() {
        return gridFins;
    }

    boolean legsDeployed() {
        return legs;
    }

    /** Free-flying after separation and not yet on the deck. */
    boolean descending() {
        return mode == Mode.COAST || mode == Mode.ENTRY_BURN || mode == Mode.DESCENT || mode == Mode.LANDING_BURN;
    }

    boolean readyToSeparate(double met) {
        return mode != Mode.PAD && !Double.isNaN(cutoffTime) && met >= cutoffTime + SEPARATION_DELAY;
    }

    void update(Situation situation, double dt, boolean separated) {
        switch (mode) {
            case PAD -> pad(situation);
            case ASCENT -> ascent(situation, dt);
            case COAST -> coast(situation, dt, separated);
            case ENTRY_BURN -> entryBurn(situation, dt);
            case DESCENT -> descent(situation, dt);
            case LANDING_BURN -> landingBurn(situation, dt);
            case LANDED, LOST -> stage.thrustDirection = situation.nav().up();
        }
        watchEngines(situation.met());
    }

    void touchdown(double speed) {
        stage.shutdownAll();
        if (speed <= SAFE_TOUCHDOWN_SPEED) {
            mode = Mode.LANDED;
            events.emit(FlightEvent.TOUCHDOWN, speed);
        } else {
            mode = Mode.LOST;
            events.emit(FlightEvent.BOOSTER_LOST, speed);
        }
    }

    private void pad(Situation situation) {
        stage.thrustDirection = situation.nav().up();
        if (!ignited && situation.met() >= -IGNITION_LEAD) {
            ignited = true;
            stage.throttle = 1.0;
            for (Engine engine : stage.engines) {
                engine.command(true);
            }
            events.emit(FlightEvent.IGNITION, stage.engines.length);
        }
        if (ignited && situation.met() >= 0 && stage.enginesInMainstage() == stage.engines.length) {
            mode = Mode.ASCENT;
            events.emit(FlightEvent.LIFTOFF, 0);
        }
    }

    private void ascent(Situation situation, double dt) {
        Nav nav = situation.nav();
        double target = 90;
        if (nav.speed() >= KICK_SPEED) {
            if (Double.isNaN(kickStart)) {
                kickStart = situation.met();
            }
            double progress = (situation.met() - kickStart) / KICK_DURATION;
            if (progress < 1) {
                target = 90 - KICK_ANGLE * progress;
            } else {
                // Hold the kick until the velocity vector has rotated down to meet it, then
                // fly a gravity turn: thrust along velocity, so the angle of attack stays zero.
                if (!gravityTurn && nav.flightPathAngle() <= pitch) {
                    gravityTurn = true;
                }
                target = gravityTurn ? nav.flightPathAngle() : 90 - KICK_ANGLE;
            }
        }
        pitch = approach(pitch, target, MAX_PITCH_RATE * dt);
        stage.thrustDirection = nav.direction(pitch);

        double q = situation.dynamicPressure();
        peakDynamicPressure = Math.max(peakDynamicPressure, q);
        if (!throttledDown && q > MAX_Q_TRIGGER) {
            throttledDown = true;
            events.emit(FlightEvent.THROTTLE_DOWN, MAX_Q_THROTTLE * 100);
        }
        if (throttledDown && !throttledUp && q < 0.8 * peakDynamicPressure) {
            throttledUp = true;
            events.emit(FlightEvent.THROTTLE_UP, 100);
        }
        double throttle = throttledDown && !throttledUp ? MAX_Q_THROTTLE : 1.0;
        double full = stage.fullThrust(situation.ambientPressure());
        if (full > 0) {
            throttle = Math.min(throttle, ACCELERATION_LIMIT * situation.mass() / full);
        }
        stage.throttle = clamp(throttle, MIN_THROTTLE, 1.0);

        if (stage.propellant() <= LANDING_RESERVE) {
            stage.shutdownAll();
            mode = Mode.COAST;
            cutoffTime = situation.met();
            events.emit(FlightEvent.MECO, stage.propellant());
        }
    }

    private void coast(Situation situation, double dt, boolean separated) {
        Nav nav = situation.nav();
        stage.thrustDirection = retrograde(nav);
        if (!separated) {
            return;
        }
        double sinceSeparation = situation.met() - cutoffTime - SEPARATION_DELAY;
        if (sinceSeparation > FLIP_START && sinceSeparation < FLIP_END) {
            stage.useNitrogen(5.0, dt);
        }
        if (!gridFins && sinceSeparation >= GRID_FIN_DEPLOY) {
            gridFins = true;
            events.emit(FlightEvent.GRID_FINS, 0);
        }
        if (sinceSeparation > FLIP_END && nav.altitude() < ENTRY_ALTITUDE && nav.verticalSpeed() < 0) {
            stage.throttle = ENTRY_THROTTLE;
            for (int number : ENTRY_ENGINES) {
                stage.engine(number).command(true);
            }
            mode = Mode.ENTRY_BURN;
            events.emit(FlightEvent.ENTRY_BURN_START, ENTRY_ENGINES.length);
        }
    }

    private void entryBurn(Situation situation, double dt) {
        Nav nav = situation.nav();
        stage.thrustDirection = retrograde(nav);
        stage.useNitrogen(0.4, dt);
        if (nav.speed() < ENTRY_EXIT_SPEED) {
            stage.shutdownAll();
            mode = Mode.DESCENT;
            events.emit(FlightEvent.ENTRY_BURN_END, nav.speed());
        }
    }

    private void descent(Situation situation, double dt) {
        Nav nav = situation.nav();
        stage.thrustDirection = retrograde(nav);
        stage.useNitrogen(0.4, dt);
        LandingCommand command = landingGuidance(situation);
        if (nav.speed() < LANDING_GATE_SPEED && command.ignitionThrottle() >= LANDING_IGNITION_THROTTLE) {
            stage.engine(LANDING_ENGINE).command(true);
            stage.throttle = clamp(command.throttle(), MIN_THROTTLE, 1.0);
            stage.thrustDirection = command.direction();
            mode = Mode.LANDING_BURN;
            events.emit(FlightEvent.LANDING_BURN_START, nav.altitude());
        }
    }

    private void landingBurn(Situation situation, double dt) {
        Nav nav = situation.nav();
        stage.useNitrogen(0.4, dt);
        LandingCommand command = landingGuidance(situation);
        stage.throttle = clamp(command.throttle(), MIN_THROTTLE, 1.0);
        stage.thrustDirection = command.direction();
        if (!legs && nav.altitude() < LEG_DEPLOY_ALTITUDE) {
            legs = true;
            events.emit(FlightEvent.LANDING_LEGS, nav.altitude());
        }
        if (nav.verticalSpeed() > -0.3 && nav.altitude() < 3) {
            stage.engine(LANDING_ENGINE).command(false);
        }
    }

    private record LandingCommand(double throttle, double ignitionThrottle, Vec2 direction) {
    }

    /**
     * Constant-deceleration landing guidance. It finds the acceleration that brings vertical
     * speed and altitude to zero together, nulls horizontal speed over the same time, and
     * subtracts what drag already provides. Ignition is judged without the drag credit,
     * because drag fades as the booster slows and the engine has to make up the difference.
     */
    private LandingCommand landingGuidance(Situation situation) {
        Nav nav = situation.nav();
        double height = Math.max(nav.altitude(), 0.1);
        double sink = Math.max(-nav.verticalSpeed(), 0.1);
        double timeToGo = 2 * height / sink;
        double radius = Earth.RADIUS + nav.altitude();
        double gravity = situation.gravity() - nav.horizontalSpeed() * nav.horizontalSpeed() / radius;

        double up = sink * sink / (2 * height) + gravity;
        double across = -nav.horizontalSpeed() / Math.max(timeToGo, 1);
        double dragUp = situation.aeroAcceleration().dot(nav.up());
        double dragAcross = situation.aeroAcceleration().dot(nav.east());

        double thrustUp = Math.max(up - dragUp, 0.5);
        double thrustAcross = clamp(across - dragAcross, -0.5 * thrustUp, 0.5 * thrustUp);
        double rated = stage.engine(LANDING_ENGINE).ratedThrust(situation.ambientPressure());
        double throttle = Math.hypot(thrustUp, thrustAcross) * situation.mass() / rated;
        double ignition = Math.hypot(up, across) * situation.mass() / rated;
        Vec2 direction = nav.east().times(thrustAcross).plus(nav.up().times(thrustUp)).unit();
        return new LandingCommand(throttle, ignition, direction);
    }

    private void watchEngines(double met) {
        for (Engine engine : stage.engines) {
            int n = engine.number;
            double expected = Engine.NOMINAL_CHAMBER_PRESSURE * stage.throttle;
            if (engine.mainstage() && engine.chamberPressure(stage.throttle) < 0.5 * expected) {
                if (Double.isNaN(lowPressureSince[n])) {
                    lowPressureSince[n] = met;
                } else if (met - lowPressureSince[n] >= FAILURE_CONFIRM_TIME) {
                    engine.command(false);
                    lowPressureSince[n] = Double.NaN;
                    events.emit(FlightEvent.ENGINE_SHUTDOWN, n);
                }
            } else {
                lowPressureSince[n] = Double.NaN;
            }
        }
    }

    private static Vec2 retrograde(Nav nav) {
        return nav.speed() > 1 ? Vec2.ZERO.minus(nav.up().times(nav.verticalSpeed()))
                .minus(nav.east().times(nav.horizontalSpeed())).unit() : nav.up();
    }

    static double approach(double value, double target, double maxStep) {
        return value + clamp(target - value, -maxStep, maxStep);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
