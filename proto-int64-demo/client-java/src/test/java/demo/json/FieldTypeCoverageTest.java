package demo.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** simlog.proto に無いフィールド型の扱いを {@link DynamicFixtures} の DynamicMessage で確認する。 */
class FieldTypeCoverageTest {

    private final Int64JsonConverter converter = new Int64JsonConverter();

    @Test
    @DisplayName("repeated int64 / sint64 / sfixed64 は数値化、uint64 / fixed64 / Int64Value / map は文字列のまま")
    void fieldTypes() throws Exception {
        DynamicMessage m = DynamicFixtures.extra(true);

        assertEquals("{\"ids\":[\"1\",\"9223372036854775807\",\"-5\"],\"s\":\"-3\",\"f\":\"-9223372036854775808\","
                + "\"u\":\"18446744073709551615\",\"fx\":\"5\",\"i32\":6,\"w\":\"9007199254740993\","
                + "\"children\":[{\"ids\":[\"7\"]}],\"m\":{\"k\":\"8\"}}", converter.toRawJson(m));

        String json = converter.toJson(m);
        assertEquals("{\"ids\":[1,9223372036854775807,-5],\"s\":-3,\"f\":-9223372036854775808,"
                + "\"u\":\"18446744073709551615\",\"fx\":\"5\",\"i32\":6,\"w\":\"9007199254740993\","
                + "\"children\":[{\"ids\":[7]}],\"m\":{\"k\":\"8\"}}", json);

        JsonNode n = Int64JsonConverter.mapper().readTree(json);
        assertTrue(n.get("ids").get(1).isLong());
        assertTrue(n.get("u").isTextual());
        assertTrue(n.get("w").isTextual());
        assertTrue(n.at("/m/k").isTextual());
    }
}
