// =====================================================================================================
// ExamplesTest: 3 つの利用例(例 1〜3)が正しく動くかを確かめるテスト
// =====================================================================================================
//
// 【テストとは】
//   プログラムが期待どおりに動くかを、プログラム(テストコード)で自動的に確かめる仕組み。
//   ここでは JUnit 5 という定番のテスト用ライブラリを使っている。
//   `mvn test` を実行すると、@Test が付いたメソッドがすべて自動で実行され、成功・失敗が表示される。
//
// 【このテストが確かめること】
//   - 例 1(PerRecordJsonExample): logs の各レコードが 1 つずつ、1 行の JSON になり、int64 が数値になっていること
//   - 例 2(StreamingNdjsonExample): 受信したレコードごとに NDJSON の 1 行になり、その内容が例 1 と同じであること
//   - 例 3(OutputSamplesExample): コミット済みの samples/ のファイルが、今のプログラムの出力と同じであること
//     (proto やライブラリを変えたのに samples/ を作り直し忘れた、という状態を見つけるため)
//
// 【前提: gRPC サーバが必要】
//   このテストは src/go の動作テスト用サーバにつないで実行する。サーバが起動していない場合は、
//   失敗(エラー)ではなく「スキップ(実行しない)」になる。
//   接続先は -Dgrpc.target=ホスト:ポート で変えられる(既定は localhost:50051)。
//
// 【実行方法】
//   cd src/go && go build -o bin/server . && ./bin/server   # 別のターミナルでサーバを起動
//   cd src/examples && mvn test
// =====================================================================================================

package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.databind.JsonNode;      // Jackson で JSON を読み込んだ結果(木構造)の 1 つの節
import com.fasterxml.jackson.databind.ObjectMapper;  // Jackson で JSON 文字列を読み込む道具
import demo.proto.GetSimLogRequest;                  // サーバに渡すリクエスト
import demo.proto.SimServiceGrpc;                    // gRPC のサービスを呼び出すためのクラス
import io.github.ramsesyok.protojson.ProtoJsonPrinter;  // 本ライブラリ: Protobuf のメッセージを JSON にする
import io.grpc.ManagedChannel;                       // gRPC の「接続」
import io.grpc.ManagedChannelBuilder;                // ManagedChannel を作るためのクラス
import io.grpc.StatusRuntimeException;               // gRPC の呼び出しが失敗したときの例外
import org.junit.jupiter.api.AfterAll;               // 「全テストの後に 1 回だけ実行する」印
import org.junit.jupiter.api.BeforeAll;              // 「全テストの前に 1 回だけ実行する」印
import org.junit.jupiter.api.DisplayName;            // テスト結果に表示する、分かりやすい名前を付ける印
import org.junit.jupiter.api.Test;                   // 「これはテストメソッドです」という印

import java.io.ByteArrayOutputStream;     // 書き出したバイト列をメモリ上に溜める OutputStream
import java.nio.charset.StandardCharsets; // 文字コードの定数(UTF_8 など)
import java.nio.file.Files;               // ファイル操作の便利メソッド集
import java.nio.file.Path;                // ファイルの場所(パス)
import java.util.ArrayList;               // 要素を追加できるリスト
import java.util.List;                    // リストを表す共通の型
import java.util.Map;                     // 「キー → 値」の表
import java.util.concurrent.TimeUnit;     // 時間の単位(秒など)

// 「import static」は、クラスの static メソッドを「クラス名.」を付けずに呼べるようにする書き方。
// 例えば Assertions.assertEquals(...) を、単に assertEquals(...) と書けるようになる。
import static org.junit.jupiter.api.Assertions.assertEquals;   // 2 つの値が等しいことを確かめる
import static org.junit.jupiter.api.Assertions.assertFalse;    // 条件が false であることを確かめる
import static org.junit.jupiter.api.Assertions.assertTrue;     // 条件が true であることを確かめる
import static org.junit.jupiter.api.Assumptions.assumeTrue;    // 条件が false ならテストをスキップする

/**
 * 例の処理を Go の動作テスト用サーバ(src/go)に対して実行して確認する。
 * 接続先は system property {@code grpc.target}(既定 localhost:50051)。サーバに接続できなければスキップする。
 */
// テストクラスは public でなくてよい(JUnit 5 では、同じパッケージから見えれば実行できる)
class ExamplesTest {

    // ---------------------------------------------------------------------------------------------
    // テスト全体で使う値
    // ---------------------------------------------------------------------------------------------

    /** サーバに要求する ObjectLog の件数。 */
    private static final int OBJECTS = 10;

    /** ObjectLog 1 件あたりの Event の件数。 */
    private static final int EVENTS = 6;

