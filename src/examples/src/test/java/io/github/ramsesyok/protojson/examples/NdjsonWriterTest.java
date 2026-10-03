// =====================================================================================================
// NdjsonWriterTest: NdjsonWriter(NDJSON を書き出すクラス)が正しく動くかを確かめるテスト
// =====================================================================================================
//
// 【このテストが確かめること】
//   - 1 レコードが 1 行になり、各行の末尾が改行(\n)であること
//   - 2 行目以降の先頭に余計な空白が入らないこと(Jackson の既定の動作を NdjsonWriter が止めているか)
//   - 各行が単独の JSON として読み込めること
//   - 文字列の中の改行・日本語・絵文字が正しく出力されること
//   - close() したときに、書き出し先(ファイル等)も閉じられること
//   - 書き出し先が OutputStream / Writer / JsonGenerator のどれでも、同じ内容になること
//
// 【ExamplesTest との違い】
//   このテストは gRPC サーバを使わない。テスト用のデータをこのファイルの中で作るので、いつでも実行できる。
//
// 【実行方法】
//   cd src/examples && mvn test
// =====================================================================================================

package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonFactory;       // Jackson の JsonGenerator を作る「製造元」
import com.fasterxml.jackson.databind.ObjectMapper;  // Jackson で JSON 文字列を読み込む道具
import demo.proto.CommEvent;                         // テストデータ用: 通信イベント
import demo.proto.Event;                             // テストデータ用: イベント
import demo.proto.ObjectLog;                         // テストデータ用: ログ 1 レコード
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする
import org.junit.jupiter.api.DisplayName;            // テスト結果に表示する名前を付ける印
import org.junit.jupiter.api.Test;                   // 「これはテストメソッドです」という印

import java.io.ByteArrayOutputStream;     // 書き出したバイト列をメモリ上に溜める OutputStream
import java.io.IOException;               // 入出力で起きる例外
import java.io.StringWriter;              // 書き出した文字列をメモリ上に溜める Writer
import java.nio.charset.StandardCharsets; // 文字コードの定数(UTF_8 など)
import java.util.List;                    // リストを表す型

// import static で、assertEquals などを「Assertions.」を付けずに呼べるようにしている
import static org.junit.jupiter.api.Assertions.assertEquals;  // 2 つの値が等しいことを確かめる
import static org.junit.jupiter.api.Assertions.assertFalse;   // 条件が false であることを確かめる
import static org.junit.jupiter.api.Assertions.assertTrue;    // 条件が true であることを確かめる

/** NdjsonWriter(利用例)の確認。サーバ不要。 */
class NdjsonWriterTest {

    /** JSON 文字列を読み込むための道具(Jackson)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** テスト対象の NdjsonWriter が内部で使う JSON 変換器。 */
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();

    /**
     * テストデータ: ObjectLog を 3 件。それぞれ確かめたい特徴を持たせている。
     * <ol>
     *   <li>値がいろいろ入ったレコード: int64 の最大値、2^53+1(JavaScript の数値では正確に表せない値)、
     *       改行・日本語・絵文字を含む文字列</li>
     *   <li>何も設定していない空のレコード(JSON は {} になる)</li>
     *   <li>負の値を持つレコード</li>
     * </ol>
     * List.of(...) は、中身を変更できないリストを作る。
     */
    private final List<ObjectLog> logs = List.of(
            ObjectLog.newBuilder().setTimestamp(Long.MAX_VALUE).setObjectId(1)
                    .addEvents(Event.newBuilder().setEventId(9007199254740993L)
                            .setCommEvent(CommEvent.newBuilder().setBody("a\nb 日本語 😀")))  // \n は改行文字
                    .build(),
            ObjectLog.getDefaultInstance(),               // すべてのフィールドが既定値(0 や空)のレコード
            ObjectLog.newBuilder().setObjectId(-1).build());

