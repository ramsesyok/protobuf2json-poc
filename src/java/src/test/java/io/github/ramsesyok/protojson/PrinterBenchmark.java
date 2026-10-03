// =====================================================================================================
// PrinterBenchmark: 変換の速さを測る簡易ベンチマーク(テストではない。必要なときに手で実行する)
// =====================================================================================================
//
// 【ベンチマークとは】
//   処理にかかる時間などを測って、性能を比べること。ここでは次の 5 通りの時間と、メモリの使用量(割り当て量)を測る。
//     - Protobuf 公式の JsonFormat(int64 は文字列。比較のための参考)
//     - ProtoJsonPrinter.print(SimLog 全体 → 文字列)
//     - ProtoJsonPrinter.writeTo(SimLog 全体 → OutputStream)
//     - 1 レコードずつ print / writeTo(SimLog の logs の各要素を 1 件ずつ)
//
// 【測り方の注意】
//   Java は、同じ処理を何度も実行するうちに速くなる(JIT コンパイルという最適化が働く)。そのため、
//   最初に 20 回「ウォームアップ(準備運動)」として実行してから、30 回の時間を測って平均と最小を出している。
//   System.nanoTime による簡易的な計測なので、数値は参考値。マシンや実行のたびに多少ばらつく。
//
// 【実行方法】(gRPC サーバは不要。データはこのファイルの中で作る)
//   cd src/java
//   mvn -q test-compile exec:java -Dexec.classpathScope=test \
//       -Dexec.mainClass=io.github.ramsesyok.protojson.PrinterBenchmark -Dexec.args="1000 50"
//   引数: [ObjectLog の件数=1000] [1 件あたりの Event 数=50]
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.google.protobuf.util.JsonFormat;
import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ExecEvent;
import demo.proto.ObjectLog;
import demo.proto.Result;
import demo.proto.SimLog;

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

    /**
     * 計算結果の捨て場。結果をどこにも使わないと、Java が「この計算は不要」と判断して処理自体を省いてしまい、
     * 正しく測れないことがある。それを防ぐため、結果をここに入れておく(volatile は最適化で省かれにくくする指定)。
     */
    private static volatile Object sink;
    /** スレッドが確保したメモリの量を調べるための道具(Java の管理用 API)。 */
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        // (1) 引数を読み、測定用のデータ(SimLog)を作る
        int objects = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int events = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        SimLog simLog = simLog(objects, events);

        // (2) 比べる変換器を用意する。discard は、書いたデータをすべて捨てる OutputStream(書き出し先の時間を含めないため)
        JsonFormat.Printer jsonFormat = JsonFormat.printer().omittingInsignificantWhitespace();
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();
        OutputStream discard = OutputStream.nullOutputStream();

        // (3) 条件を表示し、結果の表(Markdown 形式)の見出しを表示する。%s / %d / %,d は printf の書式(文字列 / 整数 / 3 桁区切りの整数)
        System.out.printf("Java %s, objects=%d, eventsPerObject=%d, protobuf %,d bytes, JSON %,d bytes%n%n",
                System.getProperty("java.version"), objects, events, simLog.getSerializedSize(), printer.print(simLog).length());
        System.out.println("| ケース | 平均 [ms] | 最小 [ms] | 割り当て [MB/回] |");
        System.out.println("|---|---:|---:|---:|");
        // (4) 各ケースを測る。第 2 引数は「測りたい処理」をラムダ式で渡している
        run("JsonFormat.print(int64 は文字列、参考)", () -> sink = jsonFormat.print(simLog));
        run("ProtoJsonPrinter.print(String)", () -> sink = printer.print(simLog));
        run("ProtoJsonPrinter.writeTo(OutputStream)", () -> printer.writeTo(simLog, discard));
        run("1 レコードずつ print(logs の各要素)", () -> {
            for (ObjectLog log : simLog.getLogsList()) {
                sink = printer.print(log);
            }
        });
        run("1 レコードずつ writeTo(OutputStream)", () -> {
            for (ObjectLog log : simLog.getLogsList()) {
                printer.writeTo(log, discard);
            }
        });
    }

    /**
     * 「測りたい処理」を表す型。メソッドが 1 つだけのインタフェース(関数型インタフェース)なので、ラムダ式で書ける。
     * Java 標準の Runnable と違い、IOException を投げてもよいようにしている。
     */
    @FunctionalInterface
    interface Task {
        void run() throws IOException;
    }

    /**
     * 処理 task の時間とメモリ割り当て量を測り、表の 1 行として表示する。
     *
     * @param name 表に表示するケース名
     * @param task 測る処理
     */
    private static void run(String name, Task task) throws IOException {
        // ウォームアップ: 20 回実行して、Java の最適化(JIT コンパイル)を済ませておく
        for (int i = 0; i < 20; i++) {
            task.run();
        }
        // ガベージコレクション(不要になったメモリの片付け)を促し、前のケースの影響を減らす
        System.gc();
        int iterations = 30;
        long total = 0;
        long min = Long.MAX_VALUE;
        // 測定開始時点の「このスレッドがこれまでに確保したメモリの合計」を覚えておく
        long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            // System.nanoTime() はナノ秒(10 億分の 1 秒)単位の時刻。処理の前後の差がかかった時間
            long t0 = System.nanoTime();
            task.run();
            long dt = System.nanoTime() - t0;
            total += dt;
            min = Math.min(min, dt);
        }
        // 測定中に確保したメモリの合計(1e6 で割ると MB)。最後に表の 1 行として、平均時間・最小時間・1 回あたりの割り当て量を表示する
        long alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0;
        System.out.printf("| %s | %.1f | %.1f | %.1f |%n", name, total / 1e6 / iterations, min / 1e6, alloc / 1e6 / iterations);
    }

    /** Go サーバ(src/go)と同様の値(0・負数・2^53+1・MIN/MAX、改行や日本語を含む文字列)を持つ SimLog。 */
    // 値は配列から順番に選ぶ(i % longs.length は「i を配列の長さで割った余り」で、0〜長さ-1 を繰り返す)。
    // Event は通し番号 g を 3 で割った余りで、commEvent / execEvent / どちらも無し を順番に入れる。
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