    /** JSON 文字列を読み込むための道具(Jackson)。作るのに手間がかかるので、1 つ作って使い回す。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // static なフィールドは、全テストで共有される(テストメソッドごとに作り直されない)。
    // サーバへの接続は時間がかかるので、@BeforeAll で 1 回だけ作り、全テストで使い回す。
    private static ManagedChannel channel;                     // サーバへの接続
    private static SimServiceGrpc.SimServiceBlockingStub stub; // サーバの機能を呼び出す窓口
    private static boolean serverAvailable;                    // サーバにつながったかどうか

    // static でないフィールドは、テストメソッドごとに新しく作られる(JUnit はテストごとにインスタンスを作るため)。
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();  // テスト対象の JSON 変換器
    private final GetSimLogRequest request = GetSimLogRequest.newBuilder()
            .setObjectCount(OBJECTS).setEventsPerObject(EVENTS).build(); // 各テストで使うリクエスト

    // ---------------------------------------------------------------------------------------------
    // 準備と後片付け
    // ---------------------------------------------------------------------------------------------

    /**
     * 全テストの前に 1 回だけ実行される準備処理: サーバに接続し、つながるかどうかを確かめる。
     * (@BeforeAll を付けるメソッドは static にする決まりがある)
     */
    @BeforeAll
    static void connect() {
        // System.getProperty(名前, 既定値) は、「-D名前=値」で渡された設定を読む。無ければ既定値になる。
        String target = System.getProperty("grpc.target", "localhost:50051");
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = SimServiceGrpc.newBlockingStub(channel);
        try {
            // 試しに 1 回呼び出してみて、サーバが応答するか確かめる。
            // withDeadlineAfter(3, 秒) は「3 秒以内に返事が無ければ諦める」設定(サーバが無いときに長く待たないため)。
            stub.withDeadlineAfter(3, TimeUnit.SECONDS).getSimLog(GetSimLogRequest.getDefaultInstance());
            serverAvailable = true;
        } catch (StatusRuntimeException e) {
            // サーバにつながらなかった。テストを失敗にはせず、各テストの先頭でスキップさせる(下の assumeTrue)。
            System.err.println("gRPC server not available at " + target + " (tests skipped): " + e.getStatus());
        }
    }

    /** 全テストの後に 1 回だけ実行される後片付け: サーバへの接続を閉じる。 */
    @AfterAll
    static void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------------------------------------
    // テスト本体
    //   どのテストも「(1) 実行する → (2) 結果を確かめる(assert)」の順に書いている。
    //   assertEquals(期待する値, 実際の値) は、2 つが違えばテストを失敗させる。
    //   第 3 引数の文字列は、失敗したときに表示されるメッセージ(原因を探しやすくするため)。
    // ---------------------------------------------------------------------------------------------

    /** 例 1 のテスト。 */
    @Test
    @DisplayName("例 1: logs の各レコードが 1 つずつ JSON になり、int64 は数値")
    void perRecordJson() throws Exception {
        // サーバが無ければ、ここでテストをスキップする(失敗ではない)。
        assumeTrue(serverAvailable, "gRPC server not running");

        // (1) 例 1 の処理を実行する。1 件分の JSON ができるたびに、ラムダ式 (i, json) -> { ... } が呼ばれる。
        //     ここではテストのために、受け取った JSON を records リストに溜めておく。
        List<String> records = new ArrayList<>();
        int count = PerRecordJsonExample.convertEachRecord(stub, request, printer, (i, json) -> {
            // 番号 i が 0, 1, 2, ... と順番どおりに来ていること(= リストに入っている件数と同じであること)
            assertEquals(records.size(), i);
            records.add(json);
        });

        // (2) 結果を確かめる
        // 件数: 戻り値もリストの件数も、サーバに頼んだ件数(OBJECTS)と同じであること
        assertEquals(OBJECTS, count);
        assertEquals(OBJECTS, records.size());

        for (String json : records) {
            // 1 レコードの JSON は 1 行(改行文字を含まない)であること
            assertTrue(!json.contains("\n"), "1 レコードは 1 行の JSON");

            // JSON を読み込んで、中身の「型」を確かめる。
            // isIntegralNumber() は「整数の数値か」。"123" のような文字列なら false になる。
            JsonNode n = MAPPER.readTree(json);
            assertTrue(n.get("timestamp").isIntegralNumber());   // int64 の timestamp が数値であること

            for (JsonNode event : n.get("events")) {
                // 値が 0 のフィールドはキーごと省略される仕様なので、キーがあるものだけ確認する
                // (キーが無いのに get(key) すると null が返り、そのまま使うとエラーになるため)
                for (String key : List.of("eventId", "eventType")) {
                    if (event.has(key)) {
                        assertTrue(event.get(key).isIntegralNumber(), key + " in " + event);
                    }
                }
            }
        }

        // 値そのものも確かめる。2 件目(i=1)の objectId は 1、timestamp は 1700000000001(Go サーバのデータ)。
        // longValue() は JSON の数値を Java の long(64bit 整数)として取り出す。
        JsonNode second = MAPPER.readTree(records.get(1));
        assertEquals(1, second.get("objectId").longValue());
        assertEquals(1700000000001L, second.get("timestamp").longValue()); // 末尾の L は long の数値という印
    }

