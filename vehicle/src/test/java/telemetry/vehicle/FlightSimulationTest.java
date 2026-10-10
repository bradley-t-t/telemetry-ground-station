package telemetry.vehicle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.SpacePacket;

class FlightSimulationTest {

    @Test
    void nominalFlightReachesOrbitAndLandsTheBooster() {
        FlightSimulation flight = new FlightSimulation(Scenario.NOMINAL, packet -> { }, 7);
        FlightRunner.runToCompletion(flight);
        Map<FlightEvent, FlightSimulation.EventRecord> events = byEvent(flight.events());

        assertTrue(events.containsKey(FlightEvent.TOUCHDOWN), "booster did not land: " + events.keySet());
        assertTrue(events.get(FlightEvent.TOUCHDOWN).arg() < 5, "touchdown too hard");
        double perigee = events.get(FlightEvent.SECO).arg();
        assertTrue(perigee >= 199 && perigee < 210, "perigee " + perigee + " km");

        double meco = events.get(FlightEvent.MECO).met();
        assertTrue(meco > 130 && meco < 160, "MECO at T+" + meco);
        assertTrue(events.get(FlightEvent.SECO).met() < 560, "second engine cutoff too late");
    }

    @Test
    void eventsHappenInFlightOrder() {
        FlightSimulation flight = new FlightSimulation(Scenario.NOMINAL, packet -> { }, 7);
        FlightRunner.runToCompletion(flight);
        List<FlightEvent> order = flight.events().stream().map(FlightSimulation.EventRecord::event).toList();
        assertOrdered(order, FlightEvent.COUNTDOWN, FlightEvent.IGNITION, FlightEvent.LIFTOFF,
                FlightEvent.THROTTLE_DOWN, FlightEvent.THROTTLE_UP, FlightEvent.MECO,
                FlightEvent.STAGE_SEPARATION, FlightEvent.S2_IGNITION, FlightEvent.FAIRING_SEPARATION,
                FlightEvent.SECO, FlightEvent.PAYLOAD_SEPARATION, FlightEvent.END_OF_MISSION);
        assertOrdered(order, FlightEvent.STAGE_SEPARATION, FlightEvent.GRID_FINS, FlightEvent.ENTRY_BURN_START,
                FlightEvent.ENTRY_BURN_END, FlightEvent.LANDING_BURN_START, FlightEvent.LANDING_LEGS,
                FlightEvent.TOUCHDOWN);
    }

    @Test
    void theVehicleSurvivesLosingAnEngine() {
        FlightSimulation flight = new FlightSimulation(Scenario.ENGINE_OUT, packet -> { }, 7);
        FlightRunner.runToCompletion(flight);
        Map<FlightEvent, FlightSimulation.EventRecord> events = byEvent(flight.events());

        FlightSimulation.EventRecord shutdown = events.get(FlightEvent.ENGINE_SHUTDOWN);
        assertEquals(5, shutdown.arg());
        assertTrue(shutdown.met() > 80 && shutdown.met() < 81.5, "isolated at T+" + shutdown.met());
        assertTrue(events.containsKey(FlightEvent.TOUCHDOWN));
        assertTrue(events.get(FlightEvent.SECO).arg() >= 199);
        double nominalMeco = mecoTime(Scenario.NOMINAL);
        assertTrue(events.get(FlightEvent.MECO).met() > nominalMeco + 3, "eight engines should burn longer");
    }

    @Test
    void packetsGoOutAtTheirDictionaryRates() {
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        FlightSimulation flight = new FlightSimulation(Scenario.NOMINAL,
                packet -> counts.merge(SpacePacket.apid(packet), 1, Integer::sum), 7);
        FlightRunner.runToCompletion(flight);
        double seconds = flight.time();
        assertEquals(50 * seconds, counts.get(0x101), 2);
        assertEquals(25 * seconds, counts.get(0x102), 2);
        assertEquals(5 * seconds, counts.get(0x103), 2);
        assertEquals(50 * seconds, counts.get(0x201), 2);
    }

    @Test
    void theSameSeedProducesTheSameBytes() throws Exception {
        assertArrayEquals(digest(Scenario.NOISY_LINK, 99), digest(Scenario.NOISY_LINK, 99));
    }

    private static double mecoTime(Scenario scenario) {
        FlightSimulation flight = new FlightSimulation(scenario, packet -> { }, 7);
        FlightRunner.runToCompletion(flight);
        return byEvent(flight.events()).get(FlightEvent.MECO).met();
    }

    private static byte[] digest(Scenario scenario, long seed) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        FlightSimulation flight = new FlightSimulation(scenario, sha::update, seed);
        FlightRunner.runToCompletion(flight);
        return sha.digest();
    }

    private static Map<FlightEvent, FlightSimulation.EventRecord> byEvent(List<FlightSimulation.EventRecord> records) {
        Map<FlightEvent, FlightSimulation.EventRecord> map = new EnumMap<>(FlightEvent.class);
        for (FlightSimulation.EventRecord record : records) {
            map.putIfAbsent(record.event(), record);
        }
        return map;
    }

    private static void assertOrdered(List<FlightEvent> actual, FlightEvent... expected) {
        List<Integer> positions = new ArrayList<>();
        for (FlightEvent event : expected) {
            int index = actual.indexOf(event);
            assertTrue(index >= 0, event + " never happened");
            positions.add(index);
        }
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i) > positions.get(i - 1), expected[i] + " came before " + expected[i - 1]);
        }
    }
}
