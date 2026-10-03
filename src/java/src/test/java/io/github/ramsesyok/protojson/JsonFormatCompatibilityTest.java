// =====================================================================================================
// JsonFormatCompatibilityTest: Protobuf 公式の JsonFormat と同じ規則で出力できているかを、大量のデータで確かめるテスト
// =====================================================================================================
//
// 【なぜこのテストが重要か】
//   本ライブラリは速さのため、Protobuf 公式の JsonFormat を使わずに、JSON の出力規則(キー名・順序・省略・値の表現など)を
//   自前で再現している。そのため、JsonFormat と少しでも違う出力をしていないかを確かめ続ける必要がある。
//   特に protobuf-java や Jackson のバージョンを上げたときは、まずこのテストを実行すること。
//
// 【確かめ方】
//   1. RandomMessages で、乱数で値を入れたメッセージを大量に(合計 1 万件以上)作る
//   2. それぞれについて、
//        正解 = JsonFormatOracle で作った「JsonFormat の出力の int64 だけを数値にしたもの」
//        実際 = ProtoJsonPrinter の出力
//      を比べ、1 文字も違わないことを確かめる
//   3. 同じデータを DynamicMessage(生成コードを使わない形)や Builder にしても、同じ出力になることも確かめる
//
// 【失敗したとき】
//   失敗メッセージに、どのデータ(seed=シード i=何件目)で違ったかと、JsonFormat の出力そのもの(JsonFormat raw)が表示される。
//   同じシードで実行すれば同じデータが作られるので、何度でも再現できる。
// =====================================================================================================

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

    /**
     * Any の中身の型を登録した登録簿。RandomMessages は Any に Nested を詰めるので、Nested を登録している。
     * 正解を作る側(ORACLE)とテスト対象(PRINTER)の両方に同じ登録簿を渡す。
     */
    private static final JsonFormat.TypeRegistry REGISTRY = JsonFormat.TypeRegistry.newBuilder()
            .add(Nested.getDescriptor())
            .build();
    /** 正解を作る道具。 */
    private static final JsonFormatOracle ORACLE = new JsonFormatOracle(REGISTRY);
    /** テスト対象。 */
    private static final ProtoJsonPrinter PRINTER = ProtoJsonPrinter.builder().typeRegistry(REGISTRY).build();

    /**
     * 全フィールド型を持つ AllTypes を乱数で作って比べる。シード 1〜5 のそれぞれで 1,000 件ずつ(合計 5,000 件)。
     * @ParameterizedTest + @ValueSource で、シードを変えて同じテストを 5 回実行している。
     */
    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {1, 2, 3, 4, 5})
    @DisplayName("乱数で生成した AllTypes(各 1,000 件、全型・WKT・oneof・未知の enum・特殊値を含む)で一致")
    void randomAllTypes(long seed) throws Exception {
        // 入れ子の深さは最大 3。1_000 の _ は読みやすくするための区切りで、1000 と同じ意味
        RandomMessages random = new RandomMessages(seed, 3);
        for (int i = 0; i < 1_000; i++) {
            Message m = random.next(AllTypes.newBuilder());
            // 生成コードのメッセージで比べる(第 2 引数は、失敗したときに表示する「どのデータか」の目印)
            assertSame(m, "seed=" + seed + " i=" + i);

            // 同じデータを DynamicMessage(生成コードを使わない経路。WKT も JsonFormat 経由になる)でも確認
            DynamicMessage dynamic = DynamicMessage.parseFrom(AllTypes.getDescriptor(), m.toByteString());
            assertSame(dynamic, "dynamic seed=" + seed + " i=" + i);
            // 生成コード版と DynamicMessage 版で、ProtoJsonPrinter の出力が同じであること
            assertEquals(PRINTER.print(m), PRINTER.print(dynamic));
        }
    }

    /** 宣言順とフィールド番号順が違うメッセージ(OutOfOrder)で、キーの並び順まで正しいかを 500 件で確かめる。 */
    @Test
    @DisplayName("乱数で生成した OutOfOrder(宣言順≠フィールド番号順)で一致")
    void randomOutOfOrder() {
        RandomMessages random = new RandomMessages(42, 3);
        for (int i = 0; i < 500; i++) {
            assertSame(random.next(OutOfOrder.newBuilder()), "i=" + i);
        }
    }

    /** 完成したメッセージではなく Builder(組み立て途中のもの)を渡しても、正しく出力できるかを 200 件で確かめる。 */
    @Test
    @DisplayName("Builder を渡しても一致")
    void builders() {
        RandomMessages random = new RandomMessages(7, 2);
        for (int i = 0; i < 200; i++) {
            AllTypes m = (AllTypes) random.next(AllTypes.newBuilder());
            assertSame(m.toBuilder(), "builder i=" + i);
        }
    }

    /**
     * 1 件のメッセージについて、ProtoJsonPrinter の出力が正解と完全に同じことを確かめる(このクラスの中だけで使う道具)。
     * print(文字列で受け取る)と writeTo(OutputStream に UTF-8 で書く)の両方の出力を確かめる。
     *
     * @param m     確かめるメッセージ
     * @param label 失敗したときに表示する目印(どのデータか)
     */
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
