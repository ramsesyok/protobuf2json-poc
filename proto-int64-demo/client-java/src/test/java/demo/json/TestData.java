package demo.json;

import demo.proto.CommEvent;
import demo.proto.Event;
import demo.proto.ExecEvent;
import demo.proto.ObjectLog;
import demo.proto.Result;
import demo.proto.SimLog;

/** テスト用の固定データ。0 以外の値を使い、すべての int64 フィールドが JSON に出力されるようにしている。 */
final class TestData {

    static final long TWO_POW_53_PLUS_1 = 9007199254740993L;

    private TestData() {
    }

    /** oneof = comm_event。body は数字だけの文字列。 */
    static Event commEvent() {
        return Event.newBuilder()
                .setEventId(101)
                .setEventType(TWO_POW_53_PLUS_1)
                .setCommEvent(CommEvent.newBuilder()
                        .setFromId(Long.MAX_VALUE)
                        .setToId(Long.MIN_VALUE)
                        .setBody("12345"))
                .build();
    }

    /** oneof = exec_event。command / result も数字だけの文字列。 */
    static Event execEvent() {
        return Event.newBuilder()
                .setEventId(102)
                .setEventType(-7)
                .setExecEvent(ExecEvent.newBuilder()
                        .setExecId(-9007199254740993L)
                        .setCommand("-9223372036854775808")
                        .setResult("9007199254740993"))
                .build();
    }

    /** oneof 未設定。 */
    static Event noPayloadEvent() {
        return Event.newBuilder()
                .setEventId(103)
                .setEventType(3)
                .build();
    }

    /** body に改行・ダブルクォート・日本語を含む。 */
    static Event trickyStringEvent() {
        return Event.newBuilder()
                .setEventId(104)
                .setEventType(4)
                .setCommEvent(CommEvent.newBuilder()
                        .setFromId(1)
                        .setToId(2)
                        .setBody("a\nb \"quoted\" 日本語"))
                .build();
    }

    static ObjectLog objectLog1() {
        return ObjectLog.newBuilder()
                .setTimestamp(1700000000123L)
                .setObjectId(11)
                .addEvents(commEvent())
                .addEvents(execEvent())
                .addEvents(noPayloadEvent())
                .build();
    }

    static ObjectLog objectLog2() {
        return ObjectLog.newBuilder()
                .setTimestamp(-1)
                .setObjectId(Long.MAX_VALUE)
                .addEvents(trickyStringEvent())
                .build();
    }

    static Result result() {
        return Result.newBuilder()
                .setExitCode(Long.MIN_VALUE)
                .setOutput("9223372036854775807")
                .build();
    }

    static SimLog simLog(boolean includeResult) {
        SimLog.Builder b = SimLog.newBuilder()
                .setJobId(TWO_POW_53_PLUS_1)
                .addLogs(objectLog1())
                .addLogs(objectLog2());
        if (includeResult) {
            b.setResult(result());
        }
        return b.build();
    }
}
