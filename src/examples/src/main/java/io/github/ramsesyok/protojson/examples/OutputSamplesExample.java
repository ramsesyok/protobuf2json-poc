// =====================================================================================================
// 例 3: 出力サンプル(JSON ファイル)を作る
// =====================================================================================================
//
// 【このプログラムがすること】
//   gRPC サーバ(src/go の動作テスト用サーバ)からデータを受け取り、次の 3 種類の JSON をファイルに書き出す。
//   (作った結果は src/examples/samples/ にコミット済みなので、実行しなくても中身を確認できる)
//
//     ファイル名                      | 内容
//     --------------------------------+-----------------------------------------------------------
//     simlog-with-result.json         | SimLog 全体(result あり)
//     simlog-without-result.json      | SimLog 全体(result なし。JSON に "result" キー自体が無い)
//     objectlog-record.json           | ObjectLog の 1 レコードだけ(SimLog の logs[1])
//
//   それぞれについて 2 つのファイルを書く。
//     *.json        … ProtoJsonPrinter の実際の出力そのもの(1 行。改行なし)
//     *.pretty.json … 人が読みやすいように改行とインデント(字下げ)を入れたもの(値は同じ)
//
// 【この例で覚えてほしいこと】
//   - SimLog 全体でも、その中の ObjectLog 1 件だけでも、同じ printer.print(...) で JSON にできる
//     (ProtoJsonPrinter は特定の proto に依存せず、どんなメッセージでも JSON にできる)
//   - optional の result を設定していない SimLog では、JSON に "result" キーが出てこない
//   - 整形(pretty)したいときは、Jackson の整形機能を有効にした JsonGenerator を printer.writeTo に渡す
//
// 【実行方法】(先に src/go のサーバを起動しておく)
//   cd src/examples
//   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.OutputSamplesExample \
//       -Dexec.args="localhost:50051 samples"
//   引数(省略可): [接続先=localhost:50051] [出力先フォルダ=samples]
// =====================================================================================================

package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonEncoding;   // JSON を書くときの文字コード(UTF-8 など)
import com.fasterxml.jackson.core.JsonGenerator;  // Jackson の「JSON を少しずつ書き出す」道具
import com.google.protobuf.MessageOrBuilder;      // すべての Protobuf メッセージ(SimLog や ObjectLog)に共通の型
import demo.proto.GetSimLogRequest;               // サーバに渡すリクエスト
import demo.proto.SimLog;                         // ログ全体
import demo.proto.SimServiceGrpc;                 // gRPC のサービスを呼び出すためのクラス
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする
import io.grpc.ManagedChannel;                    // gRPC の「接続」
import io.grpc.ManagedChannelBuilder;             // ManagedChannel を作るためのクラス

import java.io.ByteArrayOutputStream;     // 書き出したバイト列をメモリ上に溜める OutputStream
import java.io.IOException;               // 入出力(ファイル・通信など)で起きる例外
import java.nio.charset.StandardCharsets; // 文字コードの定数(UTF_8 など)
import java.nio.file.Files;               // ファイル操作の便利メソッド集
import java.nio.file.Path;                // ファイルやフォルダの場所(パス)
import java.util.LinkedHashMap;           // 「キー → 値」の表。入れた順番を覚えている
import java.util.Map;                     // 「キー → 値」の表を表す共通の型
import java.util.concurrent.TimeUnit;     // 時間の単位(秒など)

/**
 * 例 3: 出力サンプルを作る。次の 3 種類の JSON をファイルに書き出す(コミット済みの結果は {@code samples/} にある)。
 *
 * <ul>
 *   <li>{@code simlog-with-result.json}: SimLog 全体(result あり)</li>
 *   <li>{@code simlog-without-result.json}: SimLog 全体(result なし。result キー自体が無い)</li>
 *   <li>{@code objectlog-record.json}: ObjectLog の 1 レコードだけ(SimLog の logs[1])</li>
 * </ul>
 * それぞれ、実際の出力そのもの({@code .json}、1 行)と、読みやすく整形したもの({@code .pretty.json})を書く。
 * 整形版は {@link ProtoJsonPrinter#writeTo(MessageOrBuilder, JsonGenerator)} に Jackson の整形機能を
 * 有効にした JsonGenerator を渡して作っている(値は同じで、空白と改行だけが異なる)。
 */
public final class OutputSamplesExample {

    // static final は「クラスに 1 つだけあり、値を変更できない」定数。名前は大文字とアンダースコアで書く習慣がある。

    /** サンプルに含める ObjectLog の件数(サンプルを短く読める大きさにするため少なくしている)。 */
    static final int OBJECT_COUNT = 2;

    /** ObjectLog 1 件あたりの Event の件数。 */
    static final int EVENTS_PER_OBJECT = 3;

    /**
     * 1 レコードのサンプルに使う logs の位置(0 から数える。1 なら 2 件目)。
     * logs[0](1 件目)は objectId が 0 で、JSON では値が 0 のキーが省略されて分かりにくいので、logs[1] を使う。
     */
    static final int RECORD_INDEX = 1;

    // static メソッドだけのクラスなので、インスタンスを作れないようにコンストラクタを private にしている。
    private OutputSamplesExample() {
    }

