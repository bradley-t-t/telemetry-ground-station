package telemetry.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;

/**
 * A complete flight, stepped at 100 Hz: countdown, ascent, staging, the booster's return
 * and landing, and the upper stage's climb to orbit and payload release. Telemetry goes to
 * the {@link PacketSink} as the flight runs. Everything random is seeded, so the same seed
 * and faults produce the same packets byte for byte.
 */
public final class FlightSimulation {

    public static final double STEP = 0.01;
    /** Spacecraft clock reading at power-on. */
    public static final double CLOCK_AT_POWER_ON = 1_000.0;
    /** Seconds from power-on to liftoff. */
    public static final double COUNTDOWN = 15.0;

    private static final double COUNTDOWN_REPEAT = 2.0;
    private static final double END_DELAY = 10.0;
    private static final double MAX_DURATION = 1_500.0;

    public record EventRecord(FlightEvent event, double met, double arg) {
    }

    private final Stage booster;
    private final Stage upper;
    private final BoosterComputer boosterComputer;
    private final UpperStageComputer upperComputer;
    private final TelemetryGenerator telemetry;
    private final List<Fault> pendingFaults;
    private final List<Degradation> degradations = new ArrayList<>();
    private final List<EventRecord> events = new ArrayList<>();
    private final List<Consumer<EventRecord>> listeners = new ArrayList<>();
    private Situation boosterSituation;
    private Situation upperSituation;
    private double time;
    private double nextCountdownRepeat;
    private boolean separated;
    private double endTime = Double.NaN;
    private boolean endAnnounced;
    private boolean finished;

    public FlightSimulation(List<Fault> faults, PacketSink sink, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        this.booster = Vehicle.booster(random);
        this.upper = Vehicle.upper(random);
        this.boosterComputer = new BoosterComputer(booster, this::emit);
        this.upperComputer = new UpperStageComputer(upper, this::emit);
        this.telemetry = new TelemetryGenerator(this, Dictionary.standard(), sink, random.nextLong());
        this.pendingFaults = new ArrayList<>(faults);
        this.boosterSituation = situate(booster, booster.mass() + upper.mass(), Aerodynamics.STACK);
        this.upperSituation = boosterSituation;
        emit(FlightEvent.COUNTDOWN, CLOCK_AT_POWER_ON + COUNTDOWN);
        nextCountdownRepeat = COUNTDOWN_REPEAT;
    }

    public FlightSimulation(Scenario scenario, PacketSink sink, long seed) {
        this(scenario.faults(), scenario.link().isClean() ? sink : new ImpairedLink(sink, scenario.link(), seed), seed);
    }

    public void addListener(Consumer<EventRecord> listener) {
        listeners.add(listener);
    }

    public double time() {
        return time;
    }

    public double met() {
        return time - COUNTDOWN;
    }

    public boolean finished() {
        return finished;
    }

    public List<EventRecord> events() {
        return List.copyOf(events);
    }

    public void step() {
        if (finished) {
            return;
        }
        time += STEP;
        double met = met();
        injectFaults(met);

        double stackMass = booster.mass() + upper.mass();
        boosterSituation = situate(booster, separated ? booster.mass() : stackMass,
                separated ? Aerodynamics.BOOSTER : Aerodynamics.STACK);
        upperSituation = separated ? situate(upper, upper.mass(), Aerodynamics.UPPER) : boosterSituation;

        boosterComputer.update(boosterSituation, STEP, separated);
        if (!separated && boosterComputer.readyToSeparate(met)) {
            separated = true;
            upperComputer.separated(met, boosterSituation.nav());
            emit(FlightEvent.STAGE_SEPARATION, 0);
        }
        upperComputer.update(upperSituation, STEP);

        booster.updateEngines(STEP);
        upper.updateEngines(STEP);
        if (!separated) {
            if (boosterComputer.released()) {
                fly(booster, stackMass, Aerodynamics.STACK);
            } else {
                hold(booster);
            }
            upper.position = booster.position;
            upper.velocity = booster.velocity;
            upper.sensedAcceleration = booster.sensedAcceleration;
        } else {
            if (boosterComputer.descending()) {
                fly(booster, booster.mass(), Aerodynamics.BOOSTER);
            } else {
                hold(booster);
            }
            fly(upper, upper.mass(), Aerodynamics.UPPER);
        }
        booster.consume(STEP);
        upper.consume(STEP);
        booster.updateAvionics(STEP, boosterComputer.released());
        upper.updateAvionics(STEP, boosterComputer.released());

        if (separated && boosterComputer.descending() && Earth.altitude(booster.position) <= 0) {
            touchdown();
        }
        if (time >= nextCountdownRepeat && met < 0) {
            telemetry.event(FlightEvent.COUNTDOWN, CLOCK_AT_POWER_ON, CLOCK_AT_POWER_ON + COUNTDOWN, time);
            nextCountdownRepeat += COUNTDOWN_REPEAT;
        }
        telemetry.sample(time, CLOCK_AT_POWER_ON + time);
        checkEnd();
    }

    Stage booster() {
        return booster;
    }

    Stage upper() {
        return upper;
    }

    BoosterComputer boosterComputer() {
        return boosterComputer;
    }

    UpperStageComputer upperComputer() {
        return upperComputer;
    }

    Situation boosterSituation() {
        return boosterSituation;
    }

    Situation upperSituation() {
        return upperSituation;
    }

    private void emit(FlightEvent event, double arg) {
        EventRecord record = new EventRecord(event, met(), arg);
        events.add(record);
        telemetry.event(event, CLOCK_AT_POWER_ON + time, arg, time);
        if (event == FlightEvent.BOOSTER_LOST) {
            telemetry.silenceBooster();
        }
        for (Consumer<EventRecord> listener : listeners) {
            listener.accept(record);
        }
    }

