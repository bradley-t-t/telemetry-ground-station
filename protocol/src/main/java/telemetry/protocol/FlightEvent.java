package telemetry.protocol;

/**
 * Discrete flight events. The vehicle reports most of them in the EVENTS packet; the ones
 * marked as ground-derived are recognised by the ground station from the telemetry itself.
 */
public enum FlightEvent {
    COUNTDOWN(1, "Terminal Count", false),
    IGNITION(2, "Engine Ignition", false),
    LIFTOFF(3, "Liftoff", false),
    THROTTLE_DOWN(4, "Throttle Down", false),
    THROTTLE_UP(5, "Throttle Up", false),
    MAX_Q(6, "Max-Q", true),
    MECO(7, "Main Engine Cutoff", false),
    STAGE_SEPARATION(8, "Stage Separation", false),
    S2_IGNITION(9, "Second Engine Start", false),
    FAIRING_SEPARATION(10, "Fairing Separation", false),
    GRID_FINS(11, "Grid Fin Deploy", false),
    ENTRY_BURN_START(12, "Entry Burn Start", false),
    ENTRY_BURN_END(13, "Entry Burn End", false),
    LANDING_BURN_START(14, "Landing Burn Start", false),
    LANDING_LEGS(15, "Landing Leg Deploy", false),
    TOUCHDOWN(16, "Touchdown", false),
    BOOSTER_LOST(17, "Booster Lost", false),
    SECO(18, "Second Engine Cutoff", false),
    ORBIT(19, "Orbit Confirmed", true),
    PAYLOAD_SEPARATION(20, "Payload Separation", false),
    ENGINE_SHUTDOWN(21, "Engine Shutdown", false),
    END_OF_MISSION(22, "End of Mission", false);

    private static final FlightEvent[] BY_CODE = new FlightEvent[64];

    static {
        for (FlightEvent event : values()) {
            BY_CODE[event.code] = event;
        }
    }

    private final int code;
    private final String label;
    private final boolean groundDerived;

    FlightEvent(int code, String label, boolean groundDerived) {
        this.code = code;
        this.label = label;
        this.groundDerived = groundDerived;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean groundDerived() {
        return groundDerived;
    }

    /** The event with this code, or null for a code this build does not know. */
    public static FlightEvent ofCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
    }
}
