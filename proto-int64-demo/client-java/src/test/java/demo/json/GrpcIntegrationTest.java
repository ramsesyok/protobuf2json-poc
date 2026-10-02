package demo.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static demo.json.JsonAssertions.assertInt64AreNumbersAndStringsAreStrings;
import static demo.json.JsonAssertions.assertSameExceptInt64Quotes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Go の gRPC サーバ(server-go)から受信したデータで 3 パターンを検証する。
 * 接続先は system property {@code grpc.target}(既定 localhost:50051)。サーバに接続できなければスキップ。
 */
@DisplayName("Go サーバとの統合テスト")
class GrpcIntegrationTest {

    private static final int OBJECTS = 12;
    private static final int EVENTS = 9;
    private static final ObjectMapper MAPPER = Int64JsonConverter.mapper();

    private static ManagedChannel channel;
    private static SimServiceGrpc.SimServiceBlockingStub stub;
    private static boolean serverAvailable;
    private final Int64JsonConverter converter = new Int64JsonConverter();

    @BeforeAll
    static void connect() {
        String target = System.getProperty("grpc.target", "localhost:50051");
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().maxInboundMessageSize(256 << 20).build();
        stub = SimServiceGrpc.newBlockingStub(channel);
        try {
            stub.withDeadlineAfter(3, TimeUnit.SECONDS).getSimLog(req(false, 0, 0));
            serverAvailable = true;
        } catch (StatusRuntimeException e) {
            System.err.println("gRPC server not available at " + target + ": " + e.getStatus());
            serverAvailable = false;
        }
    }

    @AfterAll
    static void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static GetSimLogRequest req(boolean includeResult, int objects, int events) {
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult).setObjectCount(objects).setEventsPerObject(events).build();
    }

    @Test
    @DisplayName("パターン1: SimLog 全体(result なし)")
    void pattern1() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        SimLog msg = stub.getSimLog(req(false, OBJECTS, EVENTS));
        JsonNode n = MAPPER.readTree(converter.toJson(msg));

        assertFalse(n.has("result"));
        assertEquals(9007199254740993L, n.get("jobId").longValue());
        assertEquals(OBJECTS, n.get("logs").size());
        int int64Count = assertInt64AreNumbersAndStringsAreStrings(n, "$");
        assertTrue(int64Count > OBJECTS * EVENTS * 2, "int64Count=" + int64Count);
        int changed = assertSameExceptInt64Quotes(MAPPER.readTree(converter.toRawJson(msg)), n, "$");
        assertEquals(int64Count, changed);

        // 0 の扱い: i=0 の objectId(0)と g=0 の eventId(0)はキー自体が無い
        assertFalse(n.at("/logs/0").has("objectId"));
        assertFalse(n.at("/logs/0/events/0").has("eventId"));

        // oneof 3 通りが含まれる(g%3: 0=comm, 1=exec, 2=未設定)
        JsonNode events = n.at("/logs/0/events");
        assertTrue(events.get(0).has("commEvent"));
        assertTrue(events.get(1).has("execEvent"));
        assertFalse(events.get(2).has("commEvent") || events.get(2).has("execEvent"));

        // round trip
        SimLog.Builder b = SimLog.newBuilder();
        JsonFormat.parser().merge(converter.toJson(msg), b);
        assertEquals(msg, b.build());
    }

    @Test
    @DisplayName("パターン2: SimLog 全体(result あり)")
    void pattern2() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        SimLog msg = stub.getSimLog(req(true, OBJECTS, EVENTS));
        JsonNode n = MAPPER.readTree(converter.toJson(msg));

        assertTrue(n.has("result"));
        assertTrue(n.at("/result/exitCode").isIntegralNumber());
        assertEquals(Long.MIN_VALUE, n.at("/result/exitCode").longValue());
        assertTrue(n.at("/result/output").isTextual());
        assertEquals("9223372036854775807", n.at("/result/output").textValue());
        assertInt64AreNumbersAndStringsAreStrings(n, "$");
        assertSameExceptInt64Quotes(MAPPER.readTree(converter.toRawJson(msg)), n, "$");
    }

    @Test
    @DisplayName("パターン3: StreamObjectLogs を 1 行 1 JSON の NDJSON に(GetSimLog の logs と一致)")
    void pattern3() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        List<ObjectLog> streamed = new ArrayList<>();
        stub.streamObjectLogs(req(false, OBJECTS, EVENTS)).forEachRemaining(streamed::add);
        String ndjson = converter.toNdjson(streamed);

        String[] lines = ndjson.substring(0, ndjson.length() - 1).split("\n", -1);
        assertEquals(OBJECTS, lines.length);
        boolean sawNewlineBody = false;
        for (String line : lines) {
            JsonNode n = MAPPER.readTree(line);
            assertInt64AreNumbersAndStringsAreStrings(n, "$");
            sawNewlineBody |= line.contains("a\\nb");
        }
        assertTrue(sawNewlineBody, "test data should include body \"a\\nb\"");

        // unary の logs 要素から作った NDJSON と同一
        SimLog whole = stub.getSimLog(req(false, OBJECTS, EVENTS));
        assertEquals(converter.toNdjson(whole.getLogsList()), ndjson);
    }

    @Test
    @DisplayName("文字列フィールドの特殊値(数字のみ・改行・ダブルクォート・日本語)が保持される")
    void specialStrings() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        SimLog msg = stub.getSimLog(req(false, OBJECTS, EVENTS));
        String json = converter.toJson(msg);
        JsonNode n = MAPPER.readTree(json);
        List<String> strings = new ArrayList<>();
        n.findValues("body").forEach(v -> strings.add(v.textValue()));
        n.findValues("command").forEach(v -> strings.add(v.textValue()));
        n.findValues("result").forEach(v -> strings.add(v.textValue()));
        assertTrue(strings.contains("12345"));
        assertTrue(strings.contains("-9223372036854775808"));
        assertTrue(strings.contains("0"));
        assertTrue(strings.contains("a\nb"));
        assertTrue(strings.contains("say \"hi\""));
        assertTrue(strings.contains("日本語テキスト"));
        assertTrue(strings.contains("<tag> & a=b 'q'"));
        assertFalse(json.contains("\n"));
    }
}
