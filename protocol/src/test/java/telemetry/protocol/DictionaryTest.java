package telemetry.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DictionaryTest {

    @Test
    void standardDictionaryLaysChannelsOutBackToBack() {
        Dictionary dictionary = Dictionary.standard();
        for (PacketDefinition packet : dictionary.packets()) {
            int offset = SpacePacket.HEADER_LENGTH;
            for (Channel channel : packet.channels()) {
                assertEquals(offset, channel.offset(), channel.name());
                offset += channel.type().size();
            }
            assertEquals(offset + SpacePacket.CRC_LENGTH, packet.packetLength());
        }
        assertEquals(dictionary.channels().size(), dictionary.channels().getLast().index() + 1);
    }

    @Test
    void conditionsGateChecks() {
        Dictionary dictionary = Dictionary.standard();
        Channel pc = dictionary.channel("S1_ENG3_PC");
        Channel accel = dictionary.channel("S1_ACCEL");
        double[] current = new double[dictionary.channels().size()];

        assertFalse(pc.checksEnabled(current));
        current[dictionary.channel("S1_ENG3_MAIN").index()] = 1;
        assertTrue(pc.checksEnabled(current));

        current[dictionary.channel("S1_MODE").index()] = 2;
        assertFalse(accel.checksEnabled(current));
        current[dictionary.channel("S1_MODE").index()] = 1;
        assertTrue(accel.checksEnabled(current));
    }

    @Test
    void limitsClassifyBothDirections() {
        Limits limits = Dictionary.standard().channel("S1_LOX_TANK_P").limits();
        assertEquals(Severity.NOMINAL, limits.classify(3.6));
        assertEquals(Severity.CAUTION, limits.classify(2.9));
        assertEquals(Severity.WARNING, limits.classify(2.5));
        assertEquals(Severity.CAUTION, limits.classify(4.3));
        assertEquals(Severity.WARNING, limits.classify(4.7));
    }

    @Test
    void reportsTheLineOfABadRow() {
        List<String> lines = List.of(
                "packet 0x100 TEST 10 A test packet",
                "  A  f32 sensor bar  1 2 3 4 - - fine",
                "  B  f32 sensor bar  4 3 2 1 - - limits out of order");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Dictionary.parse("test.txt", lines));
        assertTrue(e.getMessage().startsWith("test.txt:3:"), e.getMessage());
    }

    @Test
    void refusesDuplicateNamesAndUnknownConditions() {
        assertThrows(IllegalArgumentException.class, () -> Dictionary.parse("t", List.of(
                "packet 0x100 TEST 10 A test packet",
                "  A  f32 sensor bar - - - - - - one",
                "  A  f32 sensor bar - - - - - - two")));
        assertThrows(IllegalArgumentException.class, () -> Dictionary.parse("t", List.of(
                "packet 0x100 TEST 10 A test packet",
                "  A  f32 sensor bar - - - - - NOPE gated on nothing")));
    }
}
