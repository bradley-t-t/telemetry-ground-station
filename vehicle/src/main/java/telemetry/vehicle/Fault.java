package telemetry.vehicle;

import java.util.Locale;

/** A failure injected into a flight. Each one starts at a time measured from liftoff. */
public sealed interface Fault {

    double time();

    String describe();

    /** An engine stops producing thrust without being told to. */
    record EngineFailure(int engine, double time) implements Fault {
        public String describe() {
            return "engine " + engine + " fails at T+" + seconds(time);
        }
    }

    /** An engine's performance erodes by {@code loss} over {@code rampSeconds}. */
    record EngineDegradation(int engine, double time, double loss, double rampSeconds) implements Fault {
        public String describe() {
            return String.format(Locale.ROOT, "engine %d loses %.0f%% from T+%s", engine, loss * 100, seconds(time));
        }
    }

    /** A helium bottle on {@code stage} (1 or 2) starts leaking. */
    record HeliumLeak(int stage, double time, double barPerSecond) implements Fault {
        public String describe() {
            return "stage " + stage + " helium leak at T+" + seconds(time);
        }
    }

    /** A sensor freezes on its last reading. */
    record StuckSensor(String channel, double time) implements Fault {
        public String describe() {
            return channel + " sticks at T+" + seconds(time);
        }
    }

    /**
     * Parses {@code kind:target@seconds}, for example {@code engine-out:5@80},
     * {@code engine-degraded:3@60}, {@code helium-leak:1@45} or
     * {@code stuck-sensor:S2_LOX_TANK_P@300}.
     */
    static Fault parse(String spec) {
        int colon = spec.indexOf(':');
        int at = spec.lastIndexOf('@');
        if (colon < 0 || at < colon) {
            throw new IllegalArgumentException("Fault '" + spec + "' should look like kind:target@seconds");
        }
        String kind = spec.substring(0, colon);
        String target = spec.substring(colon + 1, at);
        double time;
        try {
            time = Double.parseDouble(spec.substring(at + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Fault '" + spec + "' has no valid time after @");
        }
        return switch (kind) {
            case "engine-out" -> new EngineFailure(engineNumber(target), time);
            case "engine-degraded" -> new EngineDegradation(engineNumber(target), time, 0.12, 8);
            case "helium-leak" -> new HeliumLeak(stageNumber(target), time, 11);
            case "stuck-sensor" -> new StuckSensor(target, time);
            default -> throw new IllegalArgumentException("Unknown fault kind '" + kind
                    + "'; use engine-out, engine-degraded, helium-leak or stuck-sensor");
        };
    }

    private static int engineNumber(String target) {
        int engine = Integer.parseInt(target);
        if (engine < 1 || engine > Vehicle.BOOSTER_ENGINES) {
            throw new IllegalArgumentException("Engine must be 1 to " + Vehicle.BOOSTER_ENGINES);
        }
        return engine;
    }

    private static int stageNumber(String target) {
        int stage = Integer.parseInt(target);
        if (stage != 1 && stage != 2) {
            throw new IllegalArgumentException("Stage must be 1 or 2");
        }
        return stage;
    }

    private static String seconds(double time) {
        return time == Math.rint(time) ? Long.toString((long) time) : Double.toString(time);
    }
}
