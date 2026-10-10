package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesNestedStructures() {
        String json = new Json().beginObject()
                .field("name", "S1 \"core\"\n")
                .field("count", 3)
                .name("values").beginArray().value(1.5).value(Double.NaN).value(2.0).endArray()
                .field("ok", true)
                .endObject().toString();
        assertEquals("{\"name\":\"S1 \\\"core\\\"\\n\",\"count\":3,\"values\":[1.5,null,2],\"ok\":true}", json);
    }

    @Test
    void roundsToFourDecimals() {
        StringBuilder out = new StringBuilder();
        Json.number(out, 3.599999904632568);
        assertEquals("3.6", out.toString());
    }
}
