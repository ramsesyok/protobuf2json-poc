// =====================================================================================================
// WellKnownTypes: Protobuf の共通の型(Well-Known Types)を JSON に書く(ライブラリ内部のクラス)
// =====================================================================================================
//
// 【Well-Known Types とは】
//   Protobuf が最初から用意している共通の型(google.protobuf パッケージ)。日時を表す Timestamp、時間の長さの Duration、
//   値を 1 つ包む Int32Value などのラッパー型、任意のメッセージを詰められる Any、任意の JSON を表す Struct など。
//   これらは JSON での表現が特別に決められている(例: Timestamp は "2023-11-14T22:13:20.123Z" という文字列)。
//
// 【このクラスの方針】
//   Protobuf 公式の JsonFormat と同じ表現にする。そのうえで、
//     - よく使う型(Timestamp / Duration / ラッパー型 / Empty)は、速さのため自分で直接書く
//     - それ以外(Any / Struct / Value / ListValue / FieldMask など)は、JsonFormat に JSON を作ってもらい、
//       その JSON を Jackson で読み直して、そのまま書き写す(エスケープの規則を他の部分と揃えるため)
//
// 【注意】
//   Well-Known Types の中身は変換の対象外。Int64Value は JsonFormat と同じく "123" の文字列のまま、
//   Any に詰めたメッセージの中の int64 も文字列のまま出力される。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonFactory;            // JsonParser / JsonGenerator を作る製造元
import com.fasterxml.jackson.core.JsonGenerator;          // JSON を少しずつ書き出す道具
import com.fasterxml.jackson.core.JsonParser;             // JSON を少しずつ読み込む道具
import com.fasterxml.jackson.core.StreamReadConstraints;  // JSON を読むときの各種上限(文字列の長さなど)
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;  // JsonFormat が出力に失敗したときの例外
import com.google.protobuf.MessageOrBuilder;               // すべての Protobuf メッセージに共通の型
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.util.Durations;    // Duration を文字列にする公式の関数
import com.google.protobuf.util.JsonFormat;   // Protobuf 公式の JSON 変換
import com.google.protobuf.util.Timestamps;   // Timestamp を文字列にする公式の関数

import java.io.IOException;
import java.time.LocalDateTime;   // 日付と時刻(Java 標準の java.time)
import java.time.ZoneOffset;      // UTC などの時差

/**
 * Well-Known Types(google.protobuf パッケージの型)の書き出し。JsonFormat と同じ表現にする。
 *
 * <ul>
 *   <li>よく使う型(Timestamp / Duration / ラッパー型 / Empty)は、性能のため直接書く</li>
 *   <li>それ以外(Any / Struct / Value / ListValue / FieldMask 等)は JsonFormat で出力した JSON を
 *       Jackson で読み直してそのままコピーする(エスケープ規則を他の部分と揃えるため)</li>
 * </ul>
 * 直接書く対象は「生成コードのクラス」(例: {@link Timestamp})のインスタンスだけ。
 * DynamicMessage や Builder で渡された場合は JsonFormat 経由になる(結果は同じ)。
 *
 * <p>WKT の中身は対象外: {@code Int64Value} は JsonFormat と同じく文字列、{@code Any} に詰めたメッセージ内の
 * int64 も文字列のまま出力される。
 *
 * <p>インスタンスは不変で、複数のスレッドから同時に使ってよい。
 */
final class WellKnownTypes {

    /** Timestamp として JSON にできる範囲(0001-01-01T00:00:00Z 〜 9999-12-31T23:59:59Z)。Timestamps と同じ。 */
    private static final long MIN_TIMESTAMP_SECONDS = -62135596800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253402300799L;

