package telemetry.ground;

import java.util.HashMap;
import java.util.Map;
import telemetry.protocol.Channel;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Severity;

/**
 * Watches how fast a channel is changing. The rate is the least-squares slope over the last
 * second, which rides through sensor noise, and it has to stay over the dictionary's rate
 * limit for half a second before it raises a caution. A leak shows up here long before the
 * pressure it drains reaches a limit.
 */
final class RateMonitor implements Monitor {

    private static final double WINDOW = 1.0;
    private static final double RAISE_AFTER = 0.5;
    private static final double CLEAR_AFTER = 1.0;
    private static final int MIN_SAMPLES = 5;

    private final Flight flight;
    private final Map<Integer, Track> tracks = new HashMap<>();

    RateMonitor(Flight flight) {
        this.flight = flight;
    }

    @Override
    public void onPacket(DecodedPacket packet) {
        double[] current = flight.table.values();
        for (Channel channel : packet.definition().channels()) {
            if (Double.isNaN(channel.maxRate())) {
                continue;
            }
            Track track = tracks.computeIfAbsent(channel.index(), i -> new Track());
            double clock = packet.clock();
            if (!channel.checksEnabled(current)) {
                track.size = 0;
                continue;
            }
            track.add(clock, packet.value(channel));
            double slope = track.slope(clock);
            if (Double.isNaN(slope)) {
                continue;
            }
            evaluate(channel, track, slope, clock);
        }
    }

    boolean active(int channel) {
        Track track = tracks.get(channel);
        return track != null && track.active;
    }

    private void evaluate(Channel channel, Track track, double slope, double clock) {
        String key = "RATE:" + channel.name();
        if (Math.abs(slope) > channel.maxRate()) {
            track.clearSince = Double.NaN;
            if (Double.isNaN(track.exceededSince)) {
                track.exceededSince = clock;
            }
            if (!track.active && clock - track.exceededSince >= RAISE_AFTER) {
                track.active = true;
                String direction = slope < 0 ? "falling" : "rising";
                flight.alarms.raise(key, Severity.CAUTION, channel.name(),
                        channel.name() + " " + direction + " at " + Format.value(Math.abs(slope), channel.units())
                                + "/s, faster than the normal " + Format.value(channel.maxRate(), channel.units())
                                + "/s", clock);
            }
        } else {
            track.exceededSince = Double.NaN;
            if (track.active) {
                if (Double.isNaN(track.clearSince)) {
                    track.clearSince = clock;
                } else if (clock - track.clearSince >= CLEAR_AFTER) {
                    track.active = false;
                    flight.alarms.clear(key, clock);
                }
            }
        }
    }

    /** A sliding window of samples for one channel. */
    private static final class Track {
        final double[] times = new double[256];
        final double[] values = new double[256];
        int start;
        int size;
        boolean active;
        double exceededSince = Double.NaN;
        double clearSince = Double.NaN;

        void add(double time, double value) {
            if (size == times.length) {
                start = (start + 1) % times.length;
                size--;
            }
            int slot = (start + size) % times.length;
            times[slot] = time;
            values[slot] = value;
            size++;
            while (size > 0 && times[start] < time - WINDOW) {
                start = (start + 1) % times.length;
                size--;
            }
        }

        double slope(double now) {
            if (size < MIN_SAMPLES || now - times[start] < WINDOW * 0.6) {
                return Double.NaN;
            }
            double meanT = 0;
            double meanV = 0;
            for (int i = 0; i < size; i++) {
                int slot = (start + i) % times.length;
                meanT += times[slot];
                meanV += values[slot];
            }
            meanT /= size;
            meanV /= size;
            double covariance = 0;
            double variance = 0;
            for (int i = 0; i < size; i++) {
                int slot = (start + i) % times.length;
                double dt = times[slot] - meanT;
                covariance += dt * (values[slot] - meanV);
                variance += dt * dt;
            }
            return variance == 0 ? Double.NaN : covariance / variance;
        }
    }
}
