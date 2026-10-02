package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 */
final class WellKnownTypes {

    /** Timestamp として JSON にできる範囲(0001-01-01T00:00:00Z 〜 9999-12-31T23:59:59Z)。Timestamps と同じ。 */
    private static final long MIN_TIMESTAMP_SECONDS = -62135596800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253402300799L;

    private final JsonFormat.Printer fallbackPrinter;
    private final JsonFactory jsonFactory;

    /**
     * @param typeRegistry Any の中身の型を解決するためのレジストリ(Any を使わないなら空でよい)
     * @param jsonFactory  JsonFormat の出力を読み直すための JsonFactory
     */
    WellKnownTypes(JsonFormat.TypeRegistry typeRegistry, JsonFactory jsonFactory) {
        this.fallbackPrinter = JsonFormat.printer()
                .omittingInsignificantWhitespace()
                .usingTypeRegistry(typeRegistry);
        this.jsonFactory = jsonFactory;
    }

    void write(MessageOrBuilder m, JsonGenerator g) throws IOException {
        if (!writeDirectly(m, g)) {
            writeViaJsonFormat(m, g);
        }
    }

    /** @return 直接書いた場合 true。対象外の型なら何もせず false。 */
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
            g.writeStartObject();
            g.writeEndObject();
        } else {
            return false;
        }
        return true;
    }

    /** JsonFormat で出力し、その JSON をトークン単位で g にコピーする。 */
    private void writeViaJsonFormat(MessageOrBuilder m, JsonGenerator g) throws IOException {
        String json;
        try {
            json = fallbackPrinter.print(m);
        } catch (InvalidProtocolBufferException e) {
            // 主な原因: Any の中身の型が TypeRegistry に登録されていない
            throw new IllegalArgumentException(
                    "JsonFormat could not print " + m.getDescriptorForType().getFullName()
                            + " (for google.protobuf.Any, register the packed type with ProtoJsonPrinter.Builder#typeRegistry)", e);
        }
        try (JsonParser p = jsonFactory.createParser(json)) {
            p.nextToken();
            g.copyCurrentStructure(p);
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
        long seconds = t.getSeconds();
        int nanos = t.getNanos();
        if (seconds < MIN_TIMESTAMP_SECONDS || seconds > MAX_TIMESTAMP_SECONDS || nanos < 0 || nanos > 999_999_999) {
            return Timestamps.toString(t); // IllegalArgumentException を投げる
        }
        // java.time と Timestamps はどちらも先発グレゴリオ暦(1582 年以前もグレゴリオ暦で数える)
        LocalDateTime dt = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(30);
        appendPadded(sb, dt.getYear(), 4).append('-');
        appendPadded(sb, dt.getMonthValue(), 2).append('-');
        appendPadded(sb, dt.getDayOfMonth(), 2).append('T');
        appendPadded(sb, dt.getHour(), 2).append(':');
        appendPadded(sb, dt.getMinute(), 2).append(':');
        appendPadded(sb, dt.getSecond(), 2);
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
        return sb.append('Z').toString();
    }

    /** value を width 桁になるよう先頭を 0 で埋めて追加する(value は 0 以上)。 */
    private static StringBuilder appendPadded(StringBuilder sb, int value, int width) {
        String digits = Integer.toString(value);
        for (int i = digits.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(digits);
    }
}
