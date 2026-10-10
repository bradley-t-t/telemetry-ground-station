package telemetry.ground;

import telemetry.protocol.Channel;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;

/**
 * Events the ground recognises in the telemetry rather than being told about: the moment
 * of maximum dynamic pressure, and a stable orbit after second engine cutoff.
 */
final class DerivedEvents implements Monitor {

    private static final double MAX_Q_MINIMUM = 5;
    private static final double MAX_Q_FALL = 0.9;
    private static final double ORBIT_PERIGEE = 150;
    private static final double ORBIT_CONFIRM = 1.0;
    private static final int ASCENT = 1;
    private static final int COASTING_IN_ORBIT = 2;

    private final Flight flight;
    private final int stage1Nav;
    private final int stage2Nav;
    private final Channel dynamicPressure;
    private final Channel stage1Mode;
    private final Channel stage2Mode;
    private final Channel perigee;
    private double peakPressure;
    private double peakClock;
    private boolean maxQRecorded;
    private double orbitSince = Double.NaN;
    private boolean orbitRecorded;

    DerivedEvents(Flight flight) {
        this.flight = flight;
        Dictionary dictionary = flight.dictionary;
        stage1Nav = dictionary.packet("S1_NAV").apid();
        stage2Nav = dictionary.packet("S2_NAV").apid();
        dynamicPressure = dictionary.channel("S1_DYN_PRESSURE");
        stage1Mode = dictionary.channel("S1_MODE");
        stage2Mode = dictionary.channel("S2_MODE");
        perigee = dictionary.channel("S2_PERIGEE");
    }

    @Override
    public void onPacket(DecodedPacket packet) {
        if (packet.apid() == stage1Nav && !maxQRecorded && flight.table.value(stage1Mode) == ASCENT) {
            double q = packet.value(dynamicPressure);
            if (q > peakPressure) {
                peakPressure = q;
                peakClock = packet.clock();
            } else if (peakPressure > MAX_Q_MINIMUM && q < MAX_Q_FALL * peakPressure) {
                maxQRecorded = true;
                flight.events.record(FlightEvent.MAX_Q, peakClock, peakPressure, true);
            }
        }
        if (packet.apid() == stage2Nav && !orbitRecorded) {
            double p = packet.value(perigee);
            if (flight.table.value(stage2Mode) >= COASTING_IN_ORBIT && p > ORBIT_PERIGEE) {
                if (Double.isNaN(orbitSince)) {
                    orbitSince = packet.clock();
                } else if (packet.clock() - orbitSince >= ORBIT_CONFIRM) {
                    orbitRecorded = true;
                    flight.events.record(FlightEvent.ORBIT, packet.clock(), p, true);
                }
            } else {
                orbitSince = Double.NaN;
            }
        }
    }
}
