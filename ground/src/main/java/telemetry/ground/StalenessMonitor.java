package telemetry.ground;

import java.util.List;
import telemetry.protocol.PacketDefinition;
import telemetry.protocol.Severity;
import telemetry.protocol.SpacePacket;

/**
 * Notices silence. A packet type is stale once it is three periods late, which greys its
 * channels out on the display; after two seconds it raises a caution, and when every
 * packet type has gone quiet for three seconds that becomes loss of signal. None of this
 * applies once the vehicle has announced the end of the mission.
 */
final class StalenessMonitor {

    private static final double STALE_PERIODS = 3;
    private static final double MIN_STALE_SECONDS = 0.25;
    private static final double ALARM_SECONDS = 2.0;
    private static final double LOS_SECONDS = 3.0;
    private static final String LOS = "LOS";

    private final Flight flight;
    private final List<PacketDefinition> periodic;
    private final boolean[] stale = new boolean[SpacePacket.MAX_APID + 1];
    private boolean lossOfSignal;

    StalenessMonitor(Flight flight) {
        this.flight = flight;
        this.periodic = flight.dictionary.packets().stream().filter(PacketDefinition::isPeriodic).toList();
    }

    void tick(long now) {
        double clock = flight.clock.latest();
        boolean anyReceived = false;
        boolean anyFresh = false;
        for (PacketDefinition packet : periodic) {
            long last = flight.table.lastReceived(packet.apid());
            if (last == ChannelTable.NEVER) {
                continue;
            }
            anyReceived = true;
            double age = (now - last) / 1e9;
            stale[packet.apid()] = age > Math.max(STALE_PERIODS / packet.rateHz(), MIN_STALE_SECONDS);
            if (!stale[packet.apid()]) {
                anyFresh = true;
            }
        }
        double silence = flight.lastFrame == ChannelTable.NEVER ? 0 : (now - flight.lastFrame) / 1e9;
        boolean los = anyReceived && !anyFresh && silence > LOS_SECONDS && !flight.complete;

        if (los && !lossOfSignal) {
            flight.alarms.raise(LOS, Severity.WARNING, null,
                    "Loss of signal: no telemetry since " + Format.met(flight.clock.met()), clock);
        } else if (!los && lossOfSignal) {
            flight.alarms.clear(LOS, clock);
        }
        lossOfSignal = los;

        for (PacketDefinition packet : periodic) {
            long last = flight.table.lastReceived(packet.apid());
            String key = "STALE:" + packet.name();
            boolean quiet = last != ChannelTable.NEVER && (now - last) / 1e9 > ALARM_SECONDS;
            boolean alarm = quiet && !los && !flight.complete && !flight.silenced.contains(packet.apid());
            if (alarm && !flight.alarms.isActive(key)) {
                flight.alarms.raise(key, Severity.CAUTION, null,
                        "No " + packet.name() + " packets since " + Format.met(flight.clock.met()), clock);
            } else if (!alarm) {
                flight.alarms.clear(key, clock);
            }
        }
    }

    boolean stale(int apid) {
        return stale[apid];
    }

    boolean lossOfSignal() {
        return lossOfSignal;
    }
}
