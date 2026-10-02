package demo.app;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import demo.json.DirectInt64JsonWriter;
import demo.json.Int64JsonConverter;
import demo.json.StreamingInt64JsonConverter;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.grpc.ManagedChannel;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 変換時間の簡易計測(System.nanoTime)。gRPC で 1 回だけ受信したメッセージをメモリ上で繰り返し変換する。
 * 通信時間は含まない。割り当て量は com.sun.management.ThreadMXBean の計測スレッド分(GC 回収分も含む総量)。
 *
 * <pre>
 * 引数: [target=localhost:50051] [objectCount=1000] [eventsPerObject=50] [warmup=20] [iterations=30]
 * </pre>
 */
public final class Benchmark {

    private static volatile Object sink; // JIT による除去防止
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

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
        Int64JsonConverter tree = new Int64JsonConverter(printer);
        StreamingInt64JsonConverter streaming = new StreamingInt64JsonConverter(printer);
        DirectInt64JsonWriter direct = new DirectInt64JsonWriter();

        // 計測前に 3 方式の出力が一致することを確認
        String expectedP1 = tree.toJson(simLog);
        String expectedP3 = tree.toNdjson(streamed);
        check(expectedP1.equals(streaming.toJson(simLog)) && expectedP1.equals(direct.toJson(simLog)), "P1 mismatch");
        check(expectedP3.equals(streaming.toNdjson(streamed)) && expectedP3.equals(direct.toNdjson(streamed)), "P3 mismatch");

        System.out.printf("Java %s, objectCount=%d, eventsPerObject=%d, totalEvents=%d, protobuf size=%,d bytes%n",
                System.getProperty("java.version"), objectCount, eventsPerObject, totalEvents, simLog.getSerializedSize());
        System.out.printf("warmup=%d, iterations=%d, maxHeap=%,d MB%n%n", warmup, iterations,
                Runtime.getRuntime().maxMemory() >> 20);
        System.out.println("| ケース | 平均 [ms] | 最小 [ms] | 割り当て [MB/回] | 出力サイズ [bytes] |");
        System.out.println("|---|---:|---:|---:|---:|");

        // パターン1: SimLog 全体を 1 JSON
        run("P1 素の JsonFormat のみ(int64 は文字列)", warmup, iterations, () -> tree.toRawJson(simLog));
        run("P1 Tree: Int64JsonConverter(JsonNode)", warmup, iterations, () -> tree.toJson(simLog));
        run("P1 Streaming: JsonFormat→Parser→Generator", warmup, iterations, () -> streaming.toJson(simLog));
        run("P1 Direct: リフレクション→Generator(1パス)", warmup, iterations, () -> direct.toJson(simLog));

        // パターン3: ObjectLog 1 件ずつ NDJSON(stream で受信した件数分)
        run("P3 素の JsonFormat のみ(int64 は文字列)", warmup, iterations, () -> lines(streamed, tree::toRawJson));
        run("P3 Tree: Int64JsonConverter(JsonNode)", warmup, iterations, () -> tree.toNdjson(streamed));
        run("P3 Streaming: JsonFormat→Parser→Generator", warmup, iterations, () -> streaming.toNdjson(streamed));
        run("P3 Direct: リフレクション→Generator(1パス)", warmup, iterations, () -> direct.toNdjson(streamed));
    }

    private static String lines(List<? extends MessageOrBuilder> messages, Function<MessageOrBuilder, String> f) {
        StringBuilder sb = new StringBuilder();
        for (MessageOrBuilder m : messages) {
            sb.append(f.apply(m)).append('\n');
        }
        return sb.toString();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
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
        System.gc(); // 前のケースのゴミの影響を減らす
        long total = 0;
        long min = Long.MAX_VALUE;
        long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            out = task.run();
            long dt = System.nanoTime() - t0;
            sink = out;
            total += dt;
            min = Math.min(min, dt);
        }
        long alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0;
        int size = out.getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("| %s | %.1f | %.1f | %.1f | %,d |%n", name, total / 1e6 / iterations, min / 1e6,
                alloc / 1e6 / iterations, size);
    }
}
