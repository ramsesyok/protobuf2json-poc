package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.proto.GetSimLogRequest;
import demo.proto.SimServiceGrpc;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 例の処理を Go の動作テスト用サーバ(src/go)に対して実行して確認する。
 * 接続先は system property {@code grpc.target}(既定 localhost:50051)。サーバに接続できなければスキップする。
 */
class ExamplesTest {

    private static final int OBJECTS = 10;
    private static final int EVENTS = 6;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ManagedChannel channel;
    private static SimServiceGrpc.SimServiceBlockingStub stub;
    private static boolean serverAvailable;

    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();
    private final GetSimLogRequest request = GetSimLogRequest.newBuilder()
            .setObjectCount(OBJECTS).setEventsPerObject(EVENTS).build();

    @BeforeAll
    static void connect() {
        String target = System.getProperty("grpc.target", "localhost:50051");
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = SimServiceGrpc.newBlockingStub(channel);
        try {
            stub.withDeadlineAfter(3, TimeUnit.SECONDS).getSimLog(GetSimLogRequest.getDefaultInstance());
            serverAvailable = true;
        } catch (StatusRuntimeException e) {
            System.err.println("gRPC server not available at " + target + " (tests skipped): " + e.getStatus());
        }
    }

    @AfterAll
    static void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("例 1: logs の各レコードが 1 つずつ JSON になり、int64 は数値")
    void perRecordJson() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        List<String> records = new ArrayList<>();
        int count = PerRecordJsonExample.convertEachRecord(stub, request, printer, (i, json) -> {
            assertEquals(records.size(), i);
            records.add(json);
        });

        assertEquals(OBJECTS, count);
        assertEquals(OBJECTS, records.size());
        for (String json : records) {
            assertTrue(!json.contains("\n"), "1 レコードは 1 行の JSON");
            JsonNode n = MAPPER.readTree(json);
            assertTrue(n.get("timestamp").isIntegralNumber());
            for (JsonNode event : n.get("events")) {
                // 値が 0 のフィールドはキーごと省略される仕様なので、キーがあるものだけ確認する
                for (String key : List.of("eventId", "eventType")) {
                    if (event.has(key)) {
                        assertTrue(event.get(key).isIntegralNumber(), key + " in " + event);
                    }
                }
            }
        }
        // 2 件目(i=1)の objectId は 1、timestamp は 1700000000001(Go サーバのデータ)
        JsonNode second = MAPPER.readTree(records.get(1));
        assertEquals(1, second.get("objectId").longValue());
        assertEquals(1700000000001L, second.get("timestamp").longValue());
    }

    @Test
    @DisplayName("例 2: 受信したレコードごとに NDJSON の 1 行になり、例 1 の各 JSON と同じ内容")
    void streamingNdjson() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long lines = StreamingNdjsonExample.streamToNdjson(stub, request, printer, out);

        List<String> expected = new ArrayList<>();
        PerRecordJsonExample.convertEachRecord(stub, request, printer, (i, json) -> expected.add(json));

        String ndjson = out.toString(StandardCharsets.UTF_8);
        assertEquals(OBJECTS, lines);
        assertTrue(ndjson.endsWith("\n"));
        assertEquals(String.join("\n", expected) + "\n", ndjson);
    }
}
