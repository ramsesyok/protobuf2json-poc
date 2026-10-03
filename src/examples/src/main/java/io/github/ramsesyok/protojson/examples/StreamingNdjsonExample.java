// =====================================================================================================
// 例 2: ObjectLog を 1 レコードずつ受信し、受信するたびに NDJSON の 1 行として書き出す
// =====================================================================================================
//
// 【NDJSON とは】
//   "Newline Delimited JSON" の略で、「1 行に 1 つの JSON」を並べたテキスト形式(JSON Lines とも呼ばれる)。
//     {"timestamp":1700000000000,...}      ← 1 行目 = 1 レコード目
//     {"timestamp":1700000000001,...}      ← 2 行目 = 2 レコード目
//   1 行ずつ読めば 1 レコードずつ処理できるので、大量のログを扱うのに向いている。
//
// 【このプログラムがすること】
//   1. gRPC サーバ(src/go の動作テスト用サーバ)に接続する
//   2. StreamObjectLogs(server streaming)を呼び出す。サーバは ObjectLog を 1 件ずつ順番に送ってくる
//   3. 1 件受信するたびに、NdjsonWriter で JSON 1 行として書き出す(画面またはファイルへ)
//
// 【例 1(PerRecordJsonExample)との違い】
//   - 例 1 は GetSimLog で「全件をまとめて 1 回で」受け取ってから、1 件ずつ JSON にする
//   - 例 2 は StreamObjectLogs で「1 件ずつ」受け取り、受け取ったそばから書き出す
//     → 全件をメモリに溜めないので、件数が非常に多い場合でもメモリを使いすぎない
//
// 【実行方法】(先に src/go のサーバを起動しておく)
//   cd src/examples
//   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.StreamingNdjsonExample \
//       -Dexec.args="localhost:50051 3 3 -"
//   引数(省略可): [接続先=localhost:50051] [ObjectLog の件数=3] [1 件あたりの Event 数=3]
//                 [出力先ファイル名。"-" のときは画面(標準出力)=-]
//   ファイルに書く例: -Dexec.args="localhost:50051 1000 50 out.ndjson"
// =====================================================================================================

package io.github.ramsesyok.protojson.examples;

import demo.proto.GetSimLogRequest;   // サーバに渡すリクエスト(何件ほしいか等)
import demo.proto.ObjectLog;          // ログ 1 レコード分のデータ
import demo.proto.SimServiceGrpc;     // gRPC のサービスを呼び出すためのクラス
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする
import io.grpc.ManagedChannel;        // gRPC の「接続」
import io.grpc.ManagedChannelBuilder; // ManagedChannel を作るためのクラス

import java.io.FilterOutputStream;    // 別の OutputStream を包んで、一部の動きだけ変えるための土台クラス
import java.io.IOException;           // 入出力(ファイル・通信など)で起きる例外
import java.io.OutputStream;          // バイト列の書き出し先(ファイル・画面・通信など)を表す共通の型
import java.nio.file.Files;           // ファイル操作の便利メソッド集
import java.nio.file.Path;            // ファイルの場所(パス)を表す
import java.util.Iterator;            // 要素を 1 つずつ取り出すための仕組み(下で説明)
import java.util.concurrent.TimeUnit; // 時間の単位(秒など)

/**
 * 例 2: server streaming で ObjectLog を 1 レコードずつ受信し、受信するたびに NDJSON の 1 行として書き出す。
 *
 * <p>全件をメモリに溜めずに流せるので、件数が多い場合や HTTP のストリーミング応答に向いている。
 * 各行は 1 レコード分の JSON で、行末は {@code \n}。文字列中の改行はエスケープされるので行は割れない。
 * NDJSON の書き出しには、同じパッケージの {@link NdjsonWriter}(これも利用例)を使っている。
 */
public final class StreamingNdjsonExample {

    // static メソッドだけのクラスなので、インスタンスを作れないようにコンストラクタを private にしている。
    private StreamingNdjsonExample() {
    }

