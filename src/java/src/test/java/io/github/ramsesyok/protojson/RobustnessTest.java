// =====================================================================================================
// RobustnessTest: 異常なデータや異常な状況でも、安全に・決められたとおりに動くかを確かめるテスト
// =====================================================================================================
//
// 【堅牢性(ロバストネス)とは】
//   想定外の入力や状況(極端に深い入れ子、巨大な文字列、null、書き込みの失敗など)でも、
//   クラッシュしたり、メモリを使い続けたり、誤ったデータを出したりせず、決められた例外で知らせる性質。
//   製品に組み込むときは、正常系(普通のデータ)だけでなく、こうした異常系の動きも確かめておく必要がある。
//
// 【このテストが確かめること】
//   - 入れ子が深すぎるメッセージ: スタックオーバーフローにならず、IllegalArgumentException になる
//   - 途中で失敗したとき: 出力先に残った途中までの JSON が「正しい JSON」に見えない(閉じ括弧を補わない)
//   - Struct / Any の中の巨大な文字列・長いキー: Jackson の読み込み上限に引っかからず、正しく出力できる
//   - キャッシュ: printer ごとに独立していて上限があり、printer を捨てれば型の情報も解放される(メモリリークしない)
//   - null の引数: どの引数が null かが分かる NullPointerException になる
//   - 書き込みの失敗: IOException がそのまま伝わり、利用者の出力先を勝手に閉じない
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonFactory;                     // JsonGenerator を作る製造元
import com.fasterxml.jackson.core.JsonGenerator;                   // JSON を少しずつ書き出す道具
import com.fasterxml.jackson.core.exc.StreamConstraintsException;  // Jackson の上限を超えたときの例外
import com.fasterxml.jackson.databind.ObjectMapper;                // JSON を読み込む道具
import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;       // メッセージ型の定義(実行時に型を作るために使う)
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;  // フィールドの定義
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;   // .proto ファイル 1 つ分の定義
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;                         // 生成コードを使わずに扱うメッセージ
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.github.ramsesyok.protojson.testproto.AllTypes;
import io.github.ramsesyok.protojson.testproto.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;       // 「GC(ゴミ集め)で回収されたか」を調べるための弱い参照
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 異常系(深すぎる入れ子・途中の失敗・巨大な値・キャッシュ・null・I/O 失敗)の振る舞い。 */
class RobustnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();

    // ---------------------------------------------------------------------------------------------
    // 入れ子の深さ
    // ---------------------------------------------------------------------------------------------

    /** depth 段の入れ子になった Nested を作る(child の中に child の中に…)。 */
    private static Nested nestedChain(int depth) {
        Nested n = Nested.newBuilder().setId(1).build();
        for (int i = 0; i < depth; i++) {
            n = Nested.newBuilder().setChild(n).build();
        }
        return n;
    }

    @Test
    @DisplayName("入れ子が深すぎる(1000 段以上)と、スタックオーバーフローではなく IllegalArgumentException")
    void tooDeepNesting() throws Exception {
        // 999 段までは出力できる(Jackson の入れ子の上限 1000 の範囲内)
        MAPPER.readTree(printer.print(nestedChain(999)));

        // 1000 段以上は、どの出力方法でも IllegalArgumentException(原因は Jackson の StreamConstraintsException)。
        // IOException(書き込みの失敗)とは区別される
        Nested tooDeep = nestedChain(5000);
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> printer.print(tooDeep));
        assertInstanceOf(StreamConstraintsException.class, e1.getCause());
        assertThrows(IllegalArgumentException.class, () -> printer.writeTo(tooDeep, new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class, () -> printer.writeTo(tooDeep, new StringWriter()));
    }

    // ---------------------------------------------------------------------------------------------
    // 途中で失敗したときの出力
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("途中で失敗したとき、出力先に残った部分は正しい JSON にならない(閉じ括弧を補わない)")
    void partialOutputIsNeverValidJson() {
        // 2 つ目の Timestamp が範囲外(9999 年より後)なので、そこで失敗する。
        // Jackson の既定の動作のままだと、失敗後に閉じ括弧が補われて {"rTimestamp":["1970-01-01T00:00:00Z"]} という
        // 「正しい JSON」が残り、受け取った側が完全なデータと誤認する恐れがある。
        AllTypes failsInTheMiddle = AllTypes.newBuilder()
                .addRTimestamp(Timestamp.newBuilder().setSeconds(0))
                .addRTimestamp(Timestamp.newBuilder().setSeconds(253402300800L))
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> printer.writeTo(failsInTheMiddle, out));
        String partial = out.toString(StandardCharsets.UTF_8);
        assertEquals("{\"rTimestamp\":[\"1970-01-01T00:00:00Z\"", partial);   // 閉じ括弧が無い
        // JSON として読み込もうとすると失敗する(= 不完全だと分かる)
        assertThrows(Exception.class, () -> MAPPER.readTree(partial));

        // Writer 版でも同じ
        StringWriter writer = new StringWriter();
        assertThrows(IllegalArgumentException.class, () -> printer.writeTo(failsInTheMiddle, writer));
        assertThrows(Exception.class, () -> MAPPER.readTree(writer.toString()));

        // 入れ子が深すぎて失敗した場合も同じ
        ByteArrayOutputStream deep = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> printer.writeTo(nestedChain(2000), deep));
        assertThrows(Exception.class, () -> MAPPER.readTree(deep.toString(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------------------------------------
    // 巨大な値(JsonFormat 経由で出力する Struct / Any)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Struct / Any の中の 2000 万文字を超える文字列や 5 万文字を超えるキーも出力できる")
    void hugeValuesInsideFallbackTypes() {
        // Jackson は JSON を読むとき、既定で文字列 2000 万文字・キー 5 万文字までに制限している。
        // Struct や Any は JsonFormat で作った JSON を読み直して出力するので、その制限に引っかからないことを確かめる
        String huge = "x".repeat(20_000_001);
        String longKey = "k".repeat(60_000);

        String structJson = printer.print(AllTypes.newBuilder()
                .setWStruct(Struct.newBuilder()
                        .putFields("big", Value.newBuilder().setStringValue(huge).build())
                        .putFields(longKey, Value.newBuilder().setBoolValue(true).build()))
                .build());
        assertTrue(structJson.contains("\"big\":\"" + huge + "\""));
        assertTrue(structJson.contains("\"" + longKey + "\":true"));

        ProtoJsonPrinter withRegistry = ProtoJsonPrinter.builder()
                .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                .build();
        String anyJson = withRegistry.print(AllTypes.newBuilder()
                .setWAny(Any.pack(Nested.newBuilder().setName(huge).build())).build());
        assertTrue(anyJson.endsWith("\"name\":\"" + huge + "\"}}"));
    }

    // ---------------------------------------------------------------------------------------------
    // キャッシュとメモリ
    // ---------------------------------------------------------------------------------------------

    /** 実行時に新しい message 型(int64 のフィールド id を 1 つ持つ)を作る。生成コードを使わない特殊な使い方の再現。 */
    private static Descriptor newDynamicType(int i) throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("dynamic" + i + ".proto")
                .setPackage("dyn" + i)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Msg")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id").setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Msg");
    }

    @Test
    @DisplayName("キャッシュは上限を超えて増えず、上限を超えた型も正しく出力できる")
    void cacheIsBounded() throws Exception {
        ProtoJsonPrinter small = ProtoJsonPrinter.builder().layoutCacheSize(5).build();
        for (int i = 0; i < 50; i++) {
            Descriptor type = newDynamicType(i);
            DynamicMessage m = DynamicMessage.newBuilder(type).setField(type.findFieldByName("id"), (long) i + 1).build();
            assertEquals("{\"id\":" + (i + 1) + "}", small.print(m));
        }
        assertEquals(5, small.cachedLayoutCount()); // 50 種類の型を出力しても、キャッシュは 5 種類まで
    }

    @Test
    @DisplayName("キャッシュは printer ごとに独立(static ではない)")
    void cacheIsPerInstance() {
        ProtoJsonPrinter first = ProtoJsonPrinter.create();
        ProtoJsonPrinter second = ProtoJsonPrinter.create();
        first.print(AllTypes.newBuilder().setFNested(Nested.newBuilder().setId(1)).build());
        assertEquals(2, first.cachedLayoutCount());   // AllTypes と Nested
        assertEquals(0, second.cachedLayoutCount());  // 別の printer には影響しない
    }

    @Test
    @DisplayName("printer を使い終われば、キャッシュしていた型の情報も GC で解放される(メモリリークしない)")
    void cacheDoesNotLeakTypes() throws Exception {
        // 弱い参照(WeakReference)は、他に誰も使っていなければ GC で回収される参照。回収されると get() が null になる。
        // 実行時に作った型を printer に覚えさせた後、型と printer の両方を手放し、型が回収されることを確かめる
        WeakReference<Descriptor> typeRef = printAndForget();
        for (int i = 0; i < 50 && typeRef.get() != null; i++) {
            System.gc();        // GC を促す(すぐに動くとは限らないので、回収されるまで何回か繰り返す)
            Thread.sleep(20);
        }
        assertNull(typeRef.get(), "printer を手放した後も型の情報が残っている(どこかで参照され続けている)");
    }

    /** 型を作って printer で出力し、その型への弱い参照だけを返す(型も printer もこのメソッドの外には残らない)。 */
    private static WeakReference<Descriptor> printAndForget() throws Exception {
        Descriptor type = newDynamicType(999);
        ProtoJsonPrinter p = ProtoJsonPrinter.create();
        p.print(DynamicMessage.newBuilder(type).setField(type.findFieldByName("id"), 1L).build());
        assertEquals(1, p.cachedLayoutCount());
        return new WeakReference<>(type);
    }

    @Test
    @DisplayName("キャッシュの上限に負の値は指定できない")
    void negativeCacheSizeRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProtoJsonPrinter.builder().layoutCacheSize(-1).build());
    }

    // ---------------------------------------------------------------------------------------------
    // null と I/O の失敗
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("null の引数は、どの引数かが分かる NullPointerException")
    void nullArguments() throws IOException {
        AllTypes m = AllTypes.getDefaultInstance();
        assertEquals("message", assertThrows(NullPointerException.class, () -> printer.print(null)).getMessage());
        assertEquals("out", assertThrows(NullPointerException.class,
                () -> printer.writeTo(m, (OutputStream) null)).getMessage());
        assertEquals("out", assertThrows(NullPointerException.class,
                () -> printer.writeTo(m, (Writer) null)).getMessage());
        assertEquals("generator", assertThrows(NullPointerException.class,
                () -> printer.writeTo(m, (JsonGenerator) null)).getMessage());
        try (JsonGenerator g = new JsonFactory().createGenerator(new StringWriter())) {
            assertEquals("message", assertThrows(NullPointerException.class, () -> printer.writeTo(null, g)).getMessage());
        }
        assertEquals("typeRegistry", assertThrows(NullPointerException.class,
                () -> ProtoJsonPrinter.builder().typeRegistry(null)).getMessage());
        assertEquals("jsonFactory", assertThrows(NullPointerException.class,
                () -> ProtoJsonPrinter.builder().jsonFactory(null)).getMessage());
    }

    @Test
    @DisplayName("書き込みに失敗すると IOException がそのまま伝わり、出力先を勝手に閉じない")
    void ioFailurePropagates() {
        // 書き込もうとすると必ず失敗する出力先(ディスクがいっぱい、通信が切れた、などの再現)
        FailingOutputStream out = new FailingOutputStream();
        IOException e = assertThrows(IOException.class,
                () -> printer.writeTo(AllTypes.newBuilder().setFInt64(1).build(), out));
        assertEquals("disk full", e.getMessage());
        assertFalse(out.closed); // 出力先を閉じるかどうかは利用者が決める
    }

    /** 書き込みが必ず失敗する OutputStream(テスト用)。 */
    private static final class FailingOutputStream extends OutputStream {
        boolean closed;

        @Override
        public void write(int b) throws IOException {
            throw new IOException("disk full");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("disk full");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
