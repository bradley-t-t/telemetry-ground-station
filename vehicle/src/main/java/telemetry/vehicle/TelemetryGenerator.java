package telemetry.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import telemetry.protocol.Channel;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.PacketEncoder;

/**
 * Samples the simulated vehicle into telemetry packets at each packet's dictionary rate,
 * adds sensor noise, and sends every event packet three times, 40 ms apart, so that one
 * lost datagram cannot hide an event from the ground.
 */
final class TelemetryGenerator {

    private static final double[] EVENT_REPEATS = {0, 0.04, 0.08};

    private final FlightSimulation flight;
    private final Dictionary dictionary;
    private final PacketSink sink;
    private final SplittableRandom noise;
    private final List<Schedule> schedules = new ArrayList<>();
    private final PacketEncoder eventEncoder;
    private final List<PendingEvent> pendingEvents = new ArrayList<>();
    /** Channels that have stuck, by index. NaN until the stuck value has been captured. */
    private final Map<Integer, Double> frozen = new HashMap<>();
    private boolean boosterSilent;

    TelemetryGenerator(FlightSimulation flight, Dictionary dictionary, PacketSink sink, long seed) {
        this.flight = flight;
        this.dictionary = dictionary;
        this.sink = sink;
        this.noise = new SplittableRandom(seed);
        this.eventEncoder = new PacketEncoder(dictionary.packet("EVENTS"));
        schedule("S1_NAV", true, this::stage1Navigation);
        schedule("S1_PROP", true, this::stage1Propulsion);
        schedule("S1_AVIONICS", true, this::stage1Avionics);
        schedule("S2_NAV", false, this::stage2Navigation);
        schedule("S2_PROP", false, this::stage2Propulsion);
        schedule("S2_AVIONICS", false, this::stage2Avionics);
    }

    void event(FlightEvent event, double eventClock, double arg, double now) {
        for (double delay : EVENT_REPEATS) {
            pendingEvents.add(new PendingEvent(now + delay, event, eventClock, arg));
        }
    }

    void freeze(String channel) {
        frozen.put(dictionary.channel(channel).index(), Double.NaN);
    }

    /** Stops stage 1 packets, as when the booster is destroyed. */
    void silenceBooster() {
        boosterSilent = true;
    }

    void sample(double time, double clock) {
        Iterator<PendingEvent> pending = pendingEvents.iterator();
        while (pending.hasNext()) {
            PendingEvent event = pending.next();
            if (event.sendTime <= time + 1e-9) {
                eventEncoder.set(channel("EVENT_CODE"), event.event.code())
                        .set(channel("EVENT_TIME"), event.eventClock)
                        .set(channel("EVENT_ARG"), event.arg);
                sink.send(eventEncoder.encode(clock));
                pending.remove();
            }
        }
        for (Schedule schedule : schedules) {
            if (time + 1e-9 < schedule.next) {
                continue;
            }
            schedule.next += schedule.period;
            if (schedule.booster && boosterSilent) {
                continue;
            }
            schedule.fill.accept(schedule.encoder);
            sink.send(schedule.encoder.encode(clock));
        }
    }

    private void stage1Navigation(PacketEncoder packet) {
        Situation s = flight.boosterSituation();
        Nav nav = s.nav();
        measure(packet, "S1_ALTITUDE", nav.altitude() / 1000, 0.0005);
        measure(packet, "S1_DOWNRANGE", nav.downrange() / 1000, 0.0005);
        measure(packet, "S1_VELOCITY", nav.speed(), 0.05);
        measure(packet, "S1_VERT_VEL", nav.verticalSpeed(), 0.05);
        measure(packet, "S1_HORIZ_VEL", nav.horizontalSpeed(), 0.05);
        measure(packet, "S1_FPA", nav.flightPathAngle(), 0.01);
        measure(packet, "S1_ACCEL", flight.booster().sensedAcceleration / Earth.G0, 0.015);
        measure(packet, "S1_DYN_PRESSURE", s.dynamicPressure() / 1000, 0.02);
        measure(packet, "S1_MACH", s.mach(), 0.002);
    }

    private void stage1Propulsion(PacketEncoder packet) {
        Stage stage = flight.booster();
        double throttle = stage.anyEngineCommanded() ? stage.throttle * 100 : 0;
        put(packet, "S1_THROTTLE", throttle);
        put(packet, "S1_ENGINES", stage.enginesInMainstage());
        for (Engine engine : stage.engines) {
            put(packet, "S1_ENG" + engine.number + "_MAIN", engine.mainstage() ? 1 : 0);
            double pc = engine.chamberPressure(stage.throttle);
            measure(packet, "S1_ENG" + engine.number + "_PC", pc, pressureNoise(pc));
        }
        measure(packet, "S1_LOX_TANK_P", stage.pressurization.lox(), 0.012);
        measure(packet, "S1_RP1_TANK_P", stage.pressurization.rp1(), 0.012);
        measure(packet, "S1_COPV_P", stage.pressurization.bottle(), 0.4);
        put(packet, "S1_PROPELLANT", stage.propellantFraction() * 100);
    }

