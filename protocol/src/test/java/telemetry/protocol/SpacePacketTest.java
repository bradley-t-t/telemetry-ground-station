package telemetry.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SpacePacketTest {

    private final Dictionary dictionary = Dictionary.standard();
    private final PacketDecoder decoder = new PacketDecoder(dictionary);

    @Test
    void primaryHeaderIsBitExact() {
        PacketDefinition nav = dictionary.packet("S1_NAV");
        byte[] packet = new PacketEncoder(nav).encode(1015.5);

        // version 000, type 0, secondary header 1, APID 0x101
        assertEquals(0x09, packet[0] & 0xFF);
        assertEquals(0x01, packet[1] & 0xFF);
        // unsegmented, sequence 0
        assertEquals(0xC0, packet[2] & 0xFF);
        assertEquals(0x00, packet[3] & 0xFF);
        assertEquals(packet.length - 7, SpacePacket.u16(packet, 4));
        assertEquals(1015.5, SpacePacket.clock(packet), 1e-9);
    }

    @Test
    void valuesSurviveARoundTrip() {
        PacketDefinition prop = dictionary.packet("S1_PROP");
        PacketEncoder encoder = new PacketEncoder(prop);
        encoder.set(dictionary.channel("S1_THROTTLE"), 72.5)
                .set(dictionary.channel("S1_ENGINES"), 9)
                .set(dictionary.channel("S1_ENG5_PC"), 96.25)
                .set(dictionary.channel("S1_COPV_P"), 287.0);

        DecodedPacket decoded = assertInstanceOf(DecodedPacket.class, decoder.decode(encoder.encode(1042.25)));

        assertEquals(prop, decoded.definition());
        assertEquals(72.5, decoded.value(dictionary.channel("S1_THROTTLE")));
        assertEquals(9, decoded.value(dictionary.channel("S1_ENGINES")));
        assertEquals(96.25, decoded.value(dictionary.channel("S1_ENG5_PC")));
        assertEquals(287.0, decoded.value(dictionary.channel("S1_COPV_P")));
        assertEquals(1042.25, decoded.clock(), 1e-9);
    }

    @Test
    void sequenceCountWrapsAtFourteenBits() {
        PacketEncoder encoder = new PacketEncoder(dictionary.packet("S2_AVIONICS"));
        int last = -1;
        for (int i = 0; i <= SpacePacket.SEQUENCE_MODULUS; i++) {
            last = SpacePacket.sequence(encoder.encode(i));
        }
        assertEquals(0, last);
    }

    @Test
    void aSingleFlippedBitIsCaughtByTheCrc() {
        byte[] packet = new PacketEncoder(dictionary.packet("S2_NAV")).encode(1100);
        for (int bit = SpacePacket.PRIMARY_HEADER_LENGTH * 8; bit < packet.length * 8; bit++) {
            byte[] corrupted = packet.clone();
            corrupted[bit / 8] ^= (byte) (1 << (bit % 8));
            Rejected rejected = assertInstanceOf(Rejected.class, decoder.decode(corrupted));
            assertEquals(Rejected.Reason.CRC_MISMATCH, rejected.reason());
        }
    }

    @Test
    void refusesTruncatedAndUnknownPackets() {
        byte[] packet = new PacketEncoder(dictionary.packet("S2_NAV")).encode(1100);

        assertEquals(Rejected.Reason.TRUNCATED, reason(Arrays.copyOf(packet, 10)));
        assertEquals(Rejected.Reason.LENGTH_MISMATCH, reason(Arrays.copyOf(packet, packet.length - 1)));

        byte[] unknown = packet.clone();
        unknown[1] = 0x7F;
        SpacePacket.seal(unknown, 0x07F, 0, 1100);
        assertEquals(Rejected.Reason.UNKNOWN_APID, reason(unknown));
    }

    @Test
    void encodingIsDeterministic() {
        PacketDefinition nav = dictionary.packet("S2_NAV");
        PacketEncoder a = new PacketEncoder(nav).set(dictionary.channel("S2_ALTITUDE"), 201.4);
        PacketEncoder b = new PacketEncoder(nav).set(dictionary.channel("S2_ALTITUDE"), 201.4);
        assertArrayEquals(a.encode(1500.125), b.encode(1500.125));
    }

    private Rejected.Reason reason(byte[] data) {
        return assertInstanceOf(Rejected.class, decoder.decode(data)).reason();
    }
}
