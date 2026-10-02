package demo.json;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import demo.proto.complextest.AlarmEvent;
import demo.proto.complextest.Attr;
import demo.proto.complextest.BlobEvent;
import demo.proto.complextest.CommEvent2;
import demo.proto.complextest.ComplexEvent;
import demo.proto.complextest.ComplexObjectLog;
import demo.proto.complextest.ComplexSimLog;
import demo.proto.complextest.ErrorEvent;
import demo.proto.complextest.ExecEvent2;
import demo.proto.complextest.Header;
import demo.proto.complextest.Level1;
import demo.proto.complextest.Level2;
import demo.proto.complextest.Level3;
import demo.proto.complextest.MetricEvent;
import demo.proto.complextest.MoveEvent;
import demo.proto.complextest.NestedEvent;
import demo.proto.complextest.NoteEvent;
import demo.proto.complextest.Position;
import demo.proto.complextest.QueueEvent;
import demo.proto.complextest.RouteEvent;
import demo.proto.complextest.StateEvent;
import demo.proto.complextest.Status;

/** complex_test.proto のデータを決定的に生成する(乱数不使用)。 */
public final class ComplexData {

    private static final long[] LONGS = {0, 1, -1, 42, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE,
            -9007199254740993L, 1234567890123L, 1700000000000L};

    private static final String[] STRINGS = {
            "12345",
            "a\nb",
            "say \"hi\"",
            "日本語テキストを含むやや長めのメッセージです。シミュレーションの状態遷移を記録しています。",
            "<tag> & a=b 'q'",
            "",
            "plain ascii text of moderate length for a log entry, describing what happened in this step",
            "-9223372036854775808",
            "/usr/local/bin/sim --config /etc/sim/config.yaml --seed 42 --verbose --output /var/log/sim/out.json",
            "ERROR: connection reset by peer (errno=104) while sending frame #12345 to node-17.cluster.local:7000",
    };

    private ComplexData() {
    }

    private static long l(int i) {
        return LONGS[Math.floorMod(i, LONGS.length)];
    }

    private static String s(int i) {
        return STRINGS[Math.floorMod(i, STRINGS.length)];
    }

    private static Position pos(int i) {
        return Position.newBuilder().setX(i * 0.5).setY(-i * 1.25).setZ(i % 7 == 0 ? 0 : 1e-3 * i).setFrame(l(i)).build();
    }

    private static Attr attr(int i) {
        return Attr.newBuilder().setKey("key" + (i % 13)).setValue(s(i)).setNum(l(i + 3)).build();
    }

    public static ComplexSimLog simLog(int objects, int eventsPerObject, boolean withTimestamp) {
        ComplexSimLog.Builder b = ComplexSimLog.newBuilder().setJobId(9007199254740993L).setJobName(s(3));
        for (int i = 0; i < objects; i++) {
            b.addLogs(objectLog(i, eventsPerObject, withTimestamp));
        }
        return b.build();
    }

    public static ComplexObjectLog objectLog(int i, int eventsPerObject, boolean withTimestamp) {
        ComplexObjectLog.Builder ol = ComplexObjectLog.newBuilder()
                .setTimestamp(1700000000000L + i)
                .setObjectId(l(i))
                .setObjectName("object-" + i + " " + s(i))
                .setHeader(Header.newBuilder()
                        .setSeq(i)
                        .setHost("node-" + (i % 32) + ".cluster.local")
                        .addTags(l(i)).addTags(l(i + 1)).addTags(l(i + 2))
                        .setPos(pos(i))
                        .setStatus(Status.forNumber(i % 4)));
        for (int a = 0; a < 5; a++) {
            ol.addAttrs(attr(i * 5 + a));
        }
        for (int j = 0; j < eventsPerObject; j++) {
            ol.addEvents(event(i * eventsPerObject + j, withTimestamp));
        }
        return ol.build();
    }

    static ComplexEvent event(int g, boolean withTimestamp) {
        int k = g / 13;
        ComplexEvent.Builder e = ComplexEvent.newBuilder().setEventId(g).setEventType(l(g + 1));
        if (withTimestamp) {
            e.setAt(Timestamp.newBuilder().setSeconds(1700000000L + g).setNanos((g % 1000) * 1000));
        }
        // 12 種の oneof + 未設定 を巡回
        switch (g % 13) {
            case 0 -> e.setComm(CommEvent2.newBuilder().setFromId(l(k)).setToId(l(k + 1)).setBody(s(k)).setChannel(k % 8));
            case 1 -> e.setExec(ExecEvent2.newBuilder().setExecId(l(k + 2)).setCommand(s(8)).setResult(s(k + 1))
                    .setElapsedNs(123456789L * (k + 1)));
            case 2 -> e.setMove(MoveEvent.newBuilder().setFrom(pos(k)).setTo(pos(k + 1)).setDurationMs(l(k + 3)).setSpeed(k * 0.1));
            case 3 -> e.setState(StateEvent.newBuilder().setBefore(Status.forNumber(k % 4)).setAfter(Status.forNumber((k + 1) % 4))
                    .addRelatedIds(l(k)).addRelatedIds(l(k + 4)).addRelatedIds(l(k + 5)).setReason(s(k + 2)));
            case 4 -> e.setError(ErrorEvent.newBuilder().setCode(-k).setMessage(s(9))
                    .addStack("at demo.Sim.step(Sim.java:" + k + ")").addStack("at demo.Sim.run(Sim.java:42)")
                    .addStack("at java.base/java.lang.Thread.run(Thread.java:1583)").setThreadId(l(k + 6)));
            case 5 -> e.setMetric(MetricEvent.newBuilder().setName("latency_ms").addValues(0.5).addValues(1.25)
                    .addValues(k * 3.0).addValues(1e-9).addValues(12345.678).setCount(l(k + 7)).setAvg(k / 3.0));
            case 6 -> e.setBlob(BlobEvent.newBuilder().setData(ByteString.copyFromUtf8(s(k) + k)).setMime("text/plain")
                    .setSize(l(k + 8)));
            case 7 -> e.setNested(NestedEvent.newBuilder().setL1(Level1.newBuilder().setId(l(k))
                    .setL2(Level2.newBuilder().setId(l(k + 1)).setL3(Level3.newBuilder().setId(l(k + 2)).setLeaf(s(k))
                            .addValues(l(k + 3)).addValues(l(k + 4))))
                    .addAttrs(attr(k)).addAttrs(attr(k + 1))));
            case 8 -> e.setAlarm(AlarmEvent.newBuilder().setAlarmId(l(k + 9)).setSeverity(k % 5).setText(s(k + 3)).setAcked(k % 2 == 0));
            case 9 -> e.setRoute(RouteEvent.newBuilder().addWaypoints(pos(k)).addWaypoints(pos(k + 1)).addWaypoints(pos(k + 2))
                    .addWaypoints(pos(k + 3)).setRouteId(l(k)));
            case 10 -> e.setQueue(QueueEvent.newBuilder().setQueue("q-" + (k % 9)).setDepth(l(k)).setEnqueued(l(k + 1))
                    .setDequeued(l(k + 2)));
            case 11 -> e.setNote(NoteEvent.newBuilder().setAuthor("operator").setText(s(k + 4)));
            default -> {
                // payload 未設定
            }
        }
        return e.build();
    }
}
