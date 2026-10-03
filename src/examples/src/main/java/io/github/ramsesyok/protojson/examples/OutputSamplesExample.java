package io.github.ramsesyok.protojson.examples;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.MessageOrBuilder;
import demo.proto.GetSimLogRequest;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 *
 * <pre>
 * 実行(src/go のサーバを起動しておく):
 *   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.OutputSamplesExample \
 *       -Dexec.args="localhost:50051 samples"
 * 引数: [接続先=localhost:50051] [出力先ディレクトリ=samples]
 * </pre>
 */
public final class OutputSamplesExample {

    /** サンプルのデータ量(短く読める大きさにしている)。 */
    static final int OBJECT_COUNT = 2;
    static final int EVENTS_PER_OBJECT = 3;
    /** 1 レコードのサンプルに使う logs の位置。logs[0] は objectId が 0(キーが省略される)なので logs[1] を使う。 */
    static final int RECORD_INDEX = 1;

    private OutputSamplesExample() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String target = args.length > 0 ? args[0] : "localhost:50051";
        Path outDir = Path.of(args.length > 1 ? args[1] : "samples");

        ProtoJsonPrinter printer = ProtoJsonPrinter.create();
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        try {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);
            Map<String, String> files = createSamples(stub, printer);

            Files.createDirectories(outDir);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Files.writeString(outDir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
                System.out.println("==> " + outDir.resolve(e.getKey()));
                System.out.print(e.getValue());
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 3 種類のサンプルを作る。
     *
     * @return ファイル名 → ファイルの内容(末尾に改行を付けている)
     */
    public static Map<String, String> createSamples(SimServiceGrpc.SimServiceBlockingStub stub, ProtoJsonPrinter printer)
            throws IOException {
        SimLog withResult = stub.getSimLog(request(true));
        SimLog withoutResult = stub.getSimLog(request(false));

        Map<String, MessageOrBuilder> samples = new LinkedHashMap<>();
        samples.put("simlog-with-result", withResult);                              // SimLog 全体(result あり)
        samples.put("simlog-without-result", withoutResult);                        // SimLog 全体(result なし)
        samples.put("objectlog-record", withoutResult.getLogs(RECORD_INDEX));       // ObjectLog 1 レコード

        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, MessageOrBuilder> e : samples.entrySet()) {
            files.put(e.getKey() + ".json", printer.print(e.getValue()) + "\n");    // 実際の出力(1 行)
            files.put(e.getKey() + ".pretty.json", pretty(printer, e.getValue()) + "\n");
        }
        return files;
    }

    private static GetSimLogRequest request(boolean includeResult) {
        return GetSimLogRequest.newBuilder()
                .setIncludeResult(includeResult)
                .setObjectCount(OBJECT_COUNT)
                .setEventsPerObject(EVENTS_PER_OBJECT)
                .build();
    }

    /** 整形した JSON。Jackson の JsonGenerator の整形機能を使う(ProtoJsonPrinter 自体には整形の設定は無い)。 */
    private static String pretty(ProtoJsonPrinter printer, MessageOrBuilder message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = ProtoJsonPrinter.Builder.defaultJsonFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.useDefaultPrettyPrinter();
            printer.writeTo(message, g);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