    private void stage1Avionics(PacketEncoder packet) {
        Stage stage = flight.booster();
        BoosterComputer computer = flight.boosterComputer();
        put(packet, "S1_MODE", computer.mode().ordinal());
        measure(packet, "S1_BUS_VOLTAGE", stage.busVoltage, 0.04);
        measure(packet, "S1_AVIONICS_TEMP", stage.avionicsTemperature, 0.12);
        measure(packet, "S1_N2_P", stage.nitrogenPressure, 0.3);
        put(packet, "S1_GRID_FINS", computer.gridFinsDeployed() ? 1 : 0);
        put(packet, "S1_LEGS", computer.legsDeployed() ? 1 : 0);
    }

    private void stage2Navigation(PacketEncoder packet) {
        Situation s = flight.upperSituation();
        Nav nav = s.nav();
        measure(packet, "S2_ALTITUDE", nav.altitude() / 1000, 0.0005);
        measure(packet, "S2_DOWNRANGE", nav.downrange() / 1000, 0.0005);
        measure(packet, "S2_VELOCITY", nav.speed(), 0.05);
        measure(packet, "S2_VERT_VEL", nav.verticalSpeed(), 0.05);
        measure(packet, "S2_HORIZ_VEL", nav.horizontalSpeed(), 0.05);
        measure(packet, "S2_FPA", nav.flightPathAngle(), 0.01);
        measure(packet, "S2_ACCEL", flight.upper().sensedAcceleration / Earth.G0, 0.015);
        put(packet, "S2_APOGEE", nav.apogee() / 1000);
        put(packet, "S2_PERIGEE", nav.perigee() / 1000);
    }

    private void stage2Propulsion(PacketEncoder packet) {
        Stage stage = flight.upper();
        Engine engine = stage.engine(1);
        put(packet, "S2_THROTTLE", stage.anyEngineCommanded() ? stage.throttle * 100 : 0);
        put(packet, "S2_ENG_MAIN", engine.mainstage() ? 1 : 0);
        double pc = engine.chamberPressure(stage.throttle);
        measure(packet, "S2_ENG_PC", pc, pressureNoise(pc));
        measure(packet, "S2_LOX_TANK_P", stage.pressurization.lox(), 0.012);
        measure(packet, "S2_RP1_TANK_P", stage.pressurization.rp1(), 0.012);
        measure(packet, "S2_COPV_P", stage.pressurization.bottle(), 0.4);
        measure(packet, "S2_NOZZLE_TEMP", stage.nozzleTemperature, 1.5);
        put(packet, "S2_PROPELLANT", stage.propellantFraction() * 100);
    }

    private void stage2Avionics(PacketEncoder packet) {
        Stage stage = flight.upper();
        put(packet, "S2_MODE", flight.upperComputer().mode().ordinal());
        measure(packet, "S2_BUS_VOLTAGE", stage.busVoltage, 0.04);
        measure(packet, "S2_AVIONICS_TEMP", stage.avionicsTemperature, 0.12);
        put(packet, "S2_FAIRING", flight.upperComputer().fairingAttached() ? 1 : 0);
    }

    /** Chamber pressure transducers are quiet at rest and noisier with combustion behind them. */
    private static double pressureNoise(double chamberPressure) {
        return 0.04 + 0.32 * Math.min(1, chamberPressure / Engine.NOMINAL_CHAMBER_PRESSURE);
    }

    private void schedule(String packet, boolean booster, Consumer<PacketEncoder> fill) {
        PacketEncoder encoder = new PacketEncoder(dictionary.packet(packet));
        schedules.add(new Schedule(encoder, 1 / encoder.definition().rateHz(), booster, fill));
    }

    private void put(PacketEncoder packet, String name, double value) {
        packet.set(channel(name), value);
    }

    private void measure(PacketEncoder packet, String name, double truth, double sigma) {
        Channel channel = channel(name);
        Double held = frozen.get(channel.index());
        double value;
        if (held == null) {
            value = truth + sigma * noise.nextGaussian();
        } else if (held.isNaN()) {
            value = truth + sigma * noise.nextGaussian();
            frozen.put(channel.index(), value);
        } else {
            value = held;
        }
        packet.set(channel, value);
    }

    private Channel channel(String name) {
        return dictionary.channel(name);
    }

    private static final class Schedule {
        final PacketEncoder encoder;
        final double period;
        final boolean booster;
        final Consumer<PacketEncoder> fill;
        double next;

        Schedule(PacketEncoder encoder, double period, boolean booster, Consumer<PacketEncoder> fill) {
            this.encoder = encoder;
            this.period = period;
            this.booster = booster;
            this.fill = fill;
        }
    }

    private record PendingEvent(double sendTime, FlightEvent event, double eventClock, double arg) {
    }
}
