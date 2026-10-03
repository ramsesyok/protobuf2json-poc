package io.github.ramsesyok.protojson.examples;

import demo.proto.GetSimLogRequest;
import demo.proto.ObjectLog;
import demo.proto.SimServiceGrpc;
import io.github.ramsesyok.protojson.NdjsonWriter;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 例 2: server streaming で ObjectLog を 1 レコードずつ受信し、受信するたびに NDJSON の 1 行として書き出す。
 *
 * <p>全件をメモリに溜めずに流せるので、件数が多い場合や HTTP のストリーミング応答に向いている。
 * 各行は 1 レコード分の JSON で、行末は {@code \n}。文字列中の改行はエスケープされるので行は割れない。
 *
 * <pre>
 * 実行(src/go のサーバを起動しておく):
 *   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.StreamingNdjsonExample \
 *       -Dexec.args="localhost:50051 3 3 -"
 * 引数: [接続先=localhost:50051] [ObjectLog 件数=3] [1 件あたりの Event 数=3] [出力先ファイル。"-" は標準出力=-]
 * </pre>
 */
public final class StreamingNdjsonExample {

    private StreamingNdjsonExample() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String target = args.length > 0 ? args[0] : "localhost:50051";
        int objectCount = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int eventsPerObject = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String output = args.length > 3 ? args[3] : "-";

        ProtoJsonPrinter printer = ProtoJsonPrinter.create();

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        try (OutputStream out = output.equals("-") ? notClosing(System.out) : Files.newOutputStream(Path.of(output))) {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);
            GetSimLogRequest request = GetSimLogRequest.newBuilder()
                    .setObjectCount(objectCount)
                    .setEventsPerObject(eventsPerObject)
                    .build();

            long lines = streamToNdjson(stub, request, printer, out);
            System.err.println(lines + " records written to " + (output.equals("-") ? "stdout" : output));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * StreamObjectLogs で受信した ObjectLog を 1 件ずつ NDJSON の 1 行として out に書き出す。
     * 書き終えたら out を閉じる({@link NdjsonWriter#close()} が出力先も閉じるため)。
     *
     * @return 書き出したレコード(行)数
     */
    public static long streamToNdjson(SimServiceGrpc.SimServiceBlockingStub stub, GetSimLogRequest request,
                                      ProtoJsonPrinter printer, OutputStream out) throws IOException {
        long lines = 0;
        try (NdjsonWriter writer = printer.ndjsonWriter(out)) {
            Iterator<ObjectLog> it = stub.streamObjectLogs(request); // 1 件ずつ受信する
            while (it.hasNext()) {
                writer.write(it.next()); // 1 レコード → 1 行
                writer.flush();          // 受け手に逐次届けたい場合(HTTP 応答等)は 1 件ごとに flush する
                lines++;
            }
        }
        return lines;
    }

    /** close されても下の stream を閉じない OutputStream(標準出力を閉じないため)。 */
    private static OutputStream notClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }
}
