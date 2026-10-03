// =====================================================================================================
// NdjsonWriter: NDJSON(1 行に 1 つの JSON)を書き出すクラス(利用例)
// =====================================================================================================
//
// 【NDJSON とは】
//   "Newline Delimited JSON" の略。1 行に 1 つの JSON を並べたテキスト形式(JSON Lines とも呼ばれる)。
//     {"timestamp":1700000000000,...}\n
//     {"timestamp":1700000000001,...}\n
//
// 【このクラスの位置づけ】
//   ライブラリ(ProtoJsonPrinter)には NDJSON の機能を入れていない。NDJSON が必要な人は、このクラスを
//   コピーして使う想定。ライブラリの公開メソッド printer.writeTo(message, generator) だけを使って作っているので、
//   ライブラリの内部に依存していない。NDJSON が不要なら、このファイルは使わなくてよい(消してもライブラリは動く)。
//
// 【使い方】
//   try (NdjsonWriter w = new NdjsonWriter(printer, outputStream)) {
//       for (ObjectLog log : logs) {
//           w.write(log);   // 1 件 → 1 行
//       }
//   }                       // try を抜けると自動で close され、残りのデータも書き出される
// =====================================================================================================

package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonEncoding;   // JSON を書くときの文字コード(UTF-8 など)
import com.fasterxml.jackson.core.JsonGenerator;  // Jackson の「JSON を少しずつ書き出す」道具
import com.google.protobuf.MessageOrBuilder;      // すべての Protobuf メッセージに共通の型
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする

import java.io.Closeable;   // close() を持つ型(try-with-resources で自動的に閉じられるようになる)
import java.io.Flushable;   // flush() を持つ型
import java.io.IOException; // 入出力で起きる例外
import java.io.OutputStream; // バイト列の書き出し先(ファイル・通信など)
import java.io.Writer;      // 文字列の書き出し先

/**
 * NDJSON(JSON Lines)の書き出し: 1 メッセージを 1 行の JSON にし、行末に {@code \n} を付ける(利用例)。
 *
 * <p>ライブラリ({@link ProtoJsonPrinter})の公開 API {@link ProtoJsonPrinter#writeTo(MessageOrBuilder, JsonGenerator)}
 * だけを使って作っている。ライブラリ側はこのクラスを知らないので、NDJSON が不要ならこのファイルごと使わなくてよい。
 *
 * <p>1 つの JsonGenerator を使い続けるので、メッセージごとに出力先を flush しない(大量に書く場合に効率がよい)。
 * 受け手に逐次届けたい場合(HTTP のストリーミング応答等)は {@link #flush()} を呼ぶ。
 * 文字列中の改行は JSON のエスケープ({@code \n})になるため、1 メッセージが複数行に割れることはない。
 *
 * <p>スレッドセーフではない(複数のスレッドから同時に write してはいけない)。{@link #close()} で書き出し先も閉じる。
 */
// implements Closeable, Flushable:
//   Closeable を実装すると try-with-resources 文(try (NdjsonWriter w = ...) { })で使えるようになり、
//   { } を抜けるときに close() が自動で呼ばれる。Flushable は flush() を持つことを表す。
public final class NdjsonWriter implements Closeable, Flushable {

    // private final: このクラスの中からだけ使え、コンストラクタで一度設定したら変更できないフィールド
    private final ProtoJsonPrinter printer;   // JSON 変換器(ライブラリ)
    private final JsonGenerator generator;    // 実際に JSON の文字を書き出す道具(Jackson)

    // コンストラクタは 3 種類ある(引数の型が違う同名のメソッドを複数持つことを「オーバーロード」という)。
    // 出力先が OutputStream(バイト列)か、Writer(文字列)か、自分で用意した JsonGenerator か、で使い分ける。

    /**
     * UTF-8 で out(ファイル・HTTP 応答など)に書き出す。Jackson の設定はライブラリの既定と同じ。
     *
     * @param printer JSON 変換器
     * @param out     書き出し先
     * @throws IOException JsonGenerator の作成に失敗した場合
     */
    public NdjsonWriter(ProtoJsonPrinter printer, OutputStream out) throws IOException {
        // this(...) は「同じクラスの別のコンストラクタを呼ぶ」書き方。
        // ここでは out から JsonGenerator を作り、下の 3 つ目のコンストラクタに渡している。
        this(printer, ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out, JsonEncoding.UTF8));
    }

    /**
     * out(StringWriter など文字の書き出し先)に書き出す。Jackson の設定はライブラリの既定と同じ。
     *
     * @param printer JSON 変換器
     * @param out     書き出し先
     * @throws IOException JsonGenerator の作成に失敗した場合
     */
    public NdjsonWriter(ProtoJsonPrinter printer, Writer out) throws IOException {
        this(printer, ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out));
    }

    /**
     * 自分で用意した JsonGenerator に書き出す。printer を独自の JsonFactory で作った場合は、同じ JsonFactory から
     * 作った generator を渡すと設定が揃う。{@link #close()} で generator も閉じる。
     *
     * @param printer   JSON 変換器
     * @param generator 書き出しに使う JsonGenerator
     */
    public NdjsonWriter(ProtoJsonPrinter printer, JsonGenerator generator) {
        this.printer = printer;      // this.printer はフィールド、右辺の printer は引数
        this.generator = generator;

        // Jackson は、JSON の値を続けて書くと、値と値の間に空白を 1 文字入れる(既定の動作)。
        // NDJSON では区切りは改行(\n)だけにしたいので、この空白を入れないように設定する。
        // (これを忘れると、2 行目以降の先頭に空白が入ってしまう)
        this.generator.setRootValueSeparator(null);
    }

    /**
     * message を 1 行(末尾 {@code \n} 付き)書き出す。
     *
     * @param message 書き出す Protobuf メッセージ(ObjectLog など)
     * @throws IOException 書き出しに失敗した場合
     */
    public void write(MessageOrBuilder message) throws IOException {
        printer.writeTo(message, generator);  // ライブラリに、message の JSON(1 行)を書いてもらう
        generator.writeRaw('\n');             // 行の終わりに改行を書く(writeRaw は文字をそのまま書く)
    }

    /**
     * まだ書き出していないデータ(バッファに溜まっている分)を、今すぐ書き出し先へ送る。
     *
     * <p>Jackson は効率のため、書いた内容を一度メモリ(バッファ)に溜めてからまとめて送る。
     * 受け手に 1 件ずつすぐ届けたい場合(HTTP のストリーミング応答等)は、write の後にこれを呼ぶ。
     */
    @Override // @Override は「親(ここでは Flushable)のメソッドを実装している」ことを示す印
    public void flush() throws IOException {
        generator.flush();
    }

    /**
     * 残りのデータを書き出し、書き出し先(ファイルなど)も閉じる。
     * try-with-resources 文で使えば、自動で呼ばれる。
     */
    @Override
    public void close() throws IOException {
        generator.close();
    }
}
