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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("例 3: コミット済みの samples/ が現在の出力と一致し、整形版と値が同じで、result の有無が正しい")
    void outputSamplesAreUpToDate() throws Exception {
        assumeTrue(serverAvailable, "gRPC server not running");
        Map<String, String> generated = OutputSamplesExample.createSamples(stub, printer);
        assertEquals(6, generated.size());
        for (Map.Entry<String, String> e : generated.entrySet()) {
            Path committed = Path.of("samples", e.getKey());
            assertEquals(e.getValue(), Files.readString(committed, StandardCharsets.UTF_8),
                    committed + " が古い。OutputSamplesExample を実行して更新すること");
        }
        for (String name : List.of("simlog-with-result", "simlog-without-result", "objectlog-record")) {
            String compact = generated.get(name + ".json");
            assertEquals(1, compact.split("\n").length, "実際の出力は 1 行");
            assertEquals(MAPPER.readTree(compact), MAPPER.readTree(generated.get(name + ".pretty.json")));
        }
        assertTrue(MAPPER.readTree(generated.get("simlog-with-result.json")).has("result"));
        assertFalse(MAPPER.readTree(generated.get("simlog-without-result.json")).has("result"));
        assertEquals(1, MAPPER.readTree(generated.get("objectlog-record.json")).get("objectId").longValue());
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
