package telemetry.protocol;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Locale;

/** Wire encodings a channel can use. Multi-byte types are big-endian, as CCSDS requires. */
public enum ChannelType {
    U8(1), U16(2), U32(4), I32(4), F32(4), F64(8);

    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle FLOAT = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle DOUBLE = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.BIG_ENDIAN);

    private final int size;

    ChannelType(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public boolean isFloatingPoint() {
        return this == F32 || this == F64;
    }

    public double read(byte[] buffer, int offset) {
        return switch (this) {
            case U8 -> buffer[offset] & 0xFF;
            case U16 -> (short) SHORT.get(buffer, offset) & 0xFFFF;
            case U32 -> (int) INT.get(buffer, offset) & 0xFFFF_FFFFL;
            case I32 -> (int) INT.get(buffer, offset);
            case F32 -> (float) FLOAT.get(buffer, offset);
            case F64 -> (double) DOUBLE.get(buffer, offset);
        };
    }

    public void write(byte[] buffer, int offset, double value) {
        switch (this) {
            case U8 -> buffer[offset] = (byte) clampRound(value, 0, 0xFF);
            case U16 -> SHORT.set(buffer, offset, (short) clampRound(value, 0, 0xFFFF));
            case U32 -> INT.set(buffer, offset, (int) clampRound(value, 0, 0xFFFF_FFFFL));
            case I32 -> INT.set(buffer, offset, (int) clampRound(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case F32 -> FLOAT.set(buffer, offset, (float) value);
            case F64 -> DOUBLE.set(buffer, offset, value);
        }
    }

    static ChannelType parse(String token) {
        return valueOf(token.toUpperCase(Locale.ROOT));
    }

    private static long clampRound(double value, long min, long max) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(min, Math.min(max, Math.round(value)));
    }
}