    /**
     * プログラムの入口。サンプルを作ってファイルに書き、同じ内容を画面にも表示する。
     *
     * @param args コマンドラインで渡された引数
     * @throws IOException          ファイルの書き込みに失敗した場合
     * @throws InterruptedException 接続を閉じるのを待っている間に、スレッドが中断された場合
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        // (1) 引数を読む(足りなければ既定値)
        String target = args.length > 0 ? args[0] : "localhost:50051";      // 接続先
        Path outDir = Path.of(args.length > 1 ? args[1] : "samples");       // 出力先のフォルダ

        // (2) JSON 変換器と、gRPC サーバへの接続を作る
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        try {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);

            // (3) サンプルを作る(処理の本体は下の createSamples メソッド)。
            //     結果は「ファイル名 → ファイルの中身」の表(Map)で返ってくる。
            Map<String, String> files = createSamples(stub, printer);

            // (4) 出力先フォルダを作り(既にあれば何もしない)、ファイルを 1 つずつ書き出す
            Files.createDirectories(outDir);
            // Map.Entry は Map の 1 組(キーと値のペア)。entrySet() で全部の組を順に取り出せる。
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path file = outDir.resolve(e.getKey());                         // フォルダ + ファイル名
                Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);  // UTF-8 で書き込む
                System.out.println("==> " + file);                              // どのファイルか表示
                System.out.print(e.getValue());                                 // 中身も表示
            }
        } finally {
            // (5) 後片付け: 接続を閉じる(エラーが起きても必ず実行される)
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 3 種類のサンプルを作る。
     *
     * <p>テスト(ExamplesTest)からも呼べるように、main から処理の本体を切り出して public にしている。
     * テストでは、ここで作った内容と samples/ にコミット済みのファイルが一致するか(古くなっていないか)を確認している。
     *
     * @param stub    gRPC サーバを呼び出すためのスタブ
     * @param printer JSON 変換器
     * @return ファイル名 → ファイルの内容(末尾に改行を 1 つ付けている)。入れた順番を保つ LinkedHashMap
     * @throws IOException 整形版の作成に失敗した場合
     */
    public static Map<String, String> createSamples(SimServiceGrpc.SimServiceBlockingStub stub, ProtoJsonPrinter printer)
            throws IOException {
        // サーバから 2 回データを受け取る。違いは include_result(result を入れるかどうか)だけ。
        SimLog withResult = stub.getSimLog(request(true));      // result あり
        SimLog withoutResult = stub.getSimLog(request(false));  // result なし

        // 「サンプル名 → JSON にするメッセージ」の表を作る。
        // 値の型を MessageOrBuilder(全 Protobuf メッセージ共通の型)にしているので、
        // SimLog も ObjectLog も同じ表に入れられる。
        Map<String, MessageOrBuilder> samples = new LinkedHashMap<>();
        samples.put("simlog-with-result", withResult);                              // SimLog 全体(result あり)
        samples.put("simlog-without-result", withoutResult);                        // SimLog 全体(result なし)
        samples.put("objectlog-record", withoutResult.getLogs(RECORD_INDEX));       // ObjectLog 1 レコード

        // それぞれを JSON にして、「ファイル名 → 中身」の表に入れる
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, MessageOrBuilder> e : samples.entrySet()) {
            String name = e.getKey();                // 例: "simlog-with-result"
            MessageOrBuilder message = e.getValue(); // JSON にするメッセージ

            // ★実際の出力そのもの: printer.print で 1 行の JSON にする(ファイルとして末尾に改行を付ける)
            files.put(name + ".json", printer.print(message) + "\n");

            // 読みやすく整形したもの(作り方は下の pretty メソッド)
            files.put(name + ".pretty.json", pretty(printer, message) + "\n");
        }
        return files;
    }

    /**
     * GetSimLog に渡すリクエストを作る。
     *
     * @param includeResult true なら SimLog に result(実行結果)を入れてもらう
     */
    private static GetSimLogRequest request(boolean includeResult) {
        // Builder パターン: newBuilder() で組み立てを始め、値を設定し、build() で完成品を作る
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult)
                .setObjectCount(OBJECT_COUNT)
                .setEventsPerObject(EVENTS_PER_OBJECT)
                .build();
    }

    /**
     * 整形した JSON(改行とインデント入り)を作る。
     *
     * <p>ProtoJsonPrinter 自体には整形の設定が無い。代わりに、Jackson の JsonGenerator(JSON を書き出す道具)を
     * 自分で作って整形機能を有効にし、それを {@code printer.writeTo(message, generator)} に渡している。
     * こうすると、ProtoJsonPrinter はその generator を通して JSON を書くので、整形された JSON になる。
     */
    private static String pretty(ProtoJsonPrinter printer, MessageOrBuilder message) throws IOException {
        // 書き出し先: メモリ上のバイト列(最後に文字列に変換する)
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // JsonGenerator を作る。ライブラリと同じ設定にするため、ライブラリの既定の JsonFactory(generator の製造元)を使う。
        // try-with-resources なので、{ } を抜けるときに generator が自動で閉じられ、内容が out に書き出される。
        try (JsonGenerator g = ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.useDefaultPrettyPrinter();   // 整形機能を有効にする(改行とインデントが入るようになる)
            printer.writeTo(message, g);   // この generator に、message の JSON を書いてもらう
        }

        // メモリに溜まったバイト列を、UTF-8 として文字列に変換して返す
        return out.toString(StandardCharsets.UTF_8);
    }
}
