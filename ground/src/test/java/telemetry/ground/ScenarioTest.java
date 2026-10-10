package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.Severity;
import telemetry.vehicle.Scenario;

/** Every scenario flown end to end into the processor, checking what the ground concludes. */
class ScenarioTest {

    @Test
    void aNominalFlightRaisesNothingAndSeesEveryMilestone() {
        Flight flight = FlightHarness.fly(Scenario.NOMINAL, 42).flight();

        assertTrue(flight.alarms.log().isEmpty(), () -> "unexpected alarms: " + flight.alarms.log());
        for (FlightEvent event : List.of(FlightEvent.LIFTOFF, FlightEvent.MAX_Q, FlightEvent.MECO,
                FlightEvent.STAGE_SEPARATION, FlightEvent.TOUCHDOWN, FlightEvent.SECO, FlightEvent.ORBIT,
                FlightEvent.PAYLOAD_SEPARATION, FlightEvent.END_OF_MISSION)) {
            assertTrue(flight.events.contains(event), event + " missing");
        }
        assertTrue(flight.events.first(FlightEvent.MAX_Q).ground());
        assertEquals("COMPLETE", SnapshotWriter.state(flight));
        assertEquals(0, flight.link.lost());
    }

    @Test
    void anEngineFailureIsCaughtWithinASecond() {
        Flight flight = FlightHarness.fly(Scenario.ENGINE_OUT, 42).flight();
        AlarmManager.LogEntry first = firstRaise(flight, "ENGINE:5");

        assertEquals(Severity.WARNING, first.severity());
        double met = flight.clock.metAt(first.clock());
        assertTrue(met > 80 && met < 81, "raised at T+" + met);
        Alarm alarm = current(flight, "ENGINE:5");
        assertTrue(alarm.message().contains("without a command"), alarm.message());
    }

    @Test
    void aDegradedEngineIsCaughtThoughItStaysInsideItsLimits() {
        Flight flight = FlightHarness.fly(Scenario.ENGINE_DEGRADED, 42).flight();

        assertEquals(Severity.CAUTION, firstRaise(flight, "ENGINE:3:DEGRADED").severity());
        assertTrue(flight.alarms.log().stream().noneMatch(e -> e.key().startsWith("LIMIT:")),
                "the limits should not have seen it");
    }

    @Test
    void aHeliumLeakIsFlaggedByItsRateLongBeforeAnyTankLimit() {
        Flight flight = FlightHarness.fly(Scenario.HELIUM_LEAK, 42).flight();
        double rate = firstRaise(flight, "RATE:S1_COPV_P").clock();
        double tank = firstRaise(flight, "LIMIT:S1_LOX_TANK_P").clock();

        assertTrue(flight.clock.metAt(rate) < 47, "leak starts at T+45");
        assertTrue(tank - rate > 20, "lead time was only " + (tank - rate) + " s");
    }

    @Test
    void aFrozenSensorIsMarkedSuspect() {
        Flight flight = FlightHarness.fly(Scenario.STUCK_SENSOR, 42).flight();
        AlarmManager.LogEntry stuck = firstRaise(flight, "STUCK:S2_LOX_TANK_P");

        double met = flight.clock.metAt(stuck.clock());
        assertTrue(met > 300 && met < 303, "raised at T+" + met);
        assertTrue(flight.stuck.suspect(flight.dictionary.channel("S2_LOX_TANK_P").index()));
    }

    @Test
    void aDamagedLinkIsMeasuredWithoutFalseAlarms() {
        Flight flight = FlightHarness.fly(Scenario.NOISY_LINK, 42).flight();
        LinkStatistics link = flight.link;

        assertTrue(link.lossPercent() > 2.5 && link.lossPercent() < 4.5, "loss " + link.lossPercent());
        assertTrue(link.crcErrors() > 100, "CRC errors " + link.crcErrors());
        assertTrue(link.duplicates() > 100, "duplicates " + link.duplicates());
        assertTrue(flight.events.contains(FlightEvent.ORBIT));
        assertTrue(flight.alarms.log().isEmpty(), () -> "unexpected alarms: " + flight.alarms.log());
    }

    private static AlarmManager.LogEntry firstRaise(Flight flight, String key) {
        AlarmManager.LogEntry entry = flight.alarms.log().stream()
                .filter(e -> e.key().equals(key) && e.change() == AlarmManager.Change.RAISED)
                .findFirst().orElse(null);
        assertNotNull(entry, () -> key + " was never raised; log: " + flight.alarms.log());
        return entry;
    }

    private static Alarm current(Flight flight, String key) {
        return flight.alarms.current().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
    }
}
