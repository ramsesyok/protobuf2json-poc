package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.MessageOrBuilder;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

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
 * <pre>{@code
 * try (NdjsonWriter w = new NdjsonWriter(printer, outputStream)) {
 *     Iterator<ObjectLog> it = stub.streamObjectLogs(request); // gRPC server streaming
 *     while (it.hasNext()) {
 *         w.write(it.next());
 *     }
 * }
 * }</pre>
 *
 * <p>スレッドセーフではない。{@link #close()} で書き出し先も閉じる。
 */
public final class NdjsonWriter implements Closeable, Flushable {

    private final ProtoJsonPrinter printer;
    private final JsonGenerator generator;

    /** UTF-8 で out に書き出す。Jackson の設定はライブラリの既定({@code ProtoJsonPrinter.Builder.defaultJsonFactory()})。 */
    public NdjsonWriter(ProtoJsonPrinter printer, OutputStream out) throws IOException {
        this(printer, ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out, JsonEncoding.UTF8));
    }

    /** out に書き出す。Jackson の設定はライブラリの既定。 */
    public NdjsonWriter(ProtoJsonPrinter printer, Writer out) throws IOException {
        this(printer, ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out));
    }

    /**
     * 渡した JsonGenerator に書き出す。printer を独自の JsonFactory で作った場合は、同じ JsonFactory から
     * 作った generator を渡すと設定が揃う。{@link #close()} で generator も閉じる。
     */
    public NdjsonWriter(ProtoJsonPrinter printer, JsonGenerator generator) {
        this.printer = printer;
        this.generator = generator;
        // Jackson は既定でトップレベルの値どうしの間に空白 1 文字を入れるので無効にする(区切りは自前の \n)
        this.generator.setRootValueSeparator(null);
    }

    /** message を 1 行(末尾 {@code \n} 付き)書き出す。 */
    public void write(MessageOrBuilder message) throws IOException {
        printer.writeTo(message, generator);
        generator.writeRaw('\n');
    }

    @Override
    public void flush() throws IOException {
        generator.flush();
    }

    /** バッファを書き出し、書き出し先も閉じる。 */
    @Override
    public void close() throws IOException {
        generator.close();
    }
}
