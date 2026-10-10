package telemetry.vehicle;

import java.util.List;

/** Ready-made flights, each exercising a different part of the ground station. */
public enum Scenario {

    NOMINAL("nominal", "Nominal",
            "A clean flight to a 200 km orbit, with the booster landing on a ship downrange.",
            List.of(), LinkProfile.CLEAN),
    ENGINE_OUT("engine-out", "Engine Out",
            "Engine 5 quits at T+80 s. The flight computer shuts it down and the other eight burn longer to make up for it.",
            List.of(new Fault.EngineFailure(5, 80)), LinkProfile.CLEAN),
    ENGINE_DEGRADED("engine-degraded", "Engine Degraded",
            "Engine 3 loses 12% of its chamber pressure from T+60 s. Every reading stays inside its limits.",
            List.of(new Fault.EngineDegradation(3, 60, 0.12, 8)), LinkProfile.CLEAN),
    HELIUM_LEAK("helium-leak", "Helium Leak",
            "The stage 1 helium bottle starts leaking at T+45 s. Tank pressure holds until the bottle runs low.",
            List.of(new Fault.HeliumLeak(1, 45, 11)), LinkProfile.CLEAN),
    STUCK_SENSOR("stuck-sensor", "Stuck Sensor",
            "The stage 2 LOX tank pressure transducer freezes at T+300 s and keeps reporting a normal value.",
            List.of(new Fault.StuckSensor("S2_LOX_TANK_P", 300)), LinkProfile.CLEAN),
    NOISY_LINK("noisy-link", "Noisy Link",
            "A nominal flight over a link that loses 3% of packets and corrupts, duplicates and reorders others.",
            List.of(), new LinkProfile(0.03, 0.005, 0.005, 0.01));

    private final String id;
    private final String label;
    private final String description;
    private final List<Fault> faults;
    private final LinkProfile link;

    Scenario(String id, String label, String description, List<Fault> faults, LinkProfile link) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.faults = faults;
        this.link = link;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public List<Fault> faults() {
        return faults;
    }

    public LinkProfile link() {
        return link;
    }

    public static Scenario byId(String id) {
        for (Scenario scenario : values()) {
            if (scenario.id.equals(id)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario '" + id + "'");
    }
}
