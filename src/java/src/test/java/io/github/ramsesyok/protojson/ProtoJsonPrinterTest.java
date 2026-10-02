package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.github.ramsesyok.protojson.testproto.AllTypes;
import io.github.ramsesyok.protojson.testproto.Color;
import io.github.ramsesyok.protojson.testproto.HasMapDeep;
import io.github.ramsesyok.protojson.testproto.Nested;
import io.github.ramsesyok.protojson.testproto.OutOfOrder;
import io.github.ramsesyok.protojson.testproto.WithMap;
import io.github.ramsesyok.protojson.testproto2.Extendable;
import io.github.ramsesyok.protojson.testproto2.Proto2Plain;
import io.github.ramsesyok.protojson.testproto2.WithGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProtoJsonPrinter の振る舞い(出力例そのものが仕様の説明になるよう、期待値は JSON 文字列で書く)。
 * 注: テスト用 proto の message 名 {@code Nested} と JUnit の {@code @Nested} が衝突するため、
 * JUnit 側は完全修飾名で書いている。
 */
class ProtoJsonPrinterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();

    @org.junit.jupiter.api.Nested
    @DisplayName("int64 系は JSON の数値")
    class Int64 {

        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {1, -1, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE, -9007199254740993L})
        @DisplayName("int64 / sint64 / sfixed64 が数値で出力され、long として値が一致する")
        void int64Family(long v) throws Exception {
            AllTypes m = AllTypes.newBuilder().setFInt64(v).setFSint64(v).setFSfixed64(v).addRInt64(v).build();
            String json = printer.print(m);
            assertEquals("{\"fInt64\":" + v + ",\"fSint64\":" + v + ",\"fSfixed64\":" + v + ",\"rInt64\":[" + v + "]}", json);
            JsonNode n = MAPPER.readTree(json);
            for (String key : List.of("fInt64", "fSint64", "fSfixed64")) {
                assertTrue(n.get(key).isIntegralNumber());
                assertEquals(v, n.get(key).longValue());
            }
        }

        @Test
        @DisplayName("数字だけの string は文字列のまま")
        void numericStringsStayStrings() {
            AllTypes m = AllTypes.newBuilder().setFString("12345").addRString("-9223372036854775808")
                    .setCString("9007199254740993").build();
            assertEquals("{\"fString\":\"12345\",\"rString\":[\"-9223372036854775808\"],\"cString\":\"9007199254740993\"}",
                    printer.print(m));
        }

        @Test
        @DisplayName("uint64 / fixed64 は対象外(JsonFormat と同じく符号なしの文字列)、uint32 / fixed32 は符号なしの数値")
        void unsigned() {
            AllTypes m = AllTypes.newBuilder().setFUint32(-1).setFFixed32(-2).setFUint64(-1).setFFixed64(5).build();
            assertEquals("{\"fUint32\":4294967295,\"fFixed32\":4294967294,"
                    + "\"fUint64\":\"18446744073709551615\",\"fFixed64\":\"5\"}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("フィールドの有無(presence)")
    class Presence {

        @Test
        @DisplayName("presence の無いフィールドはデフォルト値(0 / 空文字 / false / 先頭の enum)なら省略")
        void defaultsOmitted() {
            AllTypes m = AllTypes.newBuilder().setFInt64(0).setFString("").setFBool(false)
                    .setFEnum(Color.COLOR_UNSPECIFIED).setFDouble(0.0).build();
            assertEquals("{}", printer.print(m));
        }

        @Test
        @DisplayName("proto3 optional はデフォルト値でも設定されていれば出力")
        void optionalPresent() {
            AllTypes m = AllTypes.newBuilder().setOInt64(0).setOString("").setOEnum(Color.COLOR_UNSPECIFIED)
                    .setOBool(false).setODouble(0.0).build();
            assertEquals("{\"oInt64\":0,\"oString\":\"\",\"oEnum\":\"COLOR_UNSPECIFIED\",\"oBool\":false,\"oDouble\":0.0}",
                    printer.print(m));
        }

        @Test
        @DisplayName("message フィールドは設定されていれば中身が空でも {} を出力")
        void emptyMessagePresent() {
            assertEquals("{\"fNested\":{}}", printer.print(AllTypes.newBuilder().setFNested(Nested.getDefaultInstance()).build()));
        }

        @Test
        @DisplayName("oneof: 設定されたメンバーだけを出力(デフォルト値でも出力)、未設定ならどのキーも無い")
        void oneof() {
            assertEquals("{\"cInt64\":0}", printer.print(AllTypes.newBuilder().setCInt64(0).build()));
            assertEquals("{\"cString\":\"x\",\"sBool\":false}",
                    printer.print(AllTypes.newBuilder().setCString("x").setSBool(false).build()));
            assertEquals("{\"cNested\":{\"id\":1}}",
                    printer.print(AllTypes.newBuilder().setCNested(Nested.newBuilder().setId(1)).build()));
            assertEquals("{\"cEnum\":\"COLOR_UNSPECIFIED\"}",
                    printer.print(AllTypes.newBuilder().setCEnum(Color.COLOR_UNSPECIFIED).build()));
            assertEquals("{\"sDouble\":1.5}", printer.print(AllTypes.newBuilder().setSDouble(1.5).build()));
            assertEquals("{}", printer.print(AllTypes.getDefaultInstance()));
        }

        @Test
        @DisplayName("空の repeated は省略")
        void emptyRepeatedOmitted() {
            assertEquals("{\"fInt64\":1}", printer.print(AllTypes.newBuilder().setFInt64(1).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("キー名と順序")
    class Keys {

        @Test
        @DisplayName("キーは json_name(lowerCamelCase、json_name 指定があればそれ)")
        void jsonName() {
            assertEquals("{\"fInt64\":1,\"customJSON\":2}",
                    printer.print(AllTypes.newBuilder().setFInt64(1).setCustomJson(2).build()));
        }

        @Test
        @DisplayName("キーの順序はフィールド番号順(宣言順ではない)")
        void fieldNumberOrder() {
            OutOfOrder m = OutOfOrder.newBuilder().setZ(5).setName("n").setA(1).addIds(2)
                    .setInner(Nested.newBuilder().setId(3)).build();
            assertEquals("{\"a\":1,\"ids\":[2],\"name\":\"n\",\"inner\":{\"id\":3},\"z\":5}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("値の表現")
    class Values {

        @Test
        @DisplayName("enum は名前、proto3 で未知の値は数値")
        void enums() {
            assertEquals("{\"fEnum\":\"COLOR_RED\",\"rEnum\":[\"COLOR_GREEN\",1000]}",
                    printer.print(AllTypes.newBuilder().setFEnum(Color.COLOR_RED)
                            .addREnum(Color.COLOR_GREEN).addREnumValue(1000).build()));
        }

        @Test
        @DisplayName("bytes は標準 Base64(パディングあり)")
        void bytes() {
            assertEquals("{\"fBytes\":\"+/8A\",\"rBytes\":[\"YQ==\",\"\"]}",
                    printer.print(AllTypes.newBuilder().setFBytes(ByteString.copyFrom(new byte[]{(byte) 0xfb, (byte) 0xff, 0}))
                            .addRBytes(ByteString.copyFromUtf8("a")).addRBytes(ByteString.EMPTY).build()));
        }

        @Test
        @DisplayName("float / double は数値、NaN / Infinity は文字列")
        void floatingPoint() {
            AllTypes m = AllTypes.newBuilder().setFFloat(0.1f).setFDouble(1e21)
                    .addRFloat(Float.NaN).addRFloat(Float.NEGATIVE_INFINITY).addRFloat(-0.0f)
                    .addRDouble(Double.POSITIVE_INFINITY).addRDouble(123456789.125).build();
            assertEquals("{\"fFloat\":0.1,\"fDouble\":1.0E21,\"rFloat\":[\"NaN\",\"-Infinity\",-0.0],"
                    + "\"rDouble\":[\"Infinity\",1.23456789125E8]}", printer.print(m));
        }

        @Test
        @DisplayName("文字列のエスケープ: 改行・引用符・制御文字はエスケープ、日本語・絵文字・< > & = ' はそのまま")
        void stringEscaping() {
            AllTypes m = AllTypes.newBuilder().setFString("a\nb \"q\" \\ \u0001 日本 😀 <tag> & a=b 'q'").build();
            assertEquals("{\"fString\":\"a\\nb \\\"q\\\" \\\\ \\u0001 日本 😀 <tag> & a=b 'q'\"}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Well-Known Types(JsonFormat と同じ表現)")
    class WellKnown {

        @Test
        @DisplayName("Timestamp / Duration は文字列、ラッパー型は中の値(Int64Value / UInt64Value は文字列のまま)")
        void timestampDurationWrappers() {
            AllTypes m = AllTypes.newBuilder()
                    .setWTimestamp(Timestamp.newBuilder().setSeconds(1700000000L).setNanos(123000000))
                    .setWDuration(Duration.newBuilder().setSeconds(-3).setNanos(-500000000))
                    .setWInt64(Int64Value.of(9007199254740993L))
                    .setWUint64(UInt64Value.of(-1))
                    .setWInt32(Int32Value.of(-5))
                    .setWUint32(UInt32Value.of(-1))
                    .setWBool(BoolValue.of(false))
                    .setWString(StringValue.of("s"))
                    .setWBytes(BytesValue.of(ByteString.copyFromUtf8("a")))
                    .setWFloat(FloatValue.of(Float.NaN))
                    .setWDouble(DoubleValue.of(0.5))
                    .addRTimestamp(Timestamp.newBuilder().setSeconds(0).setNanos(5000))
                    .addRInt64Value(Int64Value.of(1))
                    .build();
            assertEquals("{\"wTimestamp\":\"2023-11-14T22:13:20.123Z\",\"wDuration\":\"-3.500s\","
                    + "\"wInt64\":\"9007199254740993\",\"wUint64\":\"18446744073709551615\",\"wInt32\":-5,"
                    + "\"wUint32\":4294967295,\"wBool\":false,\"wString\":\"s\",\"wBytes\":\"YQ==\","
                    + "\"wFloat\":\"NaN\",\"wDouble\":0.5,\"rTimestamp\":[\"1970-01-01T00:00:00.000005Z\"],"
                    + "\"rInt64Value\":[\"1\"]}", printer.print(m));
        }

        @Test
        @DisplayName("Struct / Value / ListValue / FieldMask / Empty は JsonFormat の表現")
        void structAndOthers() {
            AllTypes m = AllTypes.newBuilder()
                    .setWStruct(Struct.newBuilder().putFields("k", Value.newBuilder().setNumberValue(1.5).build()))
                    .setWValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
                    .setWList(ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("<x>")))
                    .setWMask(FieldMask.newBuilder().addPaths("f_int64").addPaths("f_nested.name"))
                    .setWEmpty(Empty.getDefaultInstance())
                    .build();
            assertEquals("{\"wStruct\":{\"k\":1.5},\"wValue\":null,\"wList\":[\"<x>\"],"
                    + "\"wMask\":\"fInt64,fNested.name\",\"wEmpty\":{}}", printer.print(m));
        }

        @Test
        @DisplayName("Any: TypeRegistry に登録した型なら出力できる(中の int64 は JsonFormat と同じく文字列)")
        void anyWithRegistry() {
            ProtoJsonPrinter withRegistry = ProtoJsonPrinter.builder()
                    .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                    .build();
            AllTypes m = AllTypes.newBuilder().setWAny(Any.pack(Nested.newBuilder().setId(5).build())).build();
            assertEquals("{\"wAny\":{\"@type\":\"type.googleapis.com/protojson.test.Nested\",\"id\":\"5\"}}",
                    withRegistry.print(m));
        }

        @Test
        @DisplayName("Any: TypeRegistry に無い型は IllegalArgumentException")
        void anyWithoutRegistry() {
            AllTypes m = AllTypes.newBuilder().setWAny(Any.pack(Nested.newBuilder().setId(5).build())).build();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> printer.print(m));
            assertTrue(e.getMessage().contains("typeRegistry"), e.getMessage());
        }

        @Test
        @DisplayName("範囲外の Timestamp / Duration は IllegalArgumentException")
        void invalidTimestampDuration() {
            assertThrows(IllegalArgumentException.class, () -> printer.print(
                    AllTypes.newBuilder().setWTimestamp(Timestamp.newBuilder().setSeconds(253402300800L)).build()));
            assertThrows(IllegalArgumentException.class, () -> printer.print(
                    AllTypes.newBuilder().setWDuration(Duration.newBuilder().setSeconds(1).setNanos(-1)).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("未対応の構造は型の時点で UnsupportedOperationException")
    class Unsupported {

        @Test
        @DisplayName("map フィールド(値が空でも、ネストした先にあっても)")
        void map() {
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> printer.print(WithMap.newBuilder().setId(1).build()));
            assertTrue(e.getMessage().contains("map field 'counts'"), e.getMessage());
            assertThrows(UnsupportedOperationException.class, () -> printer.print(HasMapDeep.getDefaultInstance()));
        }

        @Test
        @DisplayName("proto2 の group と extension")
        void proto2() {
            assertThrows(UnsupportedOperationException.class, () -> printer.print(WithGroup.getDefaultInstance()));
            assertThrows(UnsupportedOperationException.class, () -> printer.print(Extendable.getDefaultInstance()));
        }

        @Test
        @DisplayName("参考: proto2 の通常のメッセージ(optional / required)は JsonFormat と同じ出力")
        void proto2Plain() {
            JsonFormatOracle oracle = new JsonFormatOracle();
            for (Proto2Plain m : List.of(
                    Proto2Plain.newBuilder().setId(0).build(),
                    Proto2Plain.newBuilder().setId(Long.MIN_VALUE).setCount(5).setName("").build(),
                    Proto2Plain.newBuilder().setId(1).setCount(0).build())) {
                assertEquals(oracle.expected(m), printer.print(m));
            }
            assertEquals("{\"id\":0}", printer.print(Proto2Plain.newBuilder().setId(0).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("入出力 API")
    class Api {

        private final AllTypes sample = AllTypes.newBuilder().setFInt64(Long.MIN_VALUE).setFString("日本語 😀\n")
                .addRNested(Nested.newBuilder().setId(1)).build();

        @Test
        @DisplayName("writeTo(OutputStream) は UTF-8 で print と同じ内容を書き、ストリームを閉じない")
        void outputStream() throws IOException {
            CloseTrackingOutputStream out = new CloseTrackingOutputStream();
            printer.writeTo(sample, out);
            assertEquals(printer.print(sample), out.toString(StandardCharsets.UTF_8));
            assertFalse(out.closed);
        }

        @Test
        @DisplayName("writeTo(Writer) は print と同じ内容")
        void writer() throws IOException {
            StringWriter out = new StringWriter();
            printer.writeTo(sample, out);
            assertEquals(printer.print(sample), out.toString());
        }

        @Test
        @DisplayName("writeTo(JsonGenerator) で他の JSON に埋め込める")
        void embedInGenerator() throws IOException {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = new JsonFactory().createGenerator(out)) {
                g.writeStartObject();
                g.writeStringField("type", "allTypes");
                g.writeFieldName("data");
                printer.writeTo(sample, g);
                g.writeEndObject();
            }
            assertEquals("{\"type\":\"allTypes\",\"data\":" + printer.print(sample) + "}", out.toString());
        }

        @Test
        @DisplayName("Builder・DynamicMessage を渡しても同じ出力")
        void messageOrBuilderVariants() throws Exception {
            String expected = printer.print(sample);
            assertEquals(expected, printer.print(sample.toBuilder()));
            assertEquals(expected, printer.print(DynamicMessage.parseFrom(AllTypes.getDescriptor(), sample.toByteString())));
        }

        @Test
        @DisplayName("複数スレッドから同時に使っても結果が同じ(スレッドセーフ)")
        void threadSafety() throws Exception {
            RandomMessages random = new RandomMessages(99, 2);
            List<AllTypes> messages = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            ProtoJsonPrinter shared = ProtoJsonPrinter.builder()
                    .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                    .build();
            for (int i = 0; i < 200; i++) {
                AllTypes m = (AllTypes) random.next(AllTypes.newBuilder());
                messages.add(m);
                expected.add(shared.print(m)); // 1 スレッドで出した結果を正解とする
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    results.add(pool.submit(() -> {
                        for (int round = 0; round < 20; round++) {
                            for (int i = 0; i < messages.size(); i++) {
                                if (!expected.get(i).equals(shared.print(messages.get(i)))) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    }));
                }
                for (Future<Boolean> r : results) {
                    assertTrue(r.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("NDJSON")
    class Ndjson {

        private final List<Nested> messages = List.of(
                Nested.newBuilder().setId(Long.MAX_VALUE).setName("a\nb").build(),
                Nested.getDefaultInstance(),
                Nested.newBuilder().setId(-1).addValues(9007199254740993L).build());

        @Test
        @DisplayName("1 メッセージ 1 行・各行末に \\n・文字列中の改行はエスケープされ行は割れない")
        void printNdjson() throws Exception {
            String nd = printer.printNdjson(messages);
            assertEquals("{\"id\":9223372036854775807,\"name\":\"a\\nb\"}\n{}\n{\"id\":-1,\"values\":[9007199254740993]}\n", nd);
            String[] lines = nd.split("\n", -1);
            assertEquals(messages.size() + 1, lines.length); // 最後は空文字(末尾の \n の後)
            for (int i = 0; i < messages.size(); i++) {
                assertEquals(printer.print(messages.get(i)), lines[i]);
                MAPPER.readTree(lines[i]); // 各行が単独でパースできる
            }
            assertEquals("", printer.printNdjson(List.of()));
        }

        @Test
        @DisplayName("NdjsonWriter(OutputStream)は printNdjson と同じ内容を UTF-8 で書き、close で出力先も閉じる")
        void ndjsonWriter() throws IOException {
            CloseTrackingOutputStream out = new CloseTrackingOutputStream();
            try (NdjsonWriter w = printer.ndjsonWriter(out)) {
                for (Nested m : messages) {
                    w.write(m);
                    w.flush();
                }
            }
            assertEquals(printer.printNdjson(messages), out.toString(StandardCharsets.UTF_8));
            assertTrue(out.closed);
        }
    }

    /** close されたかを記録する OutputStream。 */
    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
