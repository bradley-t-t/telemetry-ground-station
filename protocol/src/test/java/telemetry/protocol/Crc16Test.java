package telemetry.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Crc16Test {

    @Test
    void matchesTheStandardCheckValue() {
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x29B1, Crc16.compute(data, 0, data.length));
    }

    @Test
    void emptyInputIsTheInitialValue() {
        assertEquals(0xFFFF, Crc16.compute(new byte[0], 0, 0));
    }
}
