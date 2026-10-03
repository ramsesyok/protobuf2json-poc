package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.MessageOrBuilder;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;

/**
 * NDJSON(JSON Lines)の書き出し。1 メッセージを 1 行の JSON にし、行末に {@code \n} を付ける。
 *
 * <p>文字列中の改行は JSON のエスケープ({@code \n})になるため、1 メッセージが複数行に割れることはない。
 * 書き出し先のバッファは Jackson が持つので、こまめに相手へ届けたい場合(HTTP のストリーミング応答等)は
 * {@link #flush()} を呼ぶ。
 *
 * <pre>{@code
 * try (NdjsonWriter w = printer.ndjsonWriter(response.getOutputStream())) {
 *     Iterator<ObjectLog> it = stub.streamObjectLogs(request); // gRPC server streaming
 *     while (it.hasNext()) {
 *         w.write(it.next());
 *     }
 * }
 * }</pre>
 *
 * <p>スレッドセーフではない。{@link #close()} で書き出し先も閉じる。
 * {@link ProtoJsonPrinter#ndjsonWriter} で作る。
 */
public final class NdjsonWriter implements Closeable, Flushable {

    private final ProtoJsonPrinter printer;
    private final JsonGenerator generator;

    NdjsonWriter(ProtoJsonPrinter printer, JsonGenerator generator) {
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
