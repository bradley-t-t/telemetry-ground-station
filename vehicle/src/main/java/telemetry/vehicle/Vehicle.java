package telemetry.vehicle;

import java.util.SplittableRandom;

/**
 * Mass and propulsion figures for a two-stage kerosene and liquid oxygen launcher of the
 * Falcon 9 class, taken from published numbers and rounded.
 */
final class Vehicle {

    static final double BOOSTER_DRY_MASS = 25_600;
    static final double BOOSTER_PROPELLANT = 395_700;
    static final int BOOSTER_ENGINES = 9;
    static final double BOOSTER_ENGINE_SEA_LEVEL_THRUST = 845_000;
    static final double BOOSTER_ENGINE_VACUUM_THRUST = 914_000;
    static final double BOOSTER_ENGINE_ISP = 311;

    static final double UPPER_DRY_MASS = 4_000;
    static final double UPPER_PROPELLANT = 107_500;
    static final double UPPER_ENGINE_THRUST = 981_000;
    static final double UPPER_ENGINE_ISP = 348;

    static final double PAYLOAD = 13_000;
    static final double FAIRING = 1_900;

    /** Spread of chamber pressure between engines of the same type, as a fraction. */
    private static final double ENGINE_SPREAD = 0.008;

    private Vehicle() {
    }

    static Stage booster(SplittableRandom random) {
        Engine[] engines = new Engine[BOOSTER_ENGINES];
        for (int i = 0; i < engines.length; i++) {
            engines[i] = new Engine(i + 1, BOOSTER_ENGINE_SEA_LEVEL_THRUST, BOOSTER_ENGINE_VACUUM_THRUST,
                    BOOSTER_ENGINE_ISP, spread(random));
        }
        return new Stage("Stage 1", engines, BOOSTER_DRY_MASS, BOOSTER_PROPELLANT, 0,
                new Pressurization(310, 190, 3.6, 3.4), 300);
    }

    static Stage upper(SplittableRandom random) {
        Engine[] engines = {
            new Engine(1, UPPER_ENGINE_THRUST, UPPER_ENGINE_THRUST, UPPER_ENGINE_ISP, spread(random))
        };
        return new Stage("Stage 2", engines, UPPER_DRY_MASS, UPPER_PROPELLANT, PAYLOAD + FAIRING,
                new Pressurization(300, 175, 3.5, 3.3), 0);
    }

    private static double spread(SplittableRandom random) {
        return (random.nextDouble() * 2 - 1) * ENGINE_SPREAD;
    }
}
