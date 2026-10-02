package demo.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import demo.json.Int64JsonConverter;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.grpc.ManagedChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 変換時間の簡易計測(System.nanoTime)。gRPC で 1 回だけ受信したメッセージをメモリ上で繰り返し変換する。
 * 通信時間は含まない。
 *
 * <pre>
 * 引数: [target=localhost:50051] [objectCount=1000] [eventsPerObject=50] [warmup=20] [iterations=30]
 * </pre>
 */
public final class Benchmark {

    private static volatile Object sink; // JIT による除去防止

    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0] : "localhost:50051";
        int objectCount = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int eventsPerObject = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        int iterations = args.length > 4 ? Integer.parseInt(args[4]) : 30;

        SimLog simLog;
        List<ObjectLog> streamed = new ArrayList<>();
        ManagedChannel channel = SimClient.newChannel(target);
        try {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);
            simLog = stub.getSimLog(SimClient.request(false, objectCount, eventsPerObject));
            stub.streamObjectLogs(SimClient.request(false, objectCount, eventsPerObject)).forEachRemaining(streamed::add);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        int totalEvents = simLog.getLogsList().stream().mapToInt(ObjectLog::getEventsCount).sum();

        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        Int64JsonConverter converter = new Int64JsonConverter(printer);
        ObjectMapper mapper = Int64JsonConverter.mapper();

        System.out.printf("Java %s, objectCount=%d, eventsPerObject=%d, totalEvents=%d, protobuf size=%,d bytes%n",
                System.getProperty("java.version"), objectCount, eventsPerObject, totalEvents, simLog.getSerializedSize());
        System.out.printf("warmup=%d, iterations=%d%n%n", warmup, iterations);
        System.out.println("| ケース | 平均 [ms] | 最小 [ms] | 出力サイズ [bytes] |");
        System.out.println("|---|---:|---:|---:|");

        // パターン1: SimLog 全体を 1 JSON
        run("P1 素の JsonFormat のみ", warmup, iterations, () -> converter.toRawJson(simLog));
        run("P1 Int64JsonConverter(2パス)", warmup, iterations, () -> converter.toJson(simLog));

        // パターン3: ObjectLog 1 件ずつ NDJSON(stream で受信した 1000 件)
        run("P3 素の JsonFormat のみ", warmup, iterations, () -> {
            StringBuilder sb = new StringBuilder();
            for (ObjectLog log : streamed) {
                sb.append(converter.toRawJson(log)).append('\n');
            }
            return sb.toString();
        });
        run("P3 Int64JsonConverter(2パス)", warmup, iterations, () -> converter.toNdjson(streamed));

        // 内訳(パターン1): 2 パス目の各段階のコスト
        String raw = converter.toRawJson(simLog);
        System.out.println();
        System.out.println("内訳(P1、各段階を単独計測):");
        System.out.println("| 段階 | 平均 [ms] | 最小 [ms] | 出力サイズ [bytes] |");
        System.out.println("|---|---:|---:|---:|");
        run("JsonFormat.print", warmup, iterations, () -> converter.toRawJson(simLog));
        run("Jackson readTree(raw)", warmup, iterations, () -> {
            sink = mapper.readTree(raw);
            return "";
        });
        run("toJsonNode(print+readTree+置換)", warmup, iterations, () -> {
            sink = converter.toJsonNode(simLog);
            return "";
        });
        JsonNode converted = converter.toJsonNode(simLog);
        run("Jackson writeValueAsString", warmup, iterations, () -> mapper.writeValueAsString(converted));
    }

    @FunctionalInterface
    interface Task {
        String run() throws Exception;
    }

    private static void run(String name, int warmup, int iterations, Task task) throws Exception {
        String out = null;
        for (int i = 0; i < warmup; i++) {
            out = task.run();
            sink = out;
        }
        long total = 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            out = task.run();
            long dt = System.nanoTime() - t0;
            sink = out;
            total += dt;
            min = Math.min(min, dt);
        }
        int size = out.getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("| %s | %.1f | %.1f | %s |%n", name, total / 1e6 / iterations, min / 1e6,
                size == 0 ? "-" : String.format("%,d", size));
    }
}
