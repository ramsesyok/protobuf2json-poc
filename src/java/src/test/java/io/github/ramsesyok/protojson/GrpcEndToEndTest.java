// =====================================================================================================
// GrpcEndToEndTest: 実際に gRPC で受信したデータを JSON にする「統合テスト」(利用例を兼ねる)
// =====================================================================================================
//
// 【統合テストとは】
//   部品(ここでは ProtoJsonPrinter)だけを単独で試すのではなく、実際の使い方に近い形(Go の gRPC サーバから受信 →
//   JSON にする)で、複数の部品を組み合わせて動かすテスト。
//
// 【このテストが確かめること】
//   - SimLog 全体(result なし / あり)を JSON にした結果が、JsonFormatOracle の正解と完全に同じこと
//   - int64 のキーはすべて整数の数値、string のキーはすべて文字列であること
//   - server streaming で 1 件ずつ受信した ObjectLog を 1 件ずつ JSON にすると、
//     GetSimLog でまとめて受け取ったものと同じ内容になり、どれも 1 行であること
//
// 【前提: gRPC サーバが必要】
//   src/go の動作テスト用サーバにつないで実行する。サーバが起動していなければ、失敗ではなく「スキップ」になる。
//   接続先は -Dgrpc.target=ホスト:ポート で変えられる(既定は localhost:50051)。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.proto.GetSimLogRequest;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Go の動作テスト用サーバ(src/go)から gRPC で受信したデータを JSON にする統合テスト(利用例を兼ねる)。
 * 接続先は system property {@code grpc.target}(既定 localhost:50051)。サーバに接続できなければスキップする。
 */
@DisplayName("gRPC 統合テスト(Go サーバ)")
class GrpcEndToEndTest {

    /** サーバに要求する ObjectLog の件数。 */
    private static final int OBJECTS = 12;
    /** ObjectLog 1 件あたりの Event の件数。 */
    private static final int EVENTS = 9;
    /**
     * simlog.proto の int64 フィールドの JSON でのキー名。これらの値は必ず整数の数値でなければならない。
     * Set.of(...) は、中身を変更できない集合(同じ値を重複して持たない入れ物)を作る。
     */
    private static final Set<String> INT64_KEYS = Set.of(
            "jobId", "timestamp", "objectId", "eventId", "eventType", "exitCode", "fromId", "toId", "execId");
    /** simlog.proto の string フィールドの JSON でのキー名。これらの値は(数字だけでも)必ず文字列でなければならない。 */
    private static final Set<String> STRING_KEYS = Set.of("output", "body", "command");
    /** JSON 文字列を読み込む道具(Jackson)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // static なフィールドは全テストで共有する。サーバへの接続は時間がかかるので、@BeforeAll で 1 回だけ作る。
    private static ManagedChannel channel;
    private static SimServiceGrpc.SimServiceBlockingStub stub;
    private static boolean serverAvailable;

    /** テスト対象。 */
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();
    /** 正解(JsonFormat の出力の int64 だけを数値にしたもの)を作る道具。 */
    private final JsonFormatOracle oracle = new JsonFormatOracle();

    /**
     * 全テストの前に 1 回だけ実行: サーバに接続し、試しに 1 回呼び出して、つながるかを確かめる。
     * つながらなければ serverAvailable は false のままになり、各テストの先頭の assumeTrue でスキップされる。
     */
    @BeforeAll
    static void connect() {
        String target = System.getProperty("grpc.target", "localhost:50051");
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = SimServiceGrpc.newBlockingStub(channel);
        try {
            // withDeadlineAfter(3, 秒): 3 秒以内に返事が無ければ諦める(サーバが無いときに長く待たないため)
            stub.withDeadlineAfter(3, TimeUnit.SECONDS).getSimLog(request(false, 0, 0));
            serverAvailable = true;
        } catch (StatusRuntimeException e) {
            System.err.println("gRPC server not available at " + target + " (integration tests skipped): " + e.getStatus());
        }
    }

