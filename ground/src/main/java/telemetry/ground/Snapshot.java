package telemetry.ground;

/** One published view of the ground station, shared by every dashboard connection. */
public record Snapshot(long version, int flight, String json, History history) {
}
