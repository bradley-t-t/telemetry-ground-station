package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import telemetry.protocol.Dictionary;
import telemetry.protocol.PacketEncoder;
import telemetry.protocol.Severity;

class LimitMonitorTest {

    private final Dictionary dictionary = Dictionary.standard();
    private final TelemetryProcessor processor = new TelemetryProcessor(dictionary);
    private final PacketEncoder encoder = new PacketEncoder(dictionary.packet("S1_PROP"));
    private double clock = 1_000;

    LimitMonitorTest() {
        encoder.set(dictionary.channel("S1_LOX_TANK_P"), 3.6)
                .set(dictionary.channel("S1_RP1_TANK_P"), 3.4)
                .set(dictionary.channel("S1_COPV_P"), 280);
    }

    @Test
    void oneBadSampleDoesNotRaiseAnAlarm() {
        send(1, 97);
        send(1, 10);
        send(1, 97);
        assertTrue(alarms().isEmpty());
    }

    @Test
    void aPersistentViolationRaisesAndThenReturns() {
        send(1, 97);
        for (int i = 0; i < LimitMonitor.RAISE_AFTER; i++) {
            send(1, 10);
        }
        Alarm alarm = alarms().getFirst();
        assertEquals("LIMIT:S1_ENG4_PC", alarm.key());
        assertEquals(Severity.WARNING, alarm.severity());
        assertEquals(Alarm.State.ACTIVE, alarm.state());

        for (int i = 0; i < LimitMonitor.CLEAR_AFTER; i++) {
            send(1, 97);
        }
        assertEquals(Alarm.State.RETURNED, alarms().getFirst().state());
        processor.acknowledge(alarm.id());
        assertTrue(alarms().isEmpty());
    }

    @Test
    void limitsDoNotApplyWhileTheEngineIsOff() {
        for (int i = 0; i < 20; i++) {
            send(0, 0);
        }
        assertTrue(alarms().isEmpty());
    }

    @Test
    void anAcknowledgedAlarmLeavesWhenItClears() {
        for (int i = 0; i < LimitMonitor.RAISE_AFTER; i++) {
            send(1, 104);
        }
        Alarm alarm = alarms().getFirst();
        assertEquals(Severity.CAUTION, alarm.severity());
        processor.acknowledge(alarm.id());
        assertFalse(alarms().isEmpty());
        for (int i = 0; i < LimitMonitor.CLEAR_AFTER; i++) {
            send(1, 97);
        }
        assertTrue(alarms().isEmpty());
    }

    private void send(int mainstage, double chamberPressure) {
        encoder.set(dictionary.channel("S1_ENG4_MAIN"), mainstage)
                .set(dictionary.channel("S1_ENG4_PC"), chamberPressure);
        clock += 0.04;
        processor.process(new RawFrame((long) (clock * 1e9), encoder.encode(clock)));
    }

    private List<Alarm> alarms() {
        return processor.flight().alarms.current();
    }
}
