package demo.app;

import demo.json.Int64JsonConverter;
import demo.proto.GetSimLogRequest;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Go の SimService に接続し、3 パターンの JSON を出力ディレクトリに書き出す。
 *
 * <pre>
 * 引数: [target=localhost:50051] [outDir=output] [objectCount=3] [eventsPerObject=3]
 * 出力:
 *   pattern1.json / pattern1.raw.json             SimLog 全体(result なし)
 *   pattern2.json / pattern2.raw.json             SimLog 全体(result あり)
 *   pattern3-stream.ndjson / pattern3-stream.raw.ndjson  StreamObjectLogs で受信した ObjectLog を 1 行ずつ
 *   pattern3-logs.ndjson                          GetSimLog の logs 要素を 1 行ずつ
 * </pre>
 * *.raw.* は比較用の素の JsonFormat 出力(int64 が文字列)。
 */
public final class SimClient {

    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0] : "localhost:50051";
        Path outDir = Path.of(args.length > 1 ? args[1] : "output");
        int objectCount = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int eventsPerObject = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        Files.createDirectories(outDir);

        Int64JsonConverter converter = new Int64JsonConverter();
        ManagedChannel channel = newChannel(target);
        try {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);

            // パターン1: SimLog 全体(result なし)
            SimLog p1 = stub.getSimLog(request(false, objectCount, eventsPerObject));
            write(outDir.resolve("pattern1.json"), converter.toJson(p1) + "\n");
            write(outDir.resolve("pattern1.raw.json"), converter.toRawJson(p1) + "\n");

            // パターン2: SimLog 全体(result あり)
            SimLog p2 = stub.getSimLog(request(true, objectCount, eventsPerObject));
            write(outDir.resolve("pattern2.json"), converter.toJson(p2) + "\n");
            write(outDir.resolve("pattern2.raw.json"), converter.toRawJson(p2) + "\n");

            // パターン3a: server streaming で 1 件ずつ受信し、受信のたびに 1 行書き出す
            int lines = 0;
            try (Writer out = Files.newBufferedWriter(outDir.resolve("pattern3-stream.ndjson"), StandardCharsets.UTF_8);
                 Writer raw = Files.newBufferedWriter(outDir.resolve("pattern3-stream.raw.ndjson"), StandardCharsets.UTF_8)) {
                Iterator<ObjectLog> it = stub.streamObjectLogs(request(false, objectCount, eventsPerObject));
                while (it.hasNext()) {
                    ObjectLog log = it.next();
                    converter.writeNdjsonLine(log, out);
                    raw.write(converter.toRawJson(log));
                    raw.write('\n');
                    lines++;
                }
            }

            // パターン3b: SimLog.logs の各要素を 1 行ずつ
            try (Writer out = Files.newBufferedWriter(outDir.resolve("pattern3-logs.ndjson"), StandardCharsets.UTF_8)) {
                converter.writeNdjson(p1.getLogsList(), out);
            }

            System.out.printf("target=%s objectCount=%d eventsPerObject=%d%n", target, objectCount, eventsPerObject);
            System.out.printf("pattern3-stream lines=%d, pattern3-logs lines=%d%n", lines, p1.getLogsCount());
            System.out.println("written to " + outDir.toAbsolutePath());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static ManagedChannel newChannel(String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                // 既定の受信上限は 4MB。1000x50(約1.6MB)は収まるが、件数を増やした計測用に引き上げておく
                .maxInboundMessageSize(256 << 20)
                .build();
    }

    static GetSimLogRequest request(boolean includeResult, int objectCount, int eventsPerObject) {
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult)
                .setObjectCount(objectCount)
                .setEventsPerObject(eventsPerObject)
                .build();
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
