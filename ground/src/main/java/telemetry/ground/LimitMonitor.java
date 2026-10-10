package telemetry.ground;

import java.util.Arrays;
import telemetry.protocol.Channel;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Limits;
import telemetry.protocol.Severity;

/**
 * Checks each value against its dictionary limits. A new state has to persist for
 * {@value #RAISE_AFTER} samples before it raises an alarm and {@value #CLEAR_AFTER} before
 * it clears one, so one noisy sample on a boundary does not make the alarm flicker.
 */
final class LimitMonitor implements Monitor {

    static final int RAISE_AFTER = 3;
    static final int CLEAR_AFTER = 5;

    private final Flight flight;
    private final Severity[] state;
    private final Severity[] candidate;
    private final int[] streak;

    LimitMonitor(Flight flight) {
        this.flight = flight;
        int size = flight.dictionary.channels().size();
        state = new Severity[size];
        candidate = new Severity[size];
        streak = new int[size];
        Arrays.fill(state, Severity.NOMINAL);
        Arrays.fill(candidate, Severity.NOMINAL);
    }

    @Override
    public void onPacket(DecodedPacket packet) {
        double[] current = flight.table.values();
        for (Channel channel : packet.definition().channels()) {
            Limits limits = channel.limits();
            if (!limits.isDefined()) {
                continue;
            }
            double value = packet.value(channel);
            Severity observed = channel.checksEnabled(current) ? limits.classify(value) : Severity.NOMINAL;
            int i = channel.index();
            if (observed == state[i]) {
                candidate[i] = observed;
                streak[i] = 0;
                continue;
            }
            if (observed != candidate[i]) {
                candidate[i] = observed;
                streak[i] = 0;
            }
            streak[i]++;
            int needed = observed.compareTo(state[i]) > 0 ? RAISE_AFTER : CLEAR_AFTER;
            if (streak[i] >= needed) {
                state[i] = observed;
                streak[i] = 0;
                publish(channel, value, observed, packet.clock());
            }
        }
    }

    Severity severity(int channel) {
        return state[channel];
    }

    private void publish(Channel channel, double value, Severity severity, double clock) {
        String key = "LIMIT:" + channel.name();
        if (severity == Severity.NOMINAL) {
            flight.alarms.clear(key, clock);
        } else {
            flight.alarms.raise(key, severity, channel.name(), describe(channel, value), clock);
        }
    }

    static String describe(Channel channel, double value) {
        Limits limits = channel.limits();
        String bound;
        double limit;
        if (value < limits.redLow()) {
            bound = "below the red low limit of ";
            limit = limits.redLow();
        } else if (value > limits.redHigh()) {
            bound = "above the red high limit of ";
            limit = limits.redHigh();
        } else if (value < limits.yellowLow()) {
            bound = "below the yellow low limit of ";
            limit = limits.yellowLow();
        } else {
            bound = "above the yellow high limit of ";
            limit = limits.yellowHigh();
        }
        String shown = Format.value(value, channel.units());
        String threshold = Format.value(limit, channel.units());
        if (shown.equals(threshold)) {
            shown = Format.precise(value, channel.units());
            threshold = Format.precise(limit, channel.units());
        }
        return channel.name() + " " + shown + ", " + bound + threshold;
    }
}