    /** 全テストの後に 1 回だけ実行: サーバへの接続を閉じる。 */
    @AfterAll
    static void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * サーバに渡すリクエストを作る(Builder パターン)。
     *
     * @param includeResult true なら SimLog に result を入れてもらう
     * @param objects       ObjectLog の件数
     * @param events        ObjectLog 1 件あたりの Event の件数
     */
    private static GetSimLogRequest request(boolean includeResult, int objects, int events) {
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult).setObjectCount(objects).setEventsPerObject(events).build();
    }

    /** SimLog 全体を 1 つの JSON にするテスト。result なし・ありの 2 通りを確かめる。 */
    @Test
    @DisplayName("SimLog 全体(result なし / あり)を 1 つの JSON に")
    void wholeSimLog() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        // new boolean[]{false, true} は、false と true の 2 つを持つ配列。それぞれについて同じ確認を繰り返す
        for (boolean includeResult : new boolean[]{false, true}) {
            SimLog simLog = stub.getSimLog(request(includeResult, OBJECTS, EVENTS));
            String json = printer.print(simLog);

            // (1) 正解(JsonFormat 由来)と 1 文字も違わないこと
            assertEquals(oracle.expected(simLog), json);
            JsonNode n = MAPPER.readTree(json);
            // (2) int64 のキーが数値、string のキーが文字列であること。int64 の値が十分な数(Event の数より多く)あること
            assertTrue(checkTypes(n) > OBJECTS * EVENTS);
            // (3) result を頼んだときだけ "result" キーがあること
            assertEquals(includeResult, n.has("result"));
            if (includeResult) {
                // n.at("/result/exitCode") は、JSON の中の result → exitCode をたどって値を取り出す(JSON Pointer という書き方)。
                // exitCode(int64)は long の最小値の数値、output(string)は数字だけの文字列のままであること
                assertEquals(Long.MIN_VALUE, n.at("/result/exitCode").longValue());
                assertEquals("9223372036854775807", n.at("/result/output").textValue());
            }
        }
    }

    /** server streaming で 1 件ずつ受信し、1 件ずつ JSON にするテスト(ライブラリの使い方の例も兼ねる)。 */
    @Test
    @DisplayName("StreamObjectLogs で 1 件ずつ受信して 1 レコードずつ JSON に(GetSimLog の logs と同じ内容)")
    void streamEachRecord() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");

        // ---- 利用例: server streaming で受信した ObjectLog を 1 件ずつ JSON にする(DB に保存する等) ----
        // it.hasNext() で「次の 1 件があるか」を確かめ、it.next() で次の 1 件を取り出す(届くまで待つ)
        List<String> records = new ArrayList<>();
        Iterator<ObjectLog> it = stub.streamObjectLogs(request(false, OBJECTS, EVENTS));
        while (it.hasNext()) {
            records.add(printer.print(it.next())); // 実際はここで保存・送信する
        }
        // ------------------------------------------------------------------------------------------------

        // 比較用に、同じデータを GetSimLog でまとめて受け取る(サーバは毎回同じデータを返す)
        SimLog whole = stub.getSimLog(request(false, OBJECTS, EVENTS));
        assertEquals(OBJECTS, records.size());
        boolean sawEscapedNewline = false;
        for (int i = 0; i < records.size(); i++) {
            String json = records.get(i);
            // i 件目の JSON が、まとめて受け取った logs の i 件目から作った正解と同じこと
            assertEquals(oracle.expected(whole.getLogs(i)), json);
            assertFalse(json.contains("\n"), "1 レコードは 1 行");
            checkTypes(MAPPER.readTree(json));
            // データには改行入りの文字列 "a(改行)b" がある。JSON では a\nb(\ と n の 2 文字)になっているはず。
            // |= は「どこかで一度でも true になったら true のまま」にする書き方(x = x | 条件 と同じ)
            sawEscapedNewline |= json.contains("a\\nb");
        }
        assertTrue(sawEscapedNewline, "データに改行を含む文字列があり、エスケープされていること");
    }

    // JSON の木構造を、上から下まで全部たどって確かめる。オブジェクトや配列の中にさらにオブジェクトや配列があるので、
    // 自分自身(checkTypes)を呼び出して下の階層を調べている(再帰呼び出し)。
    /** int64 のキーは整数値、string のキーは文字列であることを確認し、確認した int64 の個数を返す。 */
    private static int checkTypes(JsonNode node) {
        int count = 0;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (INT64_KEYS.contains(e.getKey())) {
                    assertTrue(e.getValue().isIntegralNumber() && e.getValue().canConvertToLong(), e.toString());
                    count++;
                } else if (STRING_KEYS.contains(e.getKey())) {
                    assertTrue(e.getValue().isTextual(), e.toString());
                }
                count += checkTypes(e.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                count += checkTypes(child);
            }
        }
        assertFalse(node.isMissingNode());
        return count;
    }
}