    /** 例 3 のテスト。コミット済みの出力サンプルが古くなっていないかも確かめる。 */
    @Test
    @DisplayName("例 3: コミット済みの samples/ が現在の出力と一致し、整形版と値が同じで、result の有無が正しい")
    void outputSamplesAreUpToDate() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");

        // (1) 例 3 の処理を実行して、サンプル(ファイル名 → 中身)を作る。ファイルには書かず、メモリ上で比べる。
        Map<String, String> generated = OutputSamplesExample.createSamples(stub, printer);

        // (2-a) 3 種類 × 2 形式(.json と .pretty.json)= 6 ファイル分あること
        assertEquals(6, generated.size());

        // (2-b) samples/ フォルダにコミットされているファイルと、今作った中身が 1 文字も違わないこと。
        //       違う場合は、proto・Go サーバのデータ・ライブラリのどれかが変わったのに samples/ を作り直していない。
        //       (mvn test は src/examples フォルダで実行するので、Path.of("samples", ...) は src/examples/samples/... を指す)
        for (Map.Entry<String, String> e : generated.entrySet()) {
            Path committed = Path.of("samples", e.getKey());
            assertEquals(e.getValue(), Files.readString(committed, StandardCharsets.UTF_8),
                    committed + " が古い。OutputSamplesExample を実行して更新すること");
        }

        for (String name : List.of("simlog-with-result", "simlog-without-result", "objectlog-record")) {
            String compact = generated.get(name + ".json");
            // (2-c) 実際の出力(.json)は 1 行であること。
            //       split("\n") は改行で区切った配列を作る(末尾の改行の後ろの空文字は含まれない)ので、1 行なら長さ 1。
            assertEquals(1, compact.split("\n").length, "実際の出力は 1 行");
            // (2-d) 整形版(.pretty.json)は空白と改行が違うだけで、JSON として読んだ中身は同じであること。
            //       JsonNode どうしの比較は、空白や改行に関係なく「中身(キーと値)」で比べられる。
            assertEquals(MAPPER.readTree(compact), MAPPER.readTree(generated.get(name + ".pretty.json")));
        }

        // (2-e) result あり の SimLog には "result" キーがあり、result なし の SimLog には無いこと
        assertTrue(MAPPER.readTree(generated.get("simlog-with-result.json")).has("result"));
        assertFalse(MAPPER.readTree(generated.get("simlog-without-result.json")).has("result"));

        // (2-f) 1 レコードのサンプルは logs[1](objectId が 1 のレコード)であること
        assertEquals(1, MAPPER.readTree(generated.get("objectlog-record.json")).get("objectId").longValue());
    }

    /** 例 2 のテスト。例 1 と同じ内容になることも確かめる。 */
    @Test
    @DisplayName("例 2: 受信したレコードごとに NDJSON の 1 行になり、例 1 の各 JSON と同じ内容")
    void streamingNdjson() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");

        // (1) 例 2 の処理を実行する。書き出し先はファイルではなくメモリ(ByteArrayOutputStream)にして、
        //     後で中身を取り出して確かめられるようにしている。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long lines = StreamingNdjsonExample.streamToNdjson(stub, request, printer, out);

        // 比較用に、例 1 の方法でも同じデータを JSON にしておく(期待する値)
        List<String> expected = new ArrayList<>();
        PerRecordJsonExample.convertEachRecord(stub, request, printer, (i, json) -> expected.add(json));

        // (2) 結果を確かめる
        String ndjson = out.toString(StandardCharsets.UTF_8);  // メモリに溜まったバイト列を文字列にする
        assertEquals(OBJECTS, lines);           // 書いた行数 = 頼んだ件数
        assertTrue(ndjson.endsWith("\n"));      // 最後の行も改行で終わっていること
        // 例 1 の各 JSON を改行でつなぎ、最後に改行を付けたもの と、例 2 の出力が完全に同じであること。
        // String.join("\n", リスト) は、リストの要素を "\n" を挟んでつないだ 1 つの文字列を作る。
        assertEquals(String.join("\n", expected) + "\n", ndjson);
    }
}
