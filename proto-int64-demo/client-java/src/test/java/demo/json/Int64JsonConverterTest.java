package demo.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ExecEvent;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.util.List;

import static demo.json.JsonAssertions.assertInt64AreNumbersAndStringsAreStrings;
import static demo.json.JsonAssertions.assertSameExceptInt64Quotes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Int64JsonConverterTest {

    private static final ObjectMapper MAPPER = Int64JsonConverter.mapper();
    private final Int64JsonConverter converter = new Int64JsonConverter();

    /** 出力文字列を外部の利用者と同じように再パースする。 */
    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Nested
    @DisplayName("正しさ: int64 は数値、string は文字列")
    class Correctness {

        @Test
        @DisplayName("全 int64 フィールド(9 種)が JSON 数値になり、値が一致する")
        void allInt64FieldsAreNumbers() throws Exception {
            SimLog msg = TestData.simLog(true);
            JsonNode inMemory = converter.toJsonNode(msg);
            JsonNode n = parse(converter.toJson(msg));

            // in-memory の JsonNode は LongNode
            assertTrue(inMemory.get("jobId").isLong());
            assertTrue(inMemory.at("/logs/0/events/0/commEvent/fromId").isLong());

            assertLongNumber(n.at("/jobId"), TestData.TWO_POW_53_PLUS_1);
            assertLongNumber(n.at("/logs/0/timestamp"), 1700000000123L);
            assertLongNumber(n.at("/logs/0/objectId"), 11);
            assertLongNumber(n.at("/logs/0/events/0/eventId"), 101);
            assertLongNumber(n.at("/logs/0/events/0/eventType"), TestData.TWO_POW_53_PLUS_1);
            assertLongNumber(n.at("/logs/0/events/0/commEvent/fromId"), Long.MAX_VALUE);
            assertLongNumber(n.at("/logs/0/events/0/commEvent/toId"), Long.MIN_VALUE);
            assertLongNumber(n.at("/logs/0/events/1/execEvent/execId"), -9007199254740993L);
            assertLongNumber(n.at("/logs/1/timestamp"), -1);
            assertLongNumber(n.at("/result/exitCode"), Long.MIN_VALUE);

            // ツリー全体の走査でも確認(19 個 = jobId 1 + logs[0] 11 + logs[1] 6 + result 1)
            assertEquals(19, assertInt64AreNumbersAndStringsAreStrings(n, "$"));
        }

        @Test
        @DisplayName("数字だけの string(output/body/command/result)は文字列のまま")
        void numericLookingStringsStayStrings() throws Exception {
            JsonNode n = parse(converter.toJson(TestData.simLog(true)));
            assertTextual(n.at("/logs/0/events/0/commEvent/body"), "12345");
            assertTextual(n.at("/logs/0/events/1/execEvent/command"), "-9223372036854775808");
            assertTextual(n.at("/logs/0/events/1/execEvent/result"), "9007199254740993");
            assertTextual(n.at("/result/output"), "9223372036854775807");
            assertTextual(n.at("/logs/1/events/0/commEvent/body"), "a\nb \"quoted\" 日本語");
        }

        @Test
        @DisplayName("oneof: comm_event / exec_event / 未設定 の 3 通り")
        void oneofAllCases() throws Exception {
            JsonNode events = parse(converter.toJson(TestData.objectLog1())).get("events");

            JsonNode comm = events.get(0);
            assertTrue(comm.has("commEvent"));
            assertFalse(comm.has("execEvent"));
            assertLongNumber(comm.at("/commEvent/toId"), Long.MIN_VALUE);

            JsonNode exec = events.get(1);
            assertFalse(exec.has("commEvent"));
            assertTrue(exec.has("execEvent"));
            assertLongNumber(exec.at("/execEvent/execId"), -9007199254740993L);

            JsonNode none = events.get(2);
            assertFalse(none.has("commEvent"));
            assertFalse(none.has("execEvent"));
            assertEquals(List.of("eventId", "eventType"), fieldNames(none));
        }

        @Test
        @DisplayName("optional result: あり→キーあり、なし→キー自体が無い")
        void optionalResultPresence() throws Exception {
            JsonNode with = parse(converter.toJson(TestData.simLog(true)));
            JsonNode without = parse(converter.toJson(TestData.simLog(false)));
            assertTrue(with.has("result"));
            assertTrue(with.get("result").isObject());
            assertFalse(without.has("result"));
            assertEquals(18, assertInt64AreNumbersAndStringsAreStrings(without, "$"));
        }

        @Test
        @DisplayName("期待値 JSON(手書き)と文字列として完全一致する")
        void matchesHandWrittenExpectedJson() throws Exception {
            String expected = """
                    {"jobId":9007199254740993,"logs":[\
                    {"timestamp":1700000000123,"objectId":11,"events":[\
                    {"eventId":101,"eventType":9007199254740993,"commEvent":{"fromId":9223372036854775807,"toId":-9223372036854775808,"body":"12345"}},\
                    {"eventId":102,"eventType":-7,"execEvent":{"execId":-9007199254740993,"command":"-9223372036854775808","result":"9007199254740993"}},\
                    {"eventId":103,"eventType":3}]},\
                    {"timestamp":-1,"objectId":9223372036854775807,"events":[\
                    {"eventId":104,"eventType":4,"commEvent":{"fromId":1,"toId":2,"body":"a\\nb \\"quoted\\" 日本語"}}]}],\
                    "result":{"exitCode":-9223372036854775808,"output":"9223372036854775807"}}""";
            String actual = converter.toJson(TestData.simLog(true));
            assertEquals(expected, actual);
            assertEquals(parse(expected), parse(actual));
        }

        @Test
        @DisplayName("素の JsonFormat 出力との差分は int64 のダブルクォート有無のみ(JsonNode 比較・キー順含む)")
        void sameAsRawExceptInt64Quotes() throws Exception {
            for (SimLog msg : List.of(TestData.simLog(true), TestData.simLog(false))) {
                JsonNode raw = parse(converter.toRawJson(msg));
                JsonNode conv = parse(converter.toJson(msg));
                int changed = assertSameExceptInt64Quotes(raw, conv, "$");
                assertEquals(msg.hasResult() ? 19 : 18, changed);
            }
        }

        @Test
        @DisplayName("文字列レベル: 素の出力から int64 の引用符を外したものと完全一致(特殊記号を含まない場合)")
        void sameAsRawStringExceptInt64Quotes() {
            SimLog msg = TestData.simLog(true);
            String rawUnquoted = converter.toRawJson(msg)
                    .replaceAll("\"(jobId|timestamp|objectId|eventId|eventType|exitCode|fromId|toId|execId)\":\"(-?\\d+)\"",
                            "\"$1\":$2");
            assertEquals(rawUnquoted, converter.toJson(msg));
        }

        @Test
        @DisplayName("制約: < > & = ' は JsonFormat では \\u00XX エスケープ、Jackson 再出力では生文字(意味は同一)")
        void htmlSensitiveCharsAreEscapedDifferently() throws Exception {
            CommEvent ev = CommEvent.newBuilder().setFromId(1).setBody("<tag> & a=b 'q'").build();
            String raw = converter.toRawJson(ev);
            String conv = converter.toJson(ev);
            assertEquals("{\"fromId\":\"1\",\"body\":\"\\u003ctag\\u003e \\u0026 a\\u003db \\u0027q\\u0027\"}", raw);
            assertEquals("{\"fromId\":1,\"body\":\"<tag> & a=b 'q'\"}", conv);
            assertEquals(parse(raw).get("body"), parse(conv).get("body"));
        }

        @Test
        @DisplayName("変換後 JSON は JsonFormat.parser() で元のメッセージに復元できる(数値の int64 も受理される)")
        void roundTripWithJsonFormatParser() throws Exception {
            for (SimLog msg : List.of(TestData.simLog(true), TestData.simLog(false))) {
                SimLog.Builder b = SimLog.newBuilder();
                JsonFormat.parser().merge(converter.toJson(msg), b);
                assertEquals(msg, b.build());
            }
        }
    }

    @Nested
    @DisplayName("大きな値・境界値・0")
    class Values {

        @ParameterizedTest(name = "jobId = {0}")
        @ValueSource(longs = {9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, -9007199254740993L, 1L,
                2147483648L, -2147483649L})
        @DisplayName("Java の long として値が一致する")
        void longValuesRoundTrip(long value) throws Exception {
            SimLog msg = SimLog.newBuilder().setJobId(value).build();
            String json = converter.toJson(msg);
            assertEquals("{\"jobId\":" + value + "}", json);
            JsonNode n = parse(json).get("jobId");
            assertTrue(n.isIntegralNumber());
            assertEquals(value, n.longValue());
            // Jackson は値が int に収まれば IntNode、収まらなければ LongNode としてパースする
            boolean fitsInt = value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            assertEquals(!fitsInt, n.isLong());
            assertEquals(fitsInt, n.isInt());
        }

        @Test
        @DisplayName("参考: 2^53+1 は double(JavaScript の Number 等)では表現できない")
        void doubleLosesPrecision() throws Exception {
            long v = TestData.TWO_POW_53_PLUS_1;
            assertNotEquals(v, (long) (double) v);
            // 利用側が double でパースすると値が変わる
            double d = MAPPER.readTree("{\"v\":" + v + "}").get("v").doubleValue();
            assertEquals(9007199254740992.0, d);
        }

        @Test
        @DisplayName("0: 既定の JsonFormat ではキーごと省略される(int64 / string とも)")
        void zeroIsOmittedByDefault() {
            ObjectLog msg = zeroObjectLog();
            assertEquals("{\"events\":[{\"commEvent\":{}}]}", converter.toRawJson(msg));
            assertEquals("{\"events\":[{\"commEvent\":{}}]}", converter.toJson(msg));
        }

        @Test
        @DisplayName("0: alwaysPrintFieldsWithNoPresence() を付けると 0 も数値で出力される")
        void zeroIsPrintedWithAlwaysPrint() throws Exception {
            Int64JsonConverter always = new Int64JsonConverter(
                    JsonFormat.printer().omittingInsignificantWhitespace().alwaysPrintFieldsWithNoPresence());
            ObjectLog msg = zeroObjectLog();
            assertEquals("{\"timestamp\":\"0\",\"objectId\":\"0\",\"events\":[{\"eventId\":\"0\",\"eventType\":\"0\","
                    + "\"commEvent\":{\"fromId\":\"0\",\"toId\":\"0\",\"body\":\"\"}}]}", always.toRawJson(msg));
            assertEquals("{\"timestamp\":0,\"objectId\":0,\"events\":[{\"eventId\":0,\"eventType\":0,"
                    + "\"commEvent\":{\"fromId\":0,\"toId\":0,\"body\":\"\"}}]}", always.toJson(msg));

            // optional result(presence あり)と未設定 oneof は alwaysPrint でも出力されない。空の repeated は [] になる
            assertEquals("{\"jobId\":0,\"logs\":[]}", always.toJson(SimLog.getDefaultInstance()));
            assertEquals("{\"eventId\":0,\"eventType\":0}", always.toJson(Event.getDefaultInstance()));
            // optional result を「デフォルト値のまま設定」した場合は presence があるので出力される
            assertEquals("{\"jobId\":0,\"logs\":[],\"result\":{\"exitCode\":0,\"output\":\"\"}}",
                    always.toJson(SimLog.newBuilder().setResult(demo.proto.Result.getDefaultInstance()).build()));
            assertEquals("{\"result\":{}}",
                    converter.toJson(SimLog.newBuilder().setResult(demo.proto.Result.getDefaultInstance()).build()));
        }

        private ObjectLog zeroObjectLog() {
            return ObjectLog.newBuilder()
                    .addEvents(Event.newBuilder().setCommEvent(CommEvent.getDefaultInstance()))
                    .build();
        }
    }

    @Nested
    @DisplayName("パターン3: NDJSON")
    class Ndjson {

        @Test
        @DisplayName("1 行 1 JSON、各行が単独でパース可能、行数 = ObjectLog 件数、改行を含む body でも 1 行")
        void oneJsonPerLine() throws Exception {
            List<ObjectLog> logs = TestData.simLog(false).getLogsList();
            String ndjson = converter.toNdjson(logs);

            assertTrue(ndjson.endsWith("\n"));
            String[] lines = ndjson.substring(0, ndjson.length() - 1).split("\n", -1);
            assertEquals(logs.size(), lines.length);
            for (int i = 0; i < lines.length; i++) {
                assertFalse(lines[i].isEmpty());
                assertFalse(lines[i].contains("\r"));
                JsonNode line = parse(lines[i]);
                assertEquals(parse(converter.toJson(logs.get(i))), line);
                assertInt64AreNumbersAndStringsAreStrings(line, "$[" + i + "]");
            }
            // 2 行目の body は改行を含むが、JSON エスケープされて 1 行に収まっている
            assertTrue(lines[1].contains("\"body\":\"a\\nb \\\"quoted\\\" 日本語\""));
            assertEquals("a\nb \"quoted\" 日本語", parse(lines[1]).at("/events/0/commEvent/body").textValue());
        }

        @Test
        @DisplayName("writeNdjson(Writer) と toNdjson の出力が一致")
        void writerAndStringAgree() {
            List<ObjectLog> logs = TestData.simLog(false).getLogsList();
            StringWriter w = new StringWriter();
            converter.writeNdjson(logs, w);
            assertEquals(converter.toNdjson(logs), w.toString());
        }

        @Test
        @DisplayName("ObjectLog 0 件なら空文字列")
        void empty() {
            assertEquals("", converter.toNdjson(List.of()));
        }
    }

    @Nested
    @DisplayName("汎用性")
    class Generic {

        @Test
        @DisplayName("ObjectLog 単体・Builder(MessageOrBuilder)でも変換できる")
        void acceptsAnyMessageOrBuilder() throws Exception {
            ObjectLog log = TestData.objectLog1();
            String fromMessage = converter.toJson(log);
            String fromBuilder = converter.toJson(log.toBuilder());
            assertEquals(fromMessage, fromBuilder);
            assertEquals(11, assertInt64AreNumbersAndStringsAreStrings(parse(fromMessage), "$"));

            ExecEvent exec = TestData.execEvent().getExecEvent();
            assertEquals("{\"execId\":-9007199254740993,\"command\":\"-9223372036854775808\",\"result\":\"9007199254740993\"}",
                    converter.toJson(exec));
        }

        @Test
        @DisplayName("preservingProtoFieldNames() の snake_case キーでも変換される")
        void protoFieldNames() throws Exception {
            Int64JsonConverter snake = new Int64JsonConverter(
                    JsonFormat.printer().omittingInsignificantWhitespace().preservingProtoFieldNames());
            JsonNode n = parse(snake.toJson(TestData.simLog(true)));
            assertLongNumber(n.get("job_id"), TestData.TWO_POW_53_PLUS_1);
            assertLongNumber(n.at("/logs/0/object_id"), 11);
            assertLongNumber(n.at("/logs/0/events/0/comm_event/from_id"), Long.MAX_VALUE);
            assertLongNumber(n.at("/result/exit_code"), Long.MIN_VALUE);
            assertTextual(n.at("/logs/0/events/0/comm_event/body"), "12345");
            assertFalse(n.has("jobId"));
        }
    }

    // ---------------------------------------------------------------------------------------------

    static void assertLongNumber(JsonNode n, long expected) {
        assertFalse(n.isMissingNode(), "missing node");
        assertTrue(n.isIntegralNumber(), "not a JSON integer: " + n);
        assertTrue(n.canConvertToLong(), "does not fit in long: " + n);
        assertEquals(expected, n.longValue());
    }

    static void assertTextual(JsonNode n, String expected) {
        assertTrue(n.isTextual(), "not a JSON string: " + n);
        assertEquals(expected, n.textValue());
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> names = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