    /**
     * JsonFormat の出力を読み直すための JsonFactory。
     *
     * <p>Jackson は、外部から来る信用できない JSON への対策として、読み込む文字列の長さ(既定 2000 万文字)や
     * キー名の長さ(既定 5 万文字)に上限を設けている。ここで読むのは自分(JsonFormat)が今作った JSON なので、
     * その上限を外している。外さないと、Struct などに長い文字列が入っているときだけ出力に失敗してしまう。
     * 入れ子の深さの上限(既定 1000 段)はそのまま残す(書き出し側にも同じ上限があるため)。
     */
    private static final JsonFactory READ_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxStringLength(Integer.MAX_VALUE)
                    .maxNameLength(Integer.MAX_VALUE)
                    .build())
            .build();

    /** 直接書けない WKT(Any / Struct 等)を JSON にするための、Protobuf 公式の JSON 変換器。 */
    private final JsonFormat.Printer fallbackPrinter;

    /**
     * @param typeRegistry Any の中身の型を解決するためのレジストリ(Any を使わないなら空でよい)
     */
    WellKnownTypes(JsonFormat.TypeRegistry typeRegistry) {
        this.fallbackPrinter = JsonFormat.printer()
                .omittingInsignificantWhitespace()   // 空白・改行を入れない(1 行で出力)
                .usingTypeRegistry(typeRegistry);
    }

    /** WKT の値 m を g に書き出す。 */
    void write(MessageOrBuilder m, JsonGenerator g) throws IOException {
        if (!writeDirectly(m, g)) {
            writeViaJsonFormat(m, g);
        }
    }

    /**
     * よく使う WKT を直接書く。
     *
     * <p>{@code m instanceof Timestamp t} は「m が Timestamp なら、Timestamp 型の変数 t として使う」という書き方
     * (パターンマッチング。Java 16 以降)。
     *
     * @return 直接書いた場合 true。対象外の型なら何もせず false。
     */
    private static boolean writeDirectly(MessageOrBuilder m, JsonGenerator g) throws IOException {
        if (m instanceof Timestamp t) {
            g.writeString(formatTimestamp(t));
        } else if (m instanceof Duration d) {
            g.writeString(Durations.toString(d)); // 例: "1.500s"。範囲外は IllegalArgumentException
        } else if (m instanceof Int64Value v) {
            g.writeString(Long.toString(v.getValue())); // JsonFormat と同じく文字列(対象外)
        } else if (m instanceof UInt64Value v) {
            g.writeString(Long.toUnsignedString(v.getValue()));
        } else if (m instanceof Int32Value v) {
            g.writeNumber(v.getValue());
        } else if (m instanceof UInt32Value v) {
            g.writeNumber(Integer.toUnsignedLong(v.getValue()));
        } else if (m instanceof BoolValue v) {
            g.writeBoolean(v.getValue());
        } else if (m instanceof StringValue v) {
            g.writeString(v.getValue());
        } else if (m instanceof BytesValue v) {
            ScalarValues.write(FieldDescriptor.Type.BYTES, v.getValue(), g);
        } else if (m instanceof FloatValue v) {
            ScalarValues.write(FieldDescriptor.Type.FLOAT, v.getValue(), g);
        } else if (m instanceof DoubleValue v) {
            ScalarValues.write(FieldDescriptor.Type.DOUBLE, v.getValue(), g);
        } else if (m instanceof Empty) {
            g.writeStartObject(); // Empty は {}
            g.writeEndObject();
        } else {
            return false;
        }
        return true;
    }

    /** JsonFormat で出力し、その JSON をトークン(意味のある最小単位)ごとに読んで g にコピーする。 */
    private void writeViaJsonFormat(MessageOrBuilder m, JsonGenerator g) throws IOException {
        String json;
        try {
            json = fallbackPrinter.print(m);
        } catch (InvalidProtocolBufferException e) {
            // 主な原因: Any の中身の型が TypeRegistry に登録されていない。
            // 「値が JSON にできない」という意味なので IllegalArgumentException にして、解決方法をメッセージに書く。
            // (InvalidProtocolBufferException は IOException の一種なので、そのままだと I/O の失敗と区別できない)
            throw new IllegalArgumentException(
                    "JsonFormat could not print " + m.getDescriptorForType().getFullName()
                            + " (for google.protobuf.Any, register the packed type with ProtoJsonPrinter.Builder#typeRegistry)", e);
        }
        // try-with-resources: { } を抜けるときに parser が自動で閉じられる
        try (JsonParser p = READ_FACTORY.createParser(json)) {
            p.nextToken();               // 最初のトークンへ進む
            g.copyCurrentStructure(p);   // その値(オブジェクトなら中身全部)を g にそのまま書き写す
        }
    }

    /**
     * Timestamp を RFC 3339 形式(UTC、末尾 Z)の文字列にする。{@link Timestamps#toString} と同じ結果を返す。
     *
     * <p>小数部はナノ秒の値に応じて 0 / 3 / 6 / 9 桁(例: {@code 2023-11-14T22:13:20Z}、
     * {@code 2023-11-14T22:13:20.123Z}、{@code 2023-11-14T22:13:20.000005Z})。
     * Timestamps.toString は内部で SimpleDateFormat を使っていて遅い(1 件あたり約 1.6µs を計測)ため、
     * java.time で組み立てている。範囲外・不正な値は Timestamps.toString に任せ、同じ例外
     * ({@link IllegalArgumentException})にする。
     */
    static String formatTimestamp(Timestamp t) {
        long seconds = t.getSeconds();  // 1970-01-01T00:00:00Z からの経過秒数(それより前はマイナス)
        int nanos = t.getNanos();       // 秒未満の部分(0〜999,999,999 ナノ秒)
        if (seconds < MIN_TIMESTAMP_SECONDS || seconds > MAX_TIMESTAMP_SECONDS || nanos < 0 || nanos > 999_999_999) {
            return Timestamps.toString(t); // IllegalArgumentException を投げる
        }
        // 秒数を UTC の日付と時刻に変換する。
        // java.time と Timestamps はどちらも先発グレゴリオ暦(1582 年以前もグレゴリオ暦で数える)
        LocalDateTime dt = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        // StringBuilder に少しずつ付け足して文字列を作る(30 は最大の長さの目安。最初に確保しておくと効率がよい)
        StringBuilder sb = new StringBuilder(30);
        appendPadded(sb, dt.getYear(), 4).append('-');        // 年(4 桁)
        appendPadded(sb, dt.getMonthValue(), 2).append('-');  // 月(2 桁)
        appendPadded(sb, dt.getDayOfMonth(), 2).append('T');  // 日(2 桁)。日付と時刻の区切りは T
        appendPadded(sb, dt.getHour(), 2).append(':');        // 時
        appendPadded(sb, dt.getMinute(), 2).append(':');      // 分
        appendPadded(sb, dt.getSecond(), 2);                  // 秒
        if (nanos != 0) {
            sb.append('.');
            if (nanos % 1_000_000 == 0) {
                appendPadded(sb, nanos / 1_000_000, 3); // ミリ秒精度
            } else if (nanos % 1_000 == 0) {
                appendPadded(sb, nanos / 1_000, 6);     // マイクロ秒精度
            } else {
                appendPadded(sb, nanos, 9);             // ナノ秒精度
            }
        }
        return sb.append('Z').toString(); // 末尾の Z は UTC(協定世界時)を表す
    }

    /** value を width 桁になるよう先頭を 0 で埋めて追加する(value は 0 以上)。例: (5, 2) → "05"。 */
    private static StringBuilder appendPadded(StringBuilder sb, int value, int width) {
        String digits = Integer.toString(value);
        for (int i = digits.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(digits);
    }
}
