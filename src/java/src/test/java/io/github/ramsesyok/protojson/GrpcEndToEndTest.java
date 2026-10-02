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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Go の動作テスト用サーバ(src/go)から gRPC で受信したデータを JSON / NDJSON にする統合テスト(利用例を兼ねる)。
 * 接続先は system property {@code grpc.target}(既定 localhost:50051)。サーバに接続できなければスキップする。
 */
@DisplayName("gRPC 統合テスト(Go サーバ)")
class GrpcEndToEndTest {

    private static final int OBJECTS = 12;
    private static final int EVENTS = 9;
    private static final Set<String> INT64_KEYS = Set.of(
            "jobId", "timestamp", "objectId", "eventId", "eventType", "exitCode", "fromId", "toId", "execId");
    private static final Set<String> STRING_KEYS = Set.of("output", "body", "command");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ManagedChannel channel;
    private static SimServiceGrpc.SimServiceBlockingStub stub;
    private static boolean serverAvailable;

    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();
    private final JsonFormatOracle oracle = new JsonFormatOracle();

    @BeforeAll
    static void connect() {
        String target = System.getProperty("grpc.target", "localhost:50051");
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = SimServiceGrpc.newBlockingStub(channel);
        try {
            stub.withDeadlineAfter(3, TimeUnit.SECONDS).getSimLog(request(false, 0, 0));
            serverAvailable = true;
        } catch (StatusRuntimeException e) {
            System.err.println("gRPC server not available at " + target + " (integration tests skipped): " + e.getStatus());
        }
    }

    @AfterAll
    static void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static GetSimLogRequest request(boolean includeResult, int objects, int events) {
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult).setObjectCount(objects).setEventsPerObject(events).build();
    }

    @Test
    @DisplayName("SimLog 全体(result なし / あり)を 1 つの JSON に")
    void wholeSimLog() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        for (boolean includeResult : new boolean[]{false, true}) {
            SimLog simLog = stub.getSimLog(request(includeResult, OBJECTS, EVENTS));
            String json = printer.print(simLog);

            assertEquals(oracle.expected(simLog), json);
            JsonNode n = MAPPER.readTree(json);
            assertTrue(checkTypes(n) > OBJECTS * EVENTS);
            assertEquals(includeResult, n.has("result"));
            if (includeResult) {
                assertEquals(Long.MIN_VALUE, n.at("/result/exitCode").longValue());
                assertEquals("9223372036854775807", n.at("/result/output").textValue());
            }
        }
    }

    @Test
    @DisplayName("StreamObjectLogs で 1 件ずつ受信して NDJSON に(GetSimLog の logs と同じ内容)")
    void streamToNdjson() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");

        // ---- 利用例: server streaming で受信した ObjectLog を 1 件ずつ NDJSON の 1 行として書き出す ----
        ByteArrayOutputStream out = new ByteArrayOutputStream(); // 実際は HTTP 応答の OutputStream 等
        try (NdjsonWriter writer = printer.ndjsonWriter(out)) {
            Iterator<ObjectLog> it = stub.streamObjectLogs(request(false, OBJECTS, EVENTS));
            while (it.hasNext()) {
                writer.write(it.next());
            }
        }
        // -------------------------------------------------------------------------------------------

        String ndjson = out.toString(StandardCharsets.UTF_8);
        SimLog whole = stub.getSimLog(request(false, OBJECTS, EVENTS));
        assertEquals(printer.printNdjson(whole.getLogsList()), ndjson);

        String[] lines = ndjson.split("\n");
        assertEquals(OBJECTS, lines.length);
        boolean sawEscapedNewline = false;
        for (int i = 0; i < lines.length; i++) {
            assertEquals(oracle.expected(whole.getLogs(i)), lines[i]);
            checkTypes(MAPPER.readTree(lines[i]));
            sawEscapedNewline |= lines[i].contains("a\\nb");
        }
        assertTrue(sawEscapedNewline, "データに改行を含む文字列があり、1 行に収まっていること");
    }

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
