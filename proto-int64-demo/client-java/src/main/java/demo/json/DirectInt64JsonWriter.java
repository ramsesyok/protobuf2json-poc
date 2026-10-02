package demo.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JsonFormat を通さず、Protobuf のリフレクション API から Jackson の {@link JsonGenerator} へ直接書き出す 1 パス版(実験的)。
 * int64 / sint64 / sfixed64 は最初から JSON 数値で書く。
 *
 * <p>JsonFormat の既定 Printer({@code omittingInsignificantWhitespace()} のみ)の出力規則を再現している。
 * <ul>
 *   <li>キーは json_name(lowerCamelCase)、順序はフィールド番号順(JsonFormat と同じ。宣言順ではない)</li>
 *   <li>presence の無いスカラーはデフォルト値なら省略、repeated は空なら省略、未設定の oneof / optional は省略</li>
 *   <li>uint64 / fixed64 は JsonFormat と同じく符号なし 10 進の文字列(対象外)</li>
 *   <li>uint32 / fixed32 は符号なしの数値、enum は名前(未知の値は数値)、bytes は Base64(パディングあり)</li>
 *   <li>float / double は NaN・Infinity を文字列、それ以外を数値</li>
 *   <li>Well-Known Types(google.protobuf.*)は JsonFormat と同じ表現(対象外。int64 系ラッパーも文字列のまま)。
 *       Timestamp / Duration / ラッパー型は直接書き、それ以外(Any / Struct 等)は JsonFormat の出力を埋め込む</li>
 * </ul>
 * <b>未対応</b>: map フィールド(値があれば {@link UnsupportedOperationException})、group、
 * Printer のオプション(preservingProtoFieldNames / alwaysPrintFieldsWithNoPresence 等)。
 * 出力は {@link Int64JsonConverter#toJson}(既定 Printer)と文字列として一致することをテストで確認している。
 *
 * <p>インスタンスはスレッドセーフ。
 */
public final class DirectInt64JsonWriter {

    private static final JsonFactory FACTORY = Int64JsonConverter.mapper().getFactory();
    private static final JsonFormat.Printer WKT_PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    private static final Object NO_CASE = new Object();

    /** Descriptor ごとのフィールド番号順のフィールド一覧。 */
    private static final ConcurrentMap<Descriptor, FieldDescriptor[]> FIELDS_BY_NUMBER = new ConcurrentHashMap<>();

    public String toJson(MessageOrBuilder message) {
        StringWriter out = new StringWriter();
        try {
            writeJson(message, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** 1 メッセージ分の JSON を書き出す(改行は付けない)。Writer は閉じない。 */
    public void writeJson(MessageOrBuilder message, Writer out) throws IOException {
        try (JsonGenerator g = FACTORY.createGenerator(out)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            writeMessage(message, g);
        }
    }

    public void writeNdjsonLine(MessageOrBuilder message, Writer out) throws IOException {
        writeJson(message, out);
        out.write('\n');
    }

    public void writeNdjson(Iterable<? extends MessageOrBuilder> messages, Writer out) {
        try {
            for (MessageOrBuilder m : messages) {
                writeNdjsonLine(m, out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String toNdjson(Iterable<? extends MessageOrBuilder> messages) {
        StringWriter out = new StringWriter();
        writeNdjson(messages, out);
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------

    private static void writeMessage(MessageOrBuilder m, JsonGenerator g) throws IOException {
        Descriptor descriptor = m.getDescriptorForType();
        if (Int64JsonConverter.isWellKnownType(descriptor)) {
            if (!writeWellKnownTypeFast(m, g)) {
                writeWellKnownType(m, g);
            }
            return;
        }
        g.writeStartObject();
        Object[] oneofCases = null; // oneof の index → 設定されているメンバー(未設定は NO_CASE)。遅延計算
        for (FieldDescriptor field : fieldsByNumber(descriptor)) {
            if (field.isRepeated()) {
                int count = m.getRepeatedFieldCount(field);
                if (count == 0) {
                    continue;
                }
                if (field.isMapField()) {
                    throw new UnsupportedOperationException("map field is not supported: " + field.getFullName());
                }
                g.writeFieldName(field.getJsonName());
                g.writeStartArray();
                for (int i = 0; i < count; i++) {
                    writeValue(field, m.getRepeatedField(field, i), g);
                }
                g.writeEndArray();
            } else {
                OneofDescriptor oneof = field.getRealContainingOneof();
                if (oneof != null) {
                    // oneof は「どのメンバーが設定されているか」を 1 回だけ調べ、他のメンバーの hasField を省く
                    if (oneofCases == null) {
                        oneofCases = new Object[descriptor.getOneofCount()];
                    }
                    Object setCase = oneofCases[oneof.getIndex()];
                    if (setCase == null) {
                        FieldDescriptor set = m.getOneofFieldDescriptor(oneof);
                        setCase = set == null ? NO_CASE : set;
                        oneofCases[oneof.getIndex()] = setCase;
                    }
                    if (setCase != field) {
                        continue;
                    }
                } else if (!m.hasField(field)) {
                    // proto3 の presence 無しスカラーは、デフォルト値なら hasField() が false になる
                    continue;
                }
                g.writeFieldName(field.getJsonName());
                writeValue(field, m.getField(field), g);
            }
        }
        g.writeEndObject();
    }

    private static void writeValue(FieldDescriptor field, Object value, JsonGenerator g) throws IOException {
        switch (field.getType()) {
            case INT64, SINT64, SFIXED64 -> g.writeNumber((Long) value);
            case INT32, SINT32, SFIXED32 -> g.writeNumber((Integer) value);
            case UINT32, FIXED32 -> g.writeNumber(Integer.toUnsignedLong((Integer) value));
            case UINT64, FIXED64 -> g.writeString(Long.toUnsignedString((Long) value)); // 対象外: JsonFormat と同じ文字列
            case BOOL -> g.writeBoolean((Boolean) value);
            case STRING -> g.writeString((String) value);
            case BYTES -> g.writeString(BASE64.encodeToString(((ByteString) value).toByteArray()));
            case FLOAT -> {
                float f = (Float) value;
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    g.writeString(Float.toString(f));
                } else {
                    g.writeNumber(f);
                }
            }
            case DOUBLE -> {
                double d = (Double) value;
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    g.writeString(Double.toString(d));
                } else {
                    g.writeNumber(d);
                }
            }
            case ENUM -> {
                EnumValueDescriptor e = (EnumValueDescriptor) value;
                if ("google.protobuf.NullValue".equals(e.getType().getFullName())) {
                    g.writeNull();
                } else if (e.getIndex() == -1) {
                    g.writeNumber(e.getNumber()); // 未知の enum 値
                } else {
                    g.writeString(e.getName());
                }
            }
            case MESSAGE -> writeMessage((MessageOrBuilder) value, g);
            case GROUP -> throw new UnsupportedOperationException("group is not supported: " + field.getFullName());
        }
    }

    private static FieldDescriptor[] fieldsByNumber(Descriptor descriptor) {
        return FIELDS_BY_NUMBER.computeIfAbsent(descriptor, d -> d.getFields().stream()
                .sorted(Comparator.comparingInt(FieldDescriptor::getNumber))
                .toArray(FieldDescriptor[]::new));
    }

    /**
     * よく使う WKT を JsonFormat と同じ関数・規則で直接書く(JsonFormat の呼び出しと再パースを省く)。
     * Timestamp / Duration は JsonFormat 内部と同じ {@link Timestamps#toString} / {@link Durations#toString}。
     * ラッパー型は中の value を通常フィールドと同じ規則で書く。ただし Int64Value / UInt64Value は
     * JsonFormat と同じく文字列のまま(対象外)。
     *
     * @return 書いた場合 true。生成コード以外(DynamicMessage 等)や上記以外の WKT は false
     */
    private static boolean writeWellKnownTypeFast(MessageOrBuilder m, JsonGenerator g) throws IOException {
        if (m instanceof Timestamp t) {
            g.writeString(formatTimestamp(t));
            return true;
        }
        if (m instanceof Duration d) {
            g.writeString(Durations.toString(d));
            return true;
        }
        if (m instanceof Int64Value v) {
            g.writeString(Long.toString(v.getValue()));
            return true;
        }
        if (m instanceof UInt64Value v) {
            g.writeString(Long.toUnsignedString(v.getValue()));
            return true;
        }
        if (m instanceof Int32Value || m instanceof UInt32Value || m instanceof BoolValue || m instanceof StringValue
                || m instanceof BytesValue || m instanceof FloatValue || m instanceof DoubleValue) {
            FieldDescriptor value = m.getDescriptorForType().findFieldByNumber(1);
            writeValue(value, m.getField(value), g);
            return true;
        }
        return false;
    }

    private static final long MIN_TIMESTAMP_SECONDS = -62135596800L; // 0001-01-01T00:00:00Z
    private static final long MAX_TIMESTAMP_SECONDS = 253402300799L;  // 9999-12-31T23:59:59Z

    /**
     * {@link Timestamps#toString} と同じ RFC 3339 文字列を作る(小数部は 0 / 3 / 6 / 9 桁、末尾 Z)。
     * Timestamps.toString は内部で SimpleDateFormat を使い遅いため、java.time で組み立てる。
     * 範囲外・不正な値は Timestamps.toString に任せる(同じ例外になる)。
     */
    static String formatTimestamp(Timestamp t) {
        long seconds = t.getSeconds();
        int nanos = t.getNanos();
        if (seconds < MIN_TIMESTAMP_SECONDS || seconds > MAX_TIMESTAMP_SECONDS || nanos < 0 || nanos > 999_999_999) {
            return Timestamps.toString(t);
        }
        LocalDateTime dt = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(30);
        pad(sb, dt.getYear(), 4).append('-');
        pad(sb, dt.getMonthValue(), 2).append('-');
        pad(sb, dt.getDayOfMonth(), 2).append('T');
        pad(sb, dt.getHour(), 2).append(':');
        pad(sb, dt.getMinute(), 2).append(':');
        pad(sb, dt.getSecond(), 2);
        if (nanos != 0) {
            sb.append('.');
            if (nanos % 1_000_000 == 0) {
                pad(sb, nanos / 1_000_000, 3);
            } else if (nanos % 1_000 == 0) {
                pad(sb, nanos / 1_000, 6);
            } else {
                pad(sb, nanos, 9);
            }
        }
        return sb.append('Z').toString();
    }

    private static StringBuilder pad(StringBuilder sb, int value, int width) {
        String digits = Integer.toString(value);
        for (int i = digits.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(digits);
    }

    /** WKT は JsonFormat に任せ、その断片をトークンとしてコピーする(エスケープ規則を Jackson に揃えるため)。 */
    private static void writeWellKnownType(MessageOrBuilder m, JsonGenerator g) throws IOException {
        String json;
        try {
            json = WKT_PRINTER.print(m);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("JsonFormat failed to print " + m.getDescriptorForType().getFullName(), e);
        }
        try (JsonParser p = FACTORY.createParser(json)) {
            p.nextToken();
            g.copyCurrentStructure(p);
        }
    }
}
