package io.github.ramsesyok.protojson;

import com.google.protobuf.util.JsonFormat;
import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ExecEvent;
import demo.proto.ObjectLog;
import demo.proto.Result;
import demo.proto.SimLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;

/**
 * 簡易ベンチマーク(テストではない。手動で実行する)。SimLog を手元で生成し、JsonFormat と比較する。
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=io.github.ramsesyok.protojson.PrinterBenchmark -Dexec.args="1000 50"
 * </pre>
 * System.nanoTime による簡易計測(ウォームアップ 20 回後、30 回の平均と最小)。JMH ではないので参考値。
 */
public final class PrinterBenchmark {

    private static volatile Object sink;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        int objects = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int events = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        SimLog simLog = simLog(objects, events);

        JsonFormat.Printer jsonFormat = JsonFormat.printer().omittingInsignificantWhitespace();
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();
        OutputStream discard = OutputStream.nullOutputStream();

        System.out.printf("Java %s, objects=%d, eventsPerObject=%d, protobuf %,d bytes, JSON %,d bytes%n%n",
                System.getProperty("java.version"), objects, events, simLog.getSerializedSize(), printer.print(simLog).length());
        System.out.println("| ケース | 平均 [ms] | 最小 [ms] | 割り当て [MB/回] |");
        System.out.println("|---|---:|---:|---:|");
        run("JsonFormat.print(int64 は文字列、参考)", () -> sink = jsonFormat.print(simLog));
        run("ProtoJsonPrinter.print(String)", () -> sink = printer.print(simLog));
        run("ProtoJsonPrinter.writeTo(OutputStream)", () -> printer.writeTo(simLog, discard));
        run("ProtoJsonPrinter.printNdjson(logs)", () -> sink = printer.printNdjson(simLog.getLogsList()));
        run("NdjsonWriter(OutputStream)", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 << 20);
            try (NdjsonWriter w = printer.ndjsonWriter(out)) {
                for (ObjectLog log : simLog.getLogsList()) {
                    w.write(log);
                }
            }
            sink = out;
        });
    }

    @FunctionalInterface
    interface Task {
        void run() throws IOException;
    }

    private static void run(String name, Task task) throws IOException {
        for (int i = 0; i < 20; i++) {
            task.run();
        }
        System.gc();
        int iterations = 30;
        long total = 0;
        long min = Long.MAX_VALUE;
        long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            task.run();
            long dt = System.nanoTime() - t0;
            total += dt;
            min = Math.min(min, dt);
        }
        long alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0;
        System.out.printf("| %s | %.1f | %.1f | %.1f |%n", name, total / 1e6 / iterations, min / 1e6, alloc / 1e6 / iterations);
    }

    /** Go サーバ(src/go)と同様の値(0・負数・2^53+1・MIN/MAX、改行や日本語を含む文字列)を持つ SimLog。 */
    static SimLog simLog(int objects, int eventsPerObject) {
        long[] longs = {0, 1, -1, 42, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE, -9007199254740993L, 1234567890123L};
        String[] strings = {"12345", "a\nb", "say \"hi\"", "日本語テキスト", "-9223372036854775808", "0", "<tag> & a=b 'q'", "", "plain"};
        SimLog.Builder b = SimLog.newBuilder().setJobId(9007199254740993L);
        for (int i = 0; i < objects; i++) {
            ObjectLog.Builder ol = ObjectLog.newBuilder().setTimestamp(1700000000000L + i).setObjectId(longs[i % longs.length]);
            for (int j = 0; j < eventsPerObject; j++) {
                int g = i * eventsPerObject + j;
                int k = g / 3;
                Event.Builder e = Event.newBuilder().setEventId(g).setEventType(longs[(g + 1) % longs.length]);
                if (g % 3 == 0) {
                    e.setCommEvent(CommEvent.newBuilder().setFromId(longs[(k + 2) % longs.length])
                            .setToId(longs[(k + 3) % longs.length]).setBody(strings[k % strings.length]));
                } else if (g % 3 == 1) {
                    e.setExecEvent(ExecEvent.newBuilder().setExecId(longs[(k + 5) % longs.length])
                            .setCommand(strings[(k + 1) % strings.length]).setResult(strings[(k + 2) % strings.length]));
                }
                ol.addEvents(e);
            }
            b.addLogs(ol);
        }
        return b.setResult(Result.newBuilder().setExitCode(Long.MIN_VALUE).setOutput("9223372036854775807")).build();
    }
}
