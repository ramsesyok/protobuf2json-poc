package io.github.ramsesyok.protojson;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 自前の Timestamp 書式({@code WellKnownTypes.formatTimestamp})が、JsonFormat が内部で使う
 * {@link Timestamps#toString} と同じ文字列を返すことを確認する。
 */
class TimestampFormatTest {

    private static final long MIN = -62135596800L; // 0001-01-01T00:00:00Z
    private static final long MAX = 253402300799L; // 9999-12-31T23:59:59Z

    @Test
    @DisplayName("0001〜9999 年の 20 万件(シード固定)と境界値で Timestamps.toString と一致")
    void matchesTimestampsToString() {
        SplittableRandom r = new SplittableRandom(42);
        int[] nanoPatterns = {0, 1, 5000, 123000000, 123456000, 123456789, 999999999, 100, 10000000};
        for (int i = 0; i < 200_000; i++) {
            long seconds = r.nextLong(MIN, MAX + 1);
            int nanos = i % 2 == 0 ? nanoPatterns[i % nanoPatterns.length] : r.nextInt(1_000_000_000);
            assertFormatsSame(seconds, nanos);
        }
        long[] edges = {MIN, MIN + 1, -1, 0, 1,
                951782400L,     // 2000-02-29(閏日)
                4107542400L,    // 2100-03-01(2100 年は閏年でない)
                -2208988800L,   // 1900-01-01
                -12219292800L,  // 1582-10-15(グレゴリオ暦の開始日)
                -12219379200L,  // 1582-10-14(先発グレゴリオ暦の扱いの確認)
                -12220156800L,  // 1582-10-05
                MAX - 1, MAX};
        for (long s : edges) {
            for (int n : nanoPatterns) {
                assertFormatsSame(s, n);
            }
        }
    }

    @Test
    @DisplayName("範囲外・不正な値は Timestamps.toString と同じく IllegalArgumentException")
    void invalidThrowsLikeTimestamps() {
        for (Timestamp t : new Timestamp[]{
                Timestamp.newBuilder().setSeconds(MAX + 1).build(),
                Timestamp.newBuilder().setSeconds(MIN - 1).build(),
                Timestamp.newBuilder().setSeconds(0).setNanos(-1).build(),
                Timestamp.newBuilder().setSeconds(0).setNanos(1_000_000_000).build()}) {
            assertThrows(IllegalArgumentException.class, () -> Timestamps.toString(t));
            assertThrows(IllegalArgumentException.class, () -> WellKnownTypes.formatTimestamp(t));
        }
    }

    private static void assertFormatsSame(long seconds, int nanos) {
        Timestamp t = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
        assertEquals(Timestamps.toString(t), WellKnownTypes.formatTimestamp(t), () -> seconds + "s " + nanos + "ns");
    }
}
