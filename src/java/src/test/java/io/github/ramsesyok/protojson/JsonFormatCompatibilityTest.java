package io.github.ramsesyok.protojson;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.github.ramsesyok.protojson.testproto.AllTypes;
import io.github.ramsesyok.protojson.testproto.Nested;
import io.github.ramsesyok.protojson.testproto.OutOfOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JsonFormat との互換性: 乱数で作った多数のメッセージについて、ProtoJsonPrinter の出力が
 * {@link JsonFormatOracle}(JsonFormat の出力の int64 だけを数値にしたもの)と文字列として完全一致することを確認する。
 */
class JsonFormatCompatibilityTest {

    private static final JsonFormat.TypeRegistry REGISTRY = JsonFormat.TypeRegistry.newBuilder()
            .add(Nested.getDescriptor())
            .build();
    private static final JsonFormatOracle ORACLE = new JsonFormatOracle(REGISTRY);
    private static final ProtoJsonPrinter PRINTER = ProtoJsonPrinter.builder().typeRegistry(REGISTRY).build();

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {1, 2, 3, 4, 5})
    @DisplayName("乱数で生成した AllTypes(各 1,000 件、全型・WKT・oneof・未知の enum・特殊値を含む)で一致")
    void randomAllTypes(long seed) throws Exception {
        RandomMessages random = new RandomMessages(seed, 3);
        for (int i = 0; i < 1_000; i++) {
            Message m = random.next(AllTypes.newBuilder());
            assertSame(m, "seed=" + seed + " i=" + i);

            // 同じデータを DynamicMessage(生成コードを使わない経路。WKT も JsonFormat 経由になる)でも確認
            DynamicMessage dynamic = DynamicMessage.parseFrom(AllTypes.getDescriptor(), m.toByteString());
            assertSame(dynamic, "dynamic seed=" + seed + " i=" + i);
            assertEquals(PRINTER.print(m), PRINTER.print(dynamic));
        }
    }

    @Test
    @DisplayName("乱数で生成した OutOfOrder(宣言順≠フィールド番号順)で一致")
    void randomOutOfOrder() {
        RandomMessages random = new RandomMessages(42, 3);
        for (int i = 0; i < 500; i++) {
            assertSame(random.next(OutOfOrder.newBuilder()), "i=" + i);
        }
    }

    @Test
    @DisplayName("Builder を渡しても一致")
    void builders() {
        RandomMessages random = new RandomMessages(7, 2);
        for (int i = 0; i < 200; i++) {
            AllTypes m = (AllTypes) random.next(AllTypes.newBuilder());
            assertSame(m.toBuilder(), "builder i=" + i);
        }
    }

    private static void assertSame(MessageOrBuilder m, String label) {
        String expected = ORACLE.expected(m);
        String actual = PRINTER.print(m);
        if (!expected.equals(actual)) {
            // 失敗時に原因を追えるよう、JsonFormat の出力そのものも表示する
            assertEquals(expected, actual, label + "\nJsonFormat raw: " + ORACLE.raw(m));
        }
        // OutputStream(UTF-8)へ書いた結果も同じであること
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PRINTER.writeTo(m, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(expected, out.toString(StandardCharsets.UTF_8), label + " (OutputStream)");
    }
}
