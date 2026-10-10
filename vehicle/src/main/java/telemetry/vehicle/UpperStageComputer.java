package telemetry.vehicle;

import telemetry.protocol.FlightEvent;

/**
 * Flies stage 2 from separation to orbit. Steering is an explicit guidance law: each
 * cycle it estimates the time left in the burn from the rocket equation, then picks the
 * vertical acceleration that arrives at the target altitude with zero vertical speed as
 * the horizontal speed reaches circular velocity.
 */
final class UpperStageComputer {

    enum Mode {
        STACKED, BURN, ORBIT, DEPLOYED
    }

    static final double IGNITION_DELAY = 7.0;
    static final double PAYLOAD_DELAY = 40.0;

    private static final double TARGET_ALTITUDE = 205_000;
    private static final double CUTOFF_PERIGEE = 200_000;
    private static final double FAIRING_ALTITUDE = 112_000;
    private static final double ACCELERATION_LIMIT = 4.4 * Earth.G0;
    private static final double MIN_THROTTLE = 0.39;
    private static final double MAX_PITCH_RATE = 2.0;
    private static final double GUIDANCE_FREEZE = 4.0;

    private final Stage stage;
    private final FlightEvents events;
    private Mode mode = Mode.STACKED;
    private boolean fairingAttached = true;
    private double separationTime = Double.NaN;
    private double cutoffTime = Double.NaN;
    private double pitch;

    UpperStageComputer(Stage stage, FlightEvents events) {
        this.stage = stage;
        this.events = events;
    }

    Mode mode() {
        return mode;
    }

    boolean fairingAttached() {
        return fairingAttached;
    }

    void separated(double met, Nav nav) {
        separationTime = met;
        pitch = nav.flightPathAngle();
    }

    void update(Situation situation, double dt) {
        Nav nav = situation.nav();
        switch (mode) {
            case STACKED -> {
                stage.thrustDirection = nav.direction(pitch);
                if (!Double.isNaN(separationTime) && situation.met() >= separationTime + IGNITION_DELAY) {
                    stage.engine(1).command(true);
                    stage.throttle = 1.0;
                    mode = Mode.BURN;
                    events.emit(FlightEvent.S2_IGNITION, 0);
                }
            }
            case BURN -> {
                steer(situation, dt);
                if (fairingAttached && nav.altitude() > FAIRING_ALTITUDE) {
                    fairingAttached = false;
                    stage.release(Vehicle.FAIRING);
                    events.emit(FlightEvent.FAIRING_SEPARATION, nav.altitude() / 1000);
                }
                if (nav.perigee() >= CUTOFF_PERIGEE || stage.propellant() <= 0) {
                    stage.shutdownAll();
                    mode = Mode.ORBIT;
                    cutoffTime = situation.met();
                    events.emit(FlightEvent.SECO, nav.perigee() / 1000);
                }
            }
            case ORBIT -> {
                stage.thrustDirection = nav.direction(0);
                if (situation.met() >= cutoffTime + PAYLOAD_DELAY) {
                    stage.release(Vehicle.PAYLOAD);
                    mode = Mode.DEPLOYED;
                    events.emit(FlightEvent.PAYLOAD_SEPARATION, Vehicle.PAYLOAD);
                }
            }
            case DEPLOYED -> stage.thrustDirection = nav.direction(0);
        }
    }

    private void steer(Situation situation, double dt) {
        Nav nav = situation.nav();
        double mass = situation.mass();
        double rated = stage.engine(1).ratedThrust(0);
        double throttle = BoosterComputer.clamp(ACCELERATION_LIMIT * mass / rated, MIN_THROTTLE, 1.0);
        stage.throttle = throttle;

        double thrustAcceleration = rated * throttle / mass;
        double exhaustVelocity = Vehicle.UPPER_ENGINE_ISP * Earth.G0;
        double massFlow = rated * throttle / exhaustVelocity;
        double circular = Math.sqrt(Earth.MU / (Earth.RADIUS + TARGET_ALTITUDE));
        double deltaV = Math.hypot(circular - nav.horizontalSpeed(), nav.verticalSpeed());
        double timeToGo = mass / massFlow * (1 - Math.exp(-deltaV / exhaustVelocity));

        if (timeToGo > GUIDANCE_FREEZE) {
            double shortfall = TARGET_ALTITUDE - nav.altitude() - nav.verticalSpeed() * timeToGo;
            double vertical = 6 * shortfall / (timeToGo * timeToGo) + 2 * nav.verticalSpeed() / timeToGo;
            double radius = Earth.RADIUS + nav.altitude();
            double netGravity = Earth.MU / (radius * radius) - nav.horizontalSpeed() * nav.horizontalSpeed() / radius;
            double sine = BoosterComputer.clamp((vertical + netGravity) / thrustAcceleration, -0.5, 0.9);
            pitch = BoosterComputer.approach(pitch, Math.toDegrees(Math.asin(sine)), MAX_PITCH_RATE * dt);
        }
        stage.thrustDirection = nav.direction(pitch);
    }
}