    /**
     * 期待する出力を作る: 各レコードを printer.print で JSON にし、それぞれの後ろに改行を付けてつなげたもの。
     * NdjsonWriter の出力は、これと 1 文字も違わないはず。
     */
    private String expected() {
        // StringBuilder は、文字列を少しずつ付け足していくための道具(+ で何度もつなぐより効率がよい)
        StringBuilder sb = new StringBuilder();
        for (ObjectLog log : logs) {
            sb.append(printer.print(log)).append('\n');
        }
        return sb.toString();
    }

    /** 書き出し先が OutputStream の場合(ファイルや HTTP 応答に書くときと同じ形)。 */
    @Test
    @DisplayName("OutputStream: 1 レコード 1 行・行末 \\n・行頭に空白が入らない・close で出力先も閉じる")
    void outputStream() throws Exception {
        // (1) 実行: メモリ上の書き出し先に、3 件を書き出す。
        //     CloseTrackingOutputStream はこのファイルの下で定義した、「close されたか」を記録できる書き出し先。
        CloseTrackingOutputStream out = new CloseTrackingOutputStream();
        try (NdjsonWriter w = new NdjsonWriter(printer, out)) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        } // ← ここで w.close() が自動で呼ばれる(try-with-resources)

        // (2) 確かめる
        String ndjson = out.toString(StandardCharsets.UTF_8);  // 書き出されたバイト列を文字列にする

        // 期待どおり「各レコードの JSON + 改行」が並んでいること
        assertEquals(expected(), ndjson);

        // 実際の文字列も目で見て分かるように、そのまま書いて比べる。
        // Java の文字列の中では、" は \" 、改行文字は \n 、バックスラッシュそのものは \\ と書く。
        // そのため、JSON の中の「\n(バックスラッシュ + n の 2 文字)」は、ここでは \\n と書いている。
        assertEquals("{\"timestamp\":9223372036854775807,\"objectId\":1,\"events\":[{\"eventId\":9007199254740993,"
                + "\"commEvent\":{\"body\":\"a\\nb 日本語 😀\"}}]}\n{}\n{\"objectId\":-1}\n", ndjson);

        // 各行について: 先頭に空白が無いこと、単独で JSON として読み込めること
        for (String line : ndjson.split("\n")) {
            assertFalse(line.startsWith(" "));
            MAPPER.readTree(line); // JSON として正しくなければ、ここで例外が発生してテストが失敗する
        }

        // NdjsonWriter を close したら、書き出し先も閉じられていること
        assertTrue(out.closed);
    }

    /** 書き出し先が Writer の場合と、自分で作った JsonGenerator を渡す場合。 */
    @Test
    @DisplayName("Writer / JsonGenerator を渡す版も同じ内容")
    void writerAndGenerator() throws IOException {
        // Writer(文字の書き出し先)版。StringWriter は書いた文字をメモリに溜める Writer。
        StringWriter writer = new StringWriter();
        try (NdjsonWriter w = new NdjsonWriter(printer, writer)) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        }
        assertEquals(expected(), writer.toString());

        // JsonGenerator 版。自分で JsonFactory から JsonGenerator を作って渡す。
        // (ProtoJsonPrinter を独自の Jackson 設定で作ったときに、同じ設定の generator を渡すための入口)
        StringWriter viaGenerator = new StringWriter();
        try (NdjsonWriter w = new NdjsonWriter(printer, new JsonFactory().createGenerator(viaGenerator))) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        }
        assertEquals(expected(), viaGenerator.toString());
    }

    /** 1 件も書かずに閉じた場合。 */
    @Test
    @DisplayName("1 件も書かなければ空")
    void empty() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NdjsonWriter(printer, out).close();  // 作ってすぐ閉じる
        assertEquals(0, out.size());             // 何も書かれていない(0 バイト)こと
    }

    /**
     * テスト用の書き出し先: close() が呼ばれたかどうかを記録する。
     *
     * <p>ByteArrayOutputStream を継承(extends)し、close() だけを上書き(@Override)して、
     * 呼ばれたら closed を true にする。super.close() は親クラス(ByteArrayOutputStream)の close() を呼ぶ。
     * private static final class は「このテストクラスの中だけで使う、継承されない入れ子のクラス」。
     */
    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        boolean closed; // boolean のフィールドは、何も入れなければ false から始まる

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