    private void injectFaults(double met) {
        var iterator = pendingFaults.iterator();
        while (iterator.hasNext()) {
            Fault fault = iterator.next();
            if (met < fault.time()) {
                continue;
            }
            iterator.remove();
            switch (fault) {
                case Fault.EngineFailure(int engine, double ignored) -> booster.engine(engine).fail();
                case Fault.EngineDegradation(int engine, double start, double loss, double ramp) ->
                        degradations.add(new Degradation(booster.engine(engine), start, loss, ramp));
                case Fault.HeliumLeak(int stage, double ignored, double rate) ->
                        (stage == 1 ? booster : upper).pressurization.startLeak(rate);
                case Fault.StuckSensor(String channel, double ignored) -> telemetry.freeze(channel);
            }
        }
        for (Degradation degradation : degradations) {
            double progress = Math.min(1, (met - degradation.start) / degradation.ramp);
            degradation.engine.setHealth(1 - degradation.loss * progress);
        }
    }

    private Situation situate(Stage stage, double mass, Aerodynamics aerodynamics) {
        Nav nav = Nav.of(stage.position, stage.velocity);
        Atmosphere.Conditions air = Atmosphere.at(nav.altitude());
        double speed = nav.speed();
        double q = 0.5 * air.density() * speed * speed;
        double mach = speed / air.speedOfSound();
        Vec2 drag = speed > 0
                ? stage.velocity.times(-q * aerodynamics.dragArea(mach) / (mass * speed))
                : Vec2.ZERO;
        double radius = Earth.RADIUS + nav.altitude();
        return new Situation(met(), nav, mass, q, mach, air.pressure(), drag, Earth.MU / (radius * radius));
    }

    /** Advances one stage by a fourth-order Runge-Kutta step under gravity, thrust and drag. */
    private void fly(Stage stage, double mass, Aerodynamics aerodynamics) {
        double ambient = Atmosphere.at(Earth.altitude(stage.position)).pressure();
        Vec2 thrust = stage.thrustDirection.times(stage.thrust(ambient) / mass);
        Vec2 r = stage.position;
        Vec2 v = stage.velocity;
        double h = STEP;

        Vec2 a1 = acceleration(r, v, thrust, mass, aerodynamics);
        Vec2 r2 = r.plus(v.times(h / 2));
        Vec2 v2 = v.plus(a1.times(h / 2));
        Vec2 a2 = acceleration(r2, v2, thrust, mass, aerodynamics);
        Vec2 r3 = r.plus(v2.times(h / 2));
        Vec2 v3 = v.plus(a2.times(h / 2));
        Vec2 a3 = acceleration(r3, v3, thrust, mass, aerodynamics);
        Vec2 r4 = r.plus(v3.times(h));
        Vec2 v4 = v.plus(a3.times(h));
        Vec2 a4 = acceleration(r4, v4, thrust, mass, aerodynamics);

        stage.position = r.plus(v.plus(v2.times(2)).plus(v3.times(2)).plus(v4).times(h / 6));
        stage.velocity = v.plus(a1.plus(a2.times(2)).plus(a3.times(2)).plus(a4).times(h / 6));
        stage.sensedAcceleration = thrust.plus(drag(stage.position, stage.velocity, mass, aerodynamics)).norm();
    }

    private static Vec2 acceleration(Vec2 r, Vec2 v, Vec2 thrust, double mass, Aerodynamics aerodynamics) {
        return Earth.gravity(r).plus(thrust).plus(drag(r, v, mass, aerodynamics));
    }

    private static Vec2 drag(Vec2 r, Vec2 v, double mass, Aerodynamics aerodynamics) {
        double speed = v.norm();
        if (speed == 0) {
            return Vec2.ZERO;
        }
        Atmosphere.Conditions air = Atmosphere.at(Earth.altitude(r));
        double q = 0.5 * air.density() * speed * speed;
        return v.times(-q * aerodynamics.dragArea(speed / air.speedOfSound()) / (mass * speed));
    }

    /** Pinned to the ground, on the pad or on the deck. The accelerometer reads one g. */
    private static void hold(Stage stage) {
        stage.velocity = Vec2.ZERO;
        stage.sensedAcceleration = Earth.gravity(stage.position).norm();
    }

    private void touchdown() {
        double speed = booster.velocity.norm();
        booster.position = booster.position.unit().times(Earth.RADIUS);
        booster.velocity = Vec2.ZERO;
        boosterComputer.touchdown(speed);
    }

    private void checkEnd() {
        if (Double.isNaN(endTime)) {
            BoosterComputer.Mode mode = boosterComputer.mode();
            boolean boosterDone = mode == BoosterComputer.Mode.LANDED || mode == BoosterComputer.Mode.LOST;
            if (boosterDone && upperComputer.mode() == UpperStageComputer.Mode.DEPLOYED) {
                endTime = time + END_DELAY;
            } else if (met() > MAX_DURATION) {
                endTime = time;
            }
        } else if (!endAnnounced && time >= endTime) {
            endAnnounced = true;
            emit(FlightEvent.END_OF_MISSION, 0);
        } else if (endAnnounced && time >= endTime + 0.5) {
            finished = true;
        }
    }

    private static final class Degradation {
        final Engine engine;
        final double start;
        final double loss;
        final double ramp;

        Degradation(Engine engine, double start, double loss, double ramp) {
            this.engine = engine;
            this.start = start;
            this.loss = loss;
            this.ramp = ramp;
        }
    }
}
