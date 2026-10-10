package telemetry.ground;

/**
 * A small streaming JSON writer. Numbers are rounded to four decimal places, which is
 * finer than any channel in the dictionary resolves, and non-finite values become null.
 */
final class Json {

    private final StringBuilder out;
    private boolean needsComma;

    Json() {
        this(1024);
    }

    Json(int capacity) {
        out = new StringBuilder(capacity);
    }

    Json beginObject() {
        separate();
        out.append('{');
        needsComma = false;
        return this;
    }

    Json endObject() {
        out.append('}');
        needsComma = true;
        return this;
    }

    Json beginArray() {
        separate();
        out.append('[');
        needsComma = false;
        return this;
    }

    Json endArray() {
        out.append(']');
        needsComma = true;
        return this;
    }

    Json name(String name) {
        separate();
        string(name);
        out.append(':');
        needsComma = false;
        return this;
    }

    Json value(String value) {
        separate();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        needsComma = true;
        return this;
    }

    Json value(double value) {
        separate();
        number(out, value);
        needsComma = true;
        return this;
    }

    Json value(long value) {
        separate();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json value(boolean value) {
        separate();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json nullValue() {
        separate();
        out.append("null");
        needsComma = true;
        return this;
    }

    Json field(String name, String value) {
        return name(name).value(value);
    }

    Json field(String name, double value) {
        return name(name).value(value);
    }

    Json field(String name, long value) {
        return name(name).value(value);
    }

    Json field(String name, boolean value) {
        return name(name).value(value);
    }

    /** Appends text that is already valid JSON. */
    Json raw(String json) {
        separate();
        out.append(json);
        needsComma = true;
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static void number(StringBuilder out, double value) {
        if (!Double.isFinite(value)) {
            out.append("null");
            return;
        }
        double rounded = Math.round(value * 1e4) / 1e4;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            out.append((long) rounded);
        } else {
            out.append(rounded);
        }
    }

    private void separate() {
        if (needsComma) {
            out.append(',');
        }
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
