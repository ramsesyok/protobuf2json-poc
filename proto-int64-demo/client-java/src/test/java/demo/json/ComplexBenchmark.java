package demo.json;

import com.google.protobuf.MessageOrBuilder;
import demo.proto.complextest.ComplexObjectLog;
import demo.proto.complextest.ComplexSimLog;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * complex_test.proto を使った性能比較(gRPC を介さず Java 内で生成したデータを変換)。test スコープで実行する。
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=demo.json.ComplexBenchmark \
 *     -Dexec.args="objects eventsPerObject warmup iterations"
 * </pre>
 * Timestamp(WKT)なし / あり の 2 シナリオを計測する。
 */
public final class ComplexBenchmark {

    private static volatile Object sink;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        int objects = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int events = args.length > 1 ? Integer.parseInt(args[1]) : 250;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        int iterations = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        Int64JsonConverter tree = new Int64JsonConverter();
        StreamingInt64JsonConverter streaming = new StreamingInt64JsonConverter();
        DirectInt64JsonWriter direct = new DirectInt64JsonWriter();

        System.out.printf("Java %s, objects=%d, eventsPerObject=%d, warmup=%d, iterations=%d%n",
                System.getProperty("java.version"), objects, events, warmup, iterations);
        for (boolean withTs : new boolean[]{false, true}) {
            ComplexSimLog msg = ComplexData.simLog(objects, events, withTs);
            List<ComplexObjectLog> logs = msg.getLogsList();
            String expected = tree.toJson(msg);
            if (!expected.equals(streaming.toJson(msg)) || !expected.equals(direct.toJson(msg))) {
                throw new IllegalStateException("output mismatch");
            }
            System.out.printf("%n### Timestamp(WKT)%s: protobuf %,d bytes, 1 ログ平均 %,d bytes%n",
                    withTs ? "あり" : "なし", msg.getSerializedSize(), msg.getSerializedSize() / objects);
            System.out.println("| ケース | 平均 [ms] | 最小 [ms] | 割り当て [MB/回] | 出力サイズ [bytes] |");
            System.out.println("|---|---:|---:|---:|---:|");
            run("P1 素の JsonFormat のみ", warmup, iterations, () -> tree.toRawJson(msg));
            run("P1 Tree", warmup, iterations, () -> tree.toJson(msg));
            run("P1 A: Streaming", warmup, iterations, () -> streaming.toJson(msg));
            run("P1 B: Direct", warmup, iterations, () -> direct.toJson(msg));
            run("P3 素の JsonFormat のみ", warmup, iterations, () -> lines(logs, tree::toRawJson));
            run("P3 Tree", warmup, iterations, () -> tree.toNdjson(logs));
            run("P3 A: Streaming", warmup, iterations, () -> streaming.toNdjson(logs));
            run("P3 B: Direct", warmup, iterations, () -> direct.toNdjson(logs));
        }
    }

    private static String lines(List<? extends MessageOrBuilder> messages, Function<MessageOrBuilder, String> f) {
        StringBuilder sb = new StringBuilder();
        for (MessageOrBuilder m : messages) {
            sb.append(f.apply(m)).append('\n');
        }
        return sb.toString();
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
        System.gc();
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
        System.out.printf("| %s | %.1f | %.1f | %.1f | %,d |%n", name, total / 1e6 / iterations, min / 1e6,
                alloc / 1e6 / iterations, out.getBytes(StandardCharsets.UTF_8).length);
    }
}
