package demo.json;

import com.fasterxml.jackson.databind.JsonNode;
import demo.proto.complextest.ComplexSimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** complex_test.proto(oneof 12 種・深いネスト・double / bytes / enum / Timestamp)でも 3 方式が文字列一致する。 */
class ComplexConvertersTest {

    private final Int64JsonConverter tree = new Int64JsonConverter();
    private final StreamingInt64JsonConverter streaming = new StreamingInt64JsonConverter();
    private final DirectInt64JsonWriter direct = new DirectInt64JsonWriter();

    @ParameterizedTest(name = "withTimestamp={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("複雑な proto で Tree / Streaming / Direct の出力(全体・NDJSON)が一致")
    void allImplementationsAgree(boolean withTimestamp) throws Exception {
        ComplexSimLog msg = ComplexData.simLog(6, 40, withTimestamp); // 240 Event、oneof 12 種+未設定を網羅
        String expected = tree.toJson(msg);
        assertEquals(expected, streaming.toJson(msg));
        assertEquals(expected, direct.toJson(msg));
        String expectedNd = tree.toNdjson(msg.getLogsList());
        assertEquals(expectedNd, streaming.toNdjson(msg.getLogsList()));
        assertEquals(expectedNd, direct.toNdjson(msg.getLogsList()));

        // 代表的な int64 が数値、double / Timestamp / bytes が想定どおりの JSON 型
        JsonNode n = Int64JsonConverter.mapper().readTree(expected);
        assertTrue(n.get("jobId").isIntegralNumber());
        assertTrue(n.at("/logs/1/header/tags/0").isIntegralNumber());
        assertTrue(n.at("/logs/0/events/7/nested/l1/l2/l3/values/0").isIntegralNumber());
        assertTrue(n.at("/logs/0/events/2/move/to/x").isNumber());
        assertTrue(n.at("/logs/0/events/6/blob/data").isTextual());
        assertEquals(withTimestamp, n.at("/logs/0/events/0/at").isTextual());
    }
}
