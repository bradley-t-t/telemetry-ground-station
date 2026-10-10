package telemetry.protocol;

/**
 * One measurement in the telemetry dictionary.
 *
 * @param index       position in the dictionary's channel list, unique across all packets
 * @param position    position within its packet's channel list
 * @param offset      byte offset of the value from the start of the packet
 * @param maxRate     largest normal rate of change in units per second, or NaN
 * @param whenChannel index of the channel that enables this channel's checks, or -1
 * @param whenValue   value {@code whenChannel} must equal, or NaN for any non-zero value
 */
public record Channel(
        int index,
        int position,
        int offset,
        int apid,
        String name,
        ChannelType type,
        ChannelClass kind,
        String units,
        Limits limits,
        double maxRate,
        int whenChannel,
        double whenValue,
        String description) {

    public boolean hasCondition() {
        return whenChannel >= 0;
    }

    /** Whether this channel's checks apply, given the latest value of every channel. */
    public boolean checksEnabled(double[] current) {
        if (whenChannel < 0) {
            return true;
        }
        double gate = current[whenChannel];
        if (Double.isNaN(gate)) {
            return false;
        }
        return Double.isNaN(whenValue) ? gate != 0 : gate == whenValue;
    }
}
