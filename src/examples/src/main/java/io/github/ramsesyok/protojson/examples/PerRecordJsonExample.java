// =====================================================================================================
// 例 1: SimLog の logs を「1 レコード(ObjectLog)ずつ」JSON 文字列にする
// =====================================================================================================
//
// 【このプログラムがすること】
//   1. gRPC サーバ(src/go の動作テスト用サーバ)に接続する
//   2. GetSimLog を呼び出して、SimLog(ログ全体)を 1 回で受け取る
//   3. SimLog の中の logs(ObjectLog のリスト)を 1 件ずつ取り出し、ProtoJsonPrinter で JSON 文字列にする
//   4. 1 件分の JSON ができるたびに画面に表示する(実際のアプリでは、ここで DB に保存するなどの処理をする)
//
// 【この例で覚えてほしいこと】
//   - ProtoJsonPrinter は 1 つ作れば何度でも使える(作り直す必要はない)
//   - printer.print(メッセージ) を呼ぶだけで、そのメッセージが 1 行の JSON 文字列になる
//   - int64 のフィールド(timestamp / objectId / eventId など)は "123" のような文字列ではなく、123 のような数値になる
//   - 値が 0 のフィールドは JSON に出てこない(Protobuf の JSON の決まり。キーごと省略される)
//
// 【実行方法】(先に src/go のサーバを起動しておく)
//   cd src/examples
//   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.PerRecordJsonExample \
//       -Dexec.args="localhost:50051 3 3"
//   引数(省略可): [接続先=localhost:50051] [ObjectLog の件数=3] [1 件あたりの Event 数=3]
//
// 【出力例】
//   record[0] {"timestamp":1700000000000,"events":[...]}
//   record[1] {"timestamp":1700000000001,"objectId":1,"events":[...]}
//   ...
// =====================================================================================================

// package(パッケージ)は、クラスを入れる「フォルダ名」のようなもの。
// ファイルの置き場所(src/main/java/io/github/ramsesyok/protojson/examples/)と一致させる決まりがある。
package io.github.ramsesyok.protojson.examples;

// import は「別のパッケージにあるクラスを、短い名前で使えるようにする」宣言。
// demo.proto.* は simlog.proto から自動生成されたクラス(SimLog, ObjectLog など)。
import demo.proto.GetSimLogRequest;   // GetSimLog を呼ぶときに渡す「リクエスト」(何件ほしいか等)
import demo.proto.ObjectLog;          // ログ 1 レコード分のデータ
import demo.proto.SimLog;             // ログ全体(ObjectLog のリスト logs と、結果 result を持つ)
import demo.proto.SimServiceGrpc;     // gRPC のサービス(サーバの機能)を呼び出すためのクラス
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする
import io.grpc.ManagedChannel;        // gRPC の「接続」を表すクラス
import io.grpc.ManagedChannelBuilder; // ManagedChannel を作るためのクラス

import java.util.concurrent.TimeUnit;    // 時間の単位(秒など)を表す
import java.util.function.BiConsumer;    // 「値を 2 つ受け取って何かする処理」を表す型(下で説明)

/**
 * 例 1: SimLog の logs を「1 レコード(ObjectLog)ずつ」JSON 文字列にする。
 *
 * <p>GetSimLog(1 回の呼び出しで全体を受け取る gRPC)で SimLog を受信し、{@code logs} の各要素を
 * {@link ProtoJsonPrinter#print} で 1 件ずつ JSON にする。レコードごとに保存・送信したい場合
 * (DB の 1 行、メッセージキューの 1 メッセージ、1 ファイル等)を想定している。
 * int64 のフィールド(timestamp / objectId / eventId 等)は JSON の数値になる。
 */
// public: どこからでも使えるクラス
// final : このクラスを継承(extends)させない。「このクラスはこれで完成」という意思表示
public final class PerRecordJsonExample {

    // コンストラクタを private にして、new PerRecordJsonExample() でインスタンスを作れないようにしている。
    // このクラスは static メソッド(インスタンスを作らずに呼べるメソッド)だけの「道具箱」なので、
    // インスタンスを作る意味が無いため。
    private PerRecordJsonExample() {
    }