    /**
     * プログラムの入口。
     *
     * @param args コマンドラインで渡された引数
     * @throws IOException          ファイルや画面への書き込みに失敗した場合
     * @throws InterruptedException 接続を閉じるのを待っている間に、スレッドが中断された場合
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        // ---------------------------------------------------------------------------------------------
        // (1) 引数を読む(足りなければ既定値)。「条件 ? A : B」は条件が true なら A、false なら B
        // ---------------------------------------------------------------------------------------------
        String target = args.length > 0 ? args[0] : "localhost:50051";          // 接続先
        int objectCount = args.length > 1 ? Integer.parseInt(args[1]) : 3;      // ObjectLog の件数
        int eventsPerObject = args.length > 2 ? Integer.parseInt(args[2]) : 3;  // 1 件あたりの Event 数
        String output = args.length > 3 ? args[3] : "-";                        // 出力先("-" は画面)

        // ---------------------------------------------------------------------------------------------
        // (2) JSON 変換器を作る(1 つ作れば何度でも使える)
        // ---------------------------------------------------------------------------------------------
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();

        // ---------------------------------------------------------------------------------------------
        // (3) gRPC サーバへの接続を作る(usePlaintext は暗号化しない設定。動作テスト用のため)
        // ---------------------------------------------------------------------------------------------
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();

        // ---------------------------------------------------------------------------------------------
        // (4) 出力先を開いて書き出す
        // ---------------------------------------------------------------------------------------------
        // try (変数 = ...) { ... } は try-with-resources 文。( ) の中で開いたもの(ここでは out)は、
        // { } を抜けるときに自動で close(閉じる)される。閉じ忘れを防げるので、ファイルなどを扱うときの基本形。
        //
        // 出力先は、output が "-" なら画面(System.out)、そうでなければ指定された名前のファイル。
        // ただし画面(System.out)を閉じてしまうと、その後 System.out.println などが使えなくなるので、
        // notClosing(...)(このファイルの一番下で定義)で「閉じても実際には閉じない」ように包んでいる。
        try (OutputStream out = output.equals("-") ? notClosing(System.out) : Files.newOutputStream(Path.of(output))) {
            // サーバの機能を呼び出すための窓口(スタブ)
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);

            // リクエストを Builder パターンで作る(値を設定して、最後に build() で完成)
            GetSimLogRequest request = GetSimLogRequest.newBuilder()
                    .setObjectCount(objectCount)
                    .setEventsPerObject(eventsPerObject)
                    .build();

            // 処理の本体(下の streamToNdjson メソッド)。書き出した行数が返ってくる。
            long lines = streamToNdjson(stub, request, printer, out);

            // 件数は System.err(エラー出力)に表示する。System.out(標準出力)に出すと、
            // 画面に出している NDJSON の中に混ざってしまうため。
            System.err.println(lines + " records written to " + (output.equals("-") ? "stdout" : output));
        } finally {
            // (5) 後片付け: 接続を閉じる(エラーが起きても必ず実行される)
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * StreamObjectLogs で受信した ObjectLog を 1 件ずつ NDJSON の 1 行として out に書き出す。
     * 書き終えたら out を閉じる({@link NdjsonWriter#close()} が出力先も閉じるため)。
     *
     * <p>テスト(ExamplesTest)からも呼べるように、main から処理の本体を切り出して public にしている。
     *
     * @param stub    gRPC サーバを呼び出すためのスタブ
     * @param request StreamObjectLogs に渡すリクエスト
     * @param printer JSON 変換器
     * @param out     書き出し先(ファイル・画面・HTTP 応答など)
     * @return 書き出したレコード(行)数
     * @throws IOException 書き出しに失敗した場合
     */
    public static long streamToNdjson(SimServiceGrpc.SimServiceBlockingStub stub, GetSimLogRequest request,
                                      ProtoJsonPrinter printer, OutputStream out) throws IOException {
        long lines = 0; // 書いた行数を数える(long は int より大きな数を扱える整数型)

        // NdjsonWriter も try-with-resources で開く。{ } を抜けるときに自動で close され、
        // まだ書き出していないデータ(バッファの中身)も確実に書き出される。
        try (NdjsonWriter writer = new NdjsonWriter(printer, out)) {
            // server streaming の呼び出し。戻り値の Iterator(イテレータ)から 1 件ずつ取り出せる。
            // サーバがデータを送ってくるたびに、次の 1 件が取り出せるようになる。
            Iterator<ObjectLog> it = stub.streamObjectLogs(request);

            // it.hasNext(): まだ次の 1 件があるか(サーバが送り終わるまで true)
            // it.next()   : 次の 1 件を取り出す(届いていなければ届くまで待つ)
            while (it.hasNext()) {
                ObjectLog log = it.next();

                // ★1 レコード → 1 行。JSON 1 つと改行(\n)を書く。
                //   JSON の中の文字列に改行があっても \n という 2 文字に変換されるので、行が途中で割れることはない。
                writer.write(log);

                // flush は「溜めているデータを今すぐ相手に送る」処理。
                // HTTP 応答などで、受け手に 1 件ずつすぐ届けたい場合は毎回呼ぶ。
                // ファイルにまとめて書くだけなら毎回呼ばなくてもよい(呼ばない方が速い)。
                writer.flush();

                lines++; // 1 行書いたので数を 1 増やす
            }
        }
        return lines;
    }

    /**
     * close() されても、中の OutputStream を閉じない OutputStream を作る(画面出力 System.out を閉じないため)。
     *
     * <p>{@code new FilterOutputStream(out) { ... }} は「匿名クラス」と呼ばれる書き方で、
     * FilterOutputStream を継承した名前の無いクラスをその場で作り、一部のメソッドだけ上書き(@Override)している。
     */
    private static OutputStream notClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            // まとめて書くメソッドを、中の stream にそのまま渡す
            // (FilterOutputStream の既定の実装は 1 バイトずつ書くので遅い。そのため上書きしている)
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            // close() が呼ばれても閉じずに、flush(溜めたデータの書き出し)だけ行う
            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }
}
