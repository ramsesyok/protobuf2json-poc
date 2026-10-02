package demo.json;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ObjectLog;
import demo.proto.Result;
import demo.proto.SimLog;
import demo.proto.ordertest.Inner;
import demo.proto.ordertest.OutOfOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ストリーミング版({@link StreamingInt64JsonConverter})と 1 パス版({@link DirectInt64JsonWriter})の出力が、
 * 基準実装 {@link Int64JsonConverter}(JsonNode ツリー方式)と文字列として完全一致することを確認する。
 */
class AlternativeConvertersTest {

    /** テスト対象の共通インタフェース。 */
    record Impl(String name, ToJson toJson, ToNdjson toNdjson, WriteNdjson writeNdjson) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface ToJson {
        String apply(MessageOrBuilder m);
    }

    @FunctionalInterface
    interface ToNdjson {
        String apply(Iterable<? extends MessageOrBuilder> ms);
    }

    @FunctionalInterface
    interface WriteNdjson {
        void apply(Iterable<? extends MessageOrBuilder> ms, Writer w);
    }

    private static final Int64JsonConverter REFERENCE = new Int64JsonConverter();

    static Stream<Impl> impls() {
        StreamingInt64JsonConverter streaming = new StreamingInt64JsonConverter();
        DirectInt64JsonWriter direct = new DirectInt64JsonWriter();
        return Stream.of(
                new Impl("Streaming", streaming::toJson, streaming::toNdjson, streaming::writeNdjson),
                new Impl("Direct", direct::toJson, direct::toNdjson, direct::writeNdjson));
    }

    /** simlog.proto のメッセージ(境界値・0・未設定 oneof / optional・特殊文字を含む)。 */
    static List<MessageOrBuilder> simlogMessages() {
        List<MessageOrBuilder> list = new ArrayList<>(List.of(
                TestData.simLog(true),
                TestData.simLog(false),
                TestData.objectLog1(),
                TestData.objectLog2(),
                TestData.commEvent(),
                TestData.execEvent(),
                TestData.noPayloadEvent(),
                TestData.trickyStringEvent(),
                TestData.result(),
                TestData.simLog(true).toBuilder(), // Builder
                SimLog.getDefaultInstance(),
                Event.getDefaultInstance(),
                SimLog.newBuilder().setResult(Result.getDefaultInstance()).build(),
                ObjectLog.newBuilder().addEvents(Event.newBuilder().setCommEvent(CommEvent.getDefaultInstance())).build(),
                CommEvent.newBuilder().setFromId(1).setBody("<tag> & a=b 'q' \u0001 \t \\ /  ").build()));
        for (long v : new long[]{0, 1, -1, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE, -9007199254740993L}) {
            list.add(SimLog.newBuilder().setJobId(v).build());
        }
        return list;
    }

    static Stream<Object[]> implAndMessage() {
        return impls().flatMap(impl -> simlogMessages().stream().map(m -> new Object[]{impl, m}));
    }

