package telemetry.ground;

import java.util.Arrays;
import telemetry.protocol.Channel;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.Severity;

/**
 * Compares the stage 1 engines with each other. Engines on one stage share a throttle
 * command, so the median chamber pressure of the running engines is a live model of what
 * each one should read. An engine 8% below that is degraded even when it is well inside
 * its own limits, and one below half has lost thrust. An engine that loses pressure before
 * it leaves mainstage was not shut down on purpose.
 */
final class EngineHealthMonitor implements Monitor {

    private static final int ENGINES = 9;
    private static final double DEGRADED = 0.92;
    private static final double RECOVERED = 0.94;
    private static final double LOST = 0.5;
    private static final int DEGRADED_SAMPLES = 12;
    private static final int LOST_SAMPLES = 2;
    private static final double MIN_MEDIAN = 20;

    private final Flight flight;
    private final int apid;
    private final Channel[] mainstage = new Channel[ENGINES + 1];
    private final Channel[] pressure = new Channel[ENGINES + 1];
    private final boolean[] wasMainstage = new boolean[ENGINES + 1];
    private final boolean[] pressureLost = new boolean[ENGINES + 1];
    private final boolean[] failed = new boolean[ENGINES + 1];
    private final boolean[] degraded = new boolean[ENGINES + 1];
    private final int[] lostStreak = new int[ENGINES + 1];
    private final int[] degradedStreak = new int[ENGINES + 1];

    EngineHealthMonitor(Flight flight) {
        this.flight = flight;
        Dictionary dictionary = flight.dictionary;
        this.apid = dictionary.packet("S1_PROP").apid();
        for (int n = 1; n <= ENGINES; n++) {
            mainstage[n] = dictionary.channel("S1_ENG" + n + "_MAIN");
            pressure[n] = dictionary.channel("S1_ENG" + n + "_PC");
        }
    }

    @Override
    public void onPacket(DecodedPacket packet) {
        if (packet.apid() != apid) {
            return;
        }
        double clock = packet.clock();
        double[] running = new double[ENGINES];
        int count = 0;
        for (int n = 1; n <= ENGINES; n++) {
            boolean main = packet.value(mainstage[n]) != 0;
            if (wasMainstage[n] && !main && pressureLost[n] && !failed[n]) {
                failed[n] = true;
                flight.alarms.raise(key(n), Severity.WARNING, pressure[n].name(),
                        "Engine " + n + " lost thrust without a command; the flight computer isolated it at "
                                + Format.met(flight.clock.metAt(clock)),
                        clock);
            }
            wasMainstage[n] = main;
            if (main) {
                running[count++] = packet.value(pressure[n]);
            }
        }
        if (count < 3) {
            return;
        }
        Arrays.sort(running, 0, count);
        double median = count % 2 == 1 ? running[count / 2] : (running[count / 2 - 1] + running[count / 2]) / 2;
        if (median < MIN_MEDIAN) {
            return;
        }
        for (int n = 1; n <= ENGINES; n++) {
            if (packet.value(mainstage[n]) == 0) {
                continue;
            }
            double pc = packet.value(pressure[n]);
            double ratio = pc / median;
            checkLoss(n, pc, median, ratio, clock);
            checkDegradation(n, ratio, clock);
        }
    }

    private void checkLoss(int n, double pc, double median, double ratio, double clock) {
        if (ratio >= LOST) {
            lostStreak[n] = 0;
            return;
        }
        if (++lostStreak[n] >= LOST_SAMPLES && !pressureLost[n]) {
            pressureLost[n] = true;
            flight.alarms.raise(key(n), Severity.WARNING, pressure[n].name(),
                    "Engine " + n + " lost chamber pressure: " + Format.value(pc, "bar") + " against a median of "
                            + Format.value(median, "bar") + " across the running engines", clock);
        }
    }

    private void checkDegradation(int n, double ratio, double clock) {
        String key = key(n) + ":DEGRADED";
        if (ratio < DEGRADED && ratio >= LOST) {
            if (++degradedStreak[n] >= DEGRADED_SAMPLES && !degraded[n]) {
                degraded[n] = true;
                flight.alarms.raise(key, Severity.CAUTION, pressure[n].name(),
                        "Engine " + n + " chamber pressure is " + Format.number((1 - ratio) * 100)
                                + "% below the median of the running engines", clock);
            }
        } else {
            degradedStreak[n] = 0;
            if (degraded[n] && ratio >= RECOVERED) {
                degraded[n] = false;
                flight.alarms.clear(key, clock);
            }
        }
    }

    private static String key(int engine) {
        return "ENGINE:" + engine;
    }
}