    /**
     * プログラムの入口。java コマンド(または mvn exec:java)で実行すると、最初にこのメソッドが呼ばれる。
     *
     * @param args コマンドラインで渡された引数(-Dexec.args="..." の中身が空白区切りで入る)
     * @throws InterruptedException 接続を閉じるのを待っている間に、スレッドが中断された場合
     */
    public static void main(String[] args) throws InterruptedException {
        // ---------------------------------------------------------------------------------------------
        // (1) 引数を読む。引数が足りないときは既定値を使う
        // ---------------------------------------------------------------------------------------------
        // 「条件 ? A : B」は三項演算子。条件が true なら A、false なら B になる。
        // args.length は引数の個数。例えば引数が 1 個も無ければ args.length は 0。
        String target = args.length > 0 ? args[0] : "localhost:50051";          // 接続先(ホスト名:ポート番号)
        // Integer.parseInt は文字列 "3" を数値 3 に変換する(数字でない文字列だと例外が発生する)
        int objectCount = args.length > 1 ? Integer.parseInt(args[1]) : 3;      // 受け取る ObjectLog の件数
        int eventsPerObject = args.length > 2 ? Integer.parseInt(args[2]) : 3;  // 1 件あたりの Event 数

        // ---------------------------------------------------------------------------------------------
        // (2) JSON 変換器(ProtoJsonPrinter)を作る
        // ---------------------------------------------------------------------------------------------
        // ProtoJsonPrinter は「不変(作った後に中身が変わらない)」かつ「スレッドセーフ(複数の処理から同時に
        // 使っても安全)」なので、アプリ全体で 1 つ作って使い回せばよい。1 件ごとに作り直す必要は無い。
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();

        // ---------------------------------------------------------------------------------------------
        // (3) gRPC サーバへの接続(チャネル)を作る
        // ---------------------------------------------------------------------------------------------
        // ManagedChannelBuilder.forTarget(接続先) で接続の設定を始め、.build() で接続オブジェクトを作る。
        // usePlaintext() は「暗号化(TLS)しない」設定。動作テスト用のローカルサーバなのでこれでよいが、
        // 本番でネットワーク越しに使う場合は TLS を使うこと。
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();

        // try { ... } finally { ... } は、try の中で例外(エラー)が起きても起きなくても、
        // 最後に必ず finally の中を実行する書き方。接続の後片付けを確実に行うために使っている。
        try {
            // スタブ(stub)は、サーバの機能を「普通のメソッド呼び出し」のように呼べるようにするための窓口。
            // BlockingStub は「呼び出したら結果が返るまで待つ(同期)」タイプのスタブ。
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);

            // リクエストを作る。Protobuf のメッセージは「Builder(組み立て役)」で値を設定し、
            // 最後に build() で完成品(変更できないオブジェクト)を作る。これを Builder パターンという。
            GetSimLogRequest request = GetSimLogRequest.newBuilder()
                    .setObjectCount(objectCount)          // ObjectLog を何件ほしいか
                    .setEventsPerObject(eventsPerObject)  // 1 件あたり Event を何件ほしいか
                    .build();

            // -----------------------------------------------------------------------------------------
            // (4) 1 レコードずつ JSON にする(処理の本体は下の convertEachRecord メソッド)
            // -----------------------------------------------------------------------------------------
            // 第 4 引数の「(index, json) -> { ... }」はラムダ式。「レコード番号と JSON を受け取ったら、
            // { } の中の処理をする」という処理そのものを、値として渡している。
            // convertEachRecord は 1 件 JSON ができるたびに、この処理を呼び出す。
            int count = convertEachRecord(stub, request, printer, (index, json) -> {
                // 実際のアプリケーションでは、ここで DB への保存やメッセージ送信などを行う。
                // 例: repository.save(json);
                System.out.println("record[" + index + "] " + json);
            });
            System.out.println(count + " records converted");
        } finally {
            // (5) 後片付け: 接続を閉じる。shutdownNow() で閉じ始め、awaitTermination で最大 5 秒待つ。
            // 接続を閉じ忘れると、プログラムが終了しない・資源を使い続ける等の問題が起きる。
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * SimLog を受信し、logs の各レコードを JSON 文字列にして handler に渡す。
     *
     * <p>テスト(ExamplesTest)からも呼べるように、main から処理の本体を切り出して public にしている。
     *
     * @param stub    gRPC サーバを呼び出すためのスタブ
     * @param request GetSimLog に渡すリクエスト
     * @param printer JSON 変換器
     * @param handler 1 件分の JSON ができるたびに呼ばれる処理。(レコードの番号, そのレコードの JSON) を受け取る。
     *                BiConsumer&lt;A, B&gt; は「A と B を受け取って何かする(戻り値なし)」処理を表す型
     * @return 変換したレコード数
     */
    public static int convertEachRecord(SimServiceGrpc.SimServiceBlockingStub stub, GetSimLogRequest request,
                                        ProtoJsonPrinter printer, BiConsumer<Integer, String> handler) {
        // サーバの GetSimLog を呼び出す。結果(SimLog 全体)が返ってくるまでここで待つ。
        SimLog simLog = stub.getSimLog(request);

        int index = 0; // 何件目かを数えるための変数(0 から始める)

        // 拡張 for 文(for-each): simLog.getLogsList() のリストから要素を 1 つずつ取り出して log に入れ、
        // { } の中を繰り返す。リストの要素が無くなったら終わる。
        for (ObjectLog log : simLog.getLogsList()) {
            // ★ここがポイント: ObjectLog 1 件を JSON 文字列にする。
            //   結果は必ず 1 行(改行を含まない)。文字列の中の改行は \n という 2 文字に変換される。
            //   int64 のフィールドは数値で出力される(例: "objectId":1 。"objectId":"1" にはならない)。
            String json = printer.print(log);

            // 受け取った処理(handler)に、番号と JSON を渡す。
            // index++ は「今の index の値を使ってから、index を 1 増やす」という意味。
            handler.accept(index++, json);
        }
        return index; // 最後の index は、処理した件数と同じになる
    }
}