    @ParameterizedTest(name = "{0}: {index}")
    @MethodSource("implAndMessage")
    @DisplayName("simlog.proto の各メッセージで基準実装と文字列一致")
    void sameAsReference(Impl impl, MessageOrBuilder m) {
        assertEquals(REFERENCE.toJson(m), impl.toJson().apply(m));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impls")
    @DisplayName("NDJSON(toNdjson / writeNdjson)が基準実装と一致")
    void ndjsonSameAsReference(Impl impl) {
        List<ObjectLog> logs = TestData.simLog(false).getLogsList();
        String expected = REFERENCE.toNdjson(logs);
        assertEquals(expected, impl.toNdjson().apply(logs));
        StringWriter w = new StringWriter();
        impl.writeNdjson().apply(logs, w);
        assertEquals(expected, w.toString());
        assertEquals("", impl.toNdjson().apply(List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impls")
    @DisplayName("bool / bytes / enum / uint32 / float / double / Timestamp / StringValue を含む DynamicMessage で一致")
    void scalarsSameAsReference(Impl impl) {
        for (DynamicMessage m : DynamicFixtures.scalars()) {
            assertEquals(REFERENCE.toJson(m), impl.toJson().apply(m), () -> REFERENCE.toRawJson(m));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impls")
    @DisplayName("repeated int64 / sint64 / sfixed64 / uint64 / fixed64 / Int64Value(map なし)で一致")
    void extraWithoutMapSameAsReference(Impl impl) {
        DynamicMessage m = DynamicFixtures.extra(false);
        assertEquals(REFERENCE.toJson(m), impl.toJson().apply(m));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impls")
    @DisplayName("宣言順とフィールド番号順が異なるメッセージ(生成コード / DynamicMessage)でキー順が一致")
    void fieldOrderSameAsReference(Impl impl) throws Exception {
        OutOfOrder generated = OutOfOrder.newBuilder()
                .setZ(5).setName("n").setA(1).addIds(2).addIds(-3)
                .setInner(Inner.newBuilder().setS("s").setX(Long.MAX_VALUE))
                .build();
        String expected = "{\"a\":1,\"ids\":[2,-3],\"name\":\"n\",\"inner\":{\"x\":9223372036854775807,\"s\":\"s\"},\"z\":5}";
        assertEquals(expected, REFERENCE.toJson(generated));
        assertEquals(expected, impl.toJson().apply(generated));

        DynamicMessage dynamic = DynamicMessage.parseFrom(OutOfOrder.getDescriptor(), generated.toByteString());
        assertEquals(expected, REFERENCE.toJson(dynamic));
        assertEquals(expected, impl.toJson().apply(dynamic));
    }

    static List<MessageOrBuilder> wellKnownTypes() {
        return List.of(
                Timestamp.getDefaultInstance(),
                Timestamp.newBuilder().setSeconds(1700000000L).build(),
                Timestamp.newBuilder().setSeconds(1700000000L).setNanos(123000000).build(),
                Timestamp.newBuilder().setSeconds(1700000000L).setNanos(123456000).build(),
                Timestamp.newBuilder().setSeconds(1700000000L).setNanos(123456789).build(),
                Timestamp.newBuilder().setSeconds(-62135596800L).build(), // 0001-01-01T00:00:00Z
                Timestamp.newBuilder().setSeconds(-1L).setNanos(999999999).build(),
                Timestamp.newBuilder().setSeconds(253402300799L).setNanos(999999999).build(), // 9999-12-31
                Duration.getDefaultInstance(),
                Duration.newBuilder().setSeconds(3).setNanos(500000000).build(),
                Duration.newBuilder().setSeconds(-3).setNanos(-1).build(),
                Duration.newBuilder().setSeconds(315576000000L).build(),
                Int64Value.of(Long.MIN_VALUE), Int64Value.of(0), Int64Value.of(9007199254740993L),
                UInt64Value.of(-1L), UInt64Value.of(5),
                Int32Value.of(Integer.MIN_VALUE), Int32Value.of(0),
                UInt32Value.of(-1), UInt32Value.of(7),
                BoolValue.of(true), BoolValue.of(false),
                StringValue.of("<tag> & \"q\" 日本\n"), StringValue.of(""),
                BytesValue.of(ByteString.copyFrom(new byte[]{(byte) 0xfb, (byte) 0xff, 0x00})), BytesValue.of(ByteString.EMPTY),
                FloatValue.of(Float.NaN), FloatValue.of(0.1f), FloatValue.of(Float.NEGATIVE_INFINITY), FloatValue.of(-0.0f),
                DoubleValue.of(Double.POSITIVE_INFINITY), DoubleValue.of(1e21), DoubleValue.of(-0.0), DoubleValue.of(5e-324),
                Timestamp.newBuilder().setSeconds(1700000000L).setNanos(5), // Builder
                Duration.newBuilder().setSeconds(1)); // Builder → 高速パス対象外(JsonFormat 経由)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impls")
    @DisplayName("Well-Known Types(Timestamp / Duration / 全ラッパー型)単体で基準実装と一致")
    void wellKnownTypesSameAsReference(Impl impl) {
        for (MessageOrBuilder m : wellKnownTypes()) {
            assertEquals(REFERENCE.toJson(m), impl.toJson().apply(m), () -> REFERENCE.toRawJson(m));
        }
    }

    @Test
    @DisplayName("Streaming: map を含んでも基準実装と一致(map は文字列のまま)")
    void streamingSupportsMap() {
        DynamicMessage m = DynamicFixtures.extra(true);
        assertEquals(REFERENCE.toJson(m), new StreamingInt64JsonConverter().toJson(m));
    }

    @Test
    @DisplayName("Direct: map は未対応(値があれば UnsupportedOperationException)")
    void directRejectsMap() {
        assertThrows(UnsupportedOperationException.class,
                () -> new DirectInt64JsonWriter().toJson(DynamicFixtures.extra(true)));
    }

    @Test
    @DisplayName("Streaming: preservingProtoFieldNames / alwaysPrintFieldsWithNoPresence の Printer でも一致")
    void streamingHonorsPrinterOptions() {
        List<JsonFormat.Printer> printers = List.of(
                JsonFormat.printer().omittingInsignificantWhitespace().preservingProtoFieldNames(),
                JsonFormat.printer().omittingInsignificantWhitespace().alwaysPrintFieldsWithNoPresence(),
                JsonFormat.printer()); // 整形あり(空白・改行を含む)
        for (JsonFormat.Printer printer : printers) {
            Int64JsonConverter ref = new Int64JsonConverter(printer);
            StreamingInt64JsonConverter streaming = new StreamingInt64JsonConverter(printer);
            for (MessageOrBuilder m : simlogMessages()) {
                assertEquals(ref.toJson(m), streaming.toJson(m));
            }
        }
    }
}
