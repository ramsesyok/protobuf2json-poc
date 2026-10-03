// =====================================================================================================
// TimestampFormatTest: 日時(Timestamp)を JSON の文字列にする自前の処理が、公式と同じ結果になるかを確かめるテスト
// =====================================================================================================
//
// 【背景】
//   Timestamp(日時)は JSON では "2023-11-14T22:13:20.123Z" のような文字列(RFC 3339 という形式)で表す。
//   Protobuf 公式の Timestamps.toString でもこの文字列を作れるが遅いので、本ライブラリ(WellKnownTypes)では
//   同じ結果になる処理を java.time(Java 標準の日時ライブラリ)で自前に書いている。
//   このテストは、その自前の処理(WellKnownTypes.formatTimestamp)と公式の Timestamps.toString の結果が、
//   いろいろな日時で 1 文字も違わないことを確かめる。
//
// 【文字列の形の例】
//   ナノ秒の値によって、小数部の桁数が変わる(0 桁・3 桁・6 桁・9 桁)。
//     2023-11-14T22:13:20Z            (ナノ秒が 0)
//     2023-11-14T22:13:20.123Z        (ミリ秒単位)
//     2023-11-14T22:13:20.000005Z     (マイクロ秒単位)
//     2023-11-14T22:13:20.000000001Z  (ナノ秒単位)
// =====================================================================================================

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

    // Timestamp が表せる範囲の端。1970-01-01T00:00:00Z からの秒数で表す(1970 年より前はマイナス)
    private static final long MIN = -62135596800L; // 0001-01-01T00:00:00Z
    private static final long MAX = 253402300799L; // 9999-12-31T23:59:59Z

    /**
     * 0001 年〜9999 年のいろいろな日時で、自前の書式と Timestamps.toString の結果が同じことを確かめる。
     * 乱数(シード固定)で 20 万件と、間違えやすい境界の日付(閏日・暦の切り替え・最小 / 最大)を試す。
     */
    @Test
    @DisplayName("0001〜9999 年の 20 万件(シード固定)と境界値で Timestamps.toString と一致")
    void matchesTimestampsToString() {
        // 42 は乱数のシード(同じシードなら毎回同じ日時が選ばれる)
        SplittableRandom r = new SplittableRandom(42);
        // ナノ秒の値のパターン。小数部が 0 桁・3 桁(ミリ秒)・6 桁(マイクロ秒)・9 桁(ナノ秒)のどれになるかが変わる値を含む
        int[] nanoPatterns = {0, 1, 5000, 123000000, 123456000, 123456789, 999999999, 100, 10000000};
        for (int i = 0; i < 200_000; i++) {
            // nextLong(最小, 最大+1) は、最小以上・最大以下の乱数(上限は含まないので +1 している)。
            // ナノ秒は、偶数回目は上のパターンから、奇数回目は 0〜999,999,999 の乱数から選ぶ
            long seconds = r.nextLong(MIN, MAX + 1);
            int nanos = i % 2 == 0 ? nanoPatterns[i % nanoPatterns.length] : r.nextInt(1_000_000_000);
            assertFormatsSame(seconds, nanos);
        }
        // 境界の日付: 間違えやすいところを重点的に確かめる。
        // 1582 年 10 月に、ヨーロッパでは暦がユリウス暦からグレゴリオ暦に切り替わった(10/5〜10/14 は存在しない)。
        // Timestamp では、それより前もグレゴリオ暦で数える(先発グレゴリオ暦)決まりなので、その扱いが公式と同じか確かめる。
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

    /** 範囲外・不正な Timestamp では、自前の書式も Timestamps.toString と同じ例外(IllegalArgumentException)になること。 */
    @Test
    @DisplayName("範囲外・不正な値は Timestamps.toString と同じく IllegalArgumentException")
    void invalidThrowsLikeTimestamps() {
        // 最大の 1 秒後、最小の 1 秒前、ナノ秒がマイナス、ナノ秒が 10 億(1 秒分。0〜999,999,999 でなければならない)
        for (Timestamp t : new Timestamp[]{
                Timestamp.newBuilder().setSeconds(MAX + 1).build(),
                Timestamp.newBuilder().setSeconds(MIN - 1).build(),
                Timestamp.newBuilder().setSeconds(0).setNanos(-1).build(),
                Timestamp.newBuilder().setSeconds(0).setNanos(1_000_000_000).build()}) {
            assertThrows(IllegalArgumentException.class, () -> Timestamps.toString(t));
            assertThrows(IllegalArgumentException.class, () -> WellKnownTypes.formatTimestamp(t));
        }
    }

    /**
     * 1 つの日時について、自前の書式と Timestamps.toString の結果が同じことを確かめる。
     * assertEquals の第 3 引数の「() -> ...」は、失敗したときだけ作られるメッセージ(どの値で失敗したか)。
     */
    private static void assertFormatsSame(long seconds, int nanos) {
        Timestamp t = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
        assertEquals(Timestamps.toString(t), WellKnownTypes.formatTimestamp(t), () -> seconds + "s " + nanos + "ns");
    }
}
