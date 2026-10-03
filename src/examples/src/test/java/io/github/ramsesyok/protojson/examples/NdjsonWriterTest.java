package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ObjectLog;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NdjsonWriter(利用例)の確認。サーバ不要。 */
class NdjsonWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();

    private final List<ObjectLog> logs = List.of(
            ObjectLog.newBuilder().setTimestamp(Long.MAX_VALUE).setObjectId(1)
                    .addEvents(Event.newBuilder().setEventId(9007199254740993L)
                            .setCommEvent(CommEvent.newBuilder().setBody("a\nb 日本語 😀")))
                    .build(),
            ObjectLog.getDefaultInstance(),
            ObjectLog.newBuilder().setObjectId(-1).build());

    /** 期待値: 各レコードを print した結果を \n で連結し、末尾にも \n。 */
    private String expected() {
        StringBuilder sb = new StringBuilder();
        for (ObjectLog log : logs) {
            sb.append(printer.print(log)).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("OutputStream: 1 レコード 1 行・行末 \\n・行頭に空白が入らない・close で出力先も閉じる")
    void outputStream() throws Exception {
        CloseTrackingOutputStream out = new CloseTrackingOutputStream();
        try (NdjsonWriter w = new NdjsonWriter(printer, out)) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        }
        String ndjson = out.toString(StandardCharsets.UTF_8);
        assertEquals(expected(), ndjson);
        assertEquals("{\"timestamp\":9223372036854775807,\"objectId\":1,\"events\":[{\"eventId\":9007199254740993,"
                + "\"commEvent\":{\"body\":\"a\\nb 日本語 😀\"}}]}\n{}\n{\"objectId\":-1}\n", ndjson);
        for (String line : ndjson.split("\n")) {
            assertFalse(line.startsWith(" "));
            MAPPER.readTree(line); // 各行が単独でパースできる
        }
        assertTrue(out.closed);
    }

    @Test
    @DisplayName("Writer / JsonGenerator を渡す版も同じ内容")
    void writerAndGenerator() throws IOException {
        StringWriter writer = new StringWriter();
        try (NdjsonWriter w = new NdjsonWriter(printer, writer)) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        }
        assertEquals(expected(), writer.toString());

        StringWriter viaGenerator = new StringWriter();
        try (NdjsonWriter w = new NdjsonWriter(printer, new JsonFactory().createGenerator(viaGenerator))) {
            for (ObjectLog log : logs) {
                w.write(log);
            }
        }
        assertEquals(expected(), viaGenerator.toString());
    }

    @Test
    @DisplayName("1 件も書かなければ空")
    void empty() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NdjsonWriter(printer, out).close();
        assertEquals(0, out.size());
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
