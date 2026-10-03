package io.github.ramsesyok.protojson.examples;

import demo.proto.GetSimLogRequest;
import demo.proto.ObjectLog;
import demo.proto.SimLog;
import demo.proto.SimServiceGrpc;
import io.github.ramsesyok.protojson.ProtoJsonPrinter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 例 1: SimLog の logs を「1 レコード(ObjectLog)ずつ」JSON 文字列にする。
 *
 * <p>GetSimLog(unary)で SimLog 全体を受信し、{@code logs} の各要素を {@link ProtoJsonPrinter#print} で
 * 1 件ずつ JSON にする。レコードごとに保存・送信したい場合(DB の 1 行、メッセージキューの 1 メッセージ、
 * 1 ファイル等)を想定している。int64 のフィールド(timestamp / objectId / eventId 等)は JSON の数値になる。
 *
 * <pre>
 * 実行(src/go のサーバを起動しておく):
 *   mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.PerRecordJsonExample \
 *       -Dexec.args="localhost:50051 3 3"
 * 引数: [接続先=localhost:50051] [ObjectLog 件数=3] [1 件あたりの Event 数=3]
 * </pre>
 */
public final class PerRecordJsonExample {

    private PerRecordJsonExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        String target = args.length > 0 ? args[0] : "localhost:50051";
        int objectCount = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int eventsPerObject = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        // ProtoJsonPrinter は不変・スレッドセーフ。アプリケーションで 1 つ作って使い回す
        ProtoJsonPrinter printer = ProtoJsonPrinter.create();

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        try {
            SimServiceGrpc.SimServiceBlockingStub stub = SimServiceGrpc.newBlockingStub(channel);
            GetSimLogRequest request = GetSimLogRequest.newBuilder()
                    .setObjectCount(objectCount)
                    .setEventsPerObject(eventsPerObject)
                    .build();

            int count = convertEachRecord(stub, request, printer, (index, json) -> {
                // 実際のアプリケーションでは、ここで保存・送信などを行う
                System.out.println("record[" + index + "] " + json);
            });
            System.out.println(count + " records converted");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * SimLog を受信し、logs の各レコードを JSON 文字列にして handler に渡す。
     *
     * @param handler (レコードの番号, そのレコードの JSON) を受け取る処理
     * @return 変換したレコード数
     */
    public static int convertEachRecord(SimServiceGrpc.SimServiceBlockingStub stub, GetSimLogRequest request,
                                        ProtoJsonPrinter printer, BiConsumer<Integer, String> handler) {
        SimLog simLog = stub.getSimLog(request);
        int index = 0;
        for (ObjectLog log : simLog.getLogsList()) {
            String json = printer.print(log); // 1 レコード → 1 つの JSON(改行なし)
            handler.accept(index++, json);
        }
        return index;
    }
}
