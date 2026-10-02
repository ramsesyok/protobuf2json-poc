package demo.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
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
 *   <li>Well-Known Types(google.protobuf.*)は JsonFormat で出力した断片をそのまま埋め込む(対象外)</li>
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
            writeWellKnownType(m, g);
            return;
        }
        g.writeStartObject();
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
            } else if (m.hasField(field)) {
                // proto3 の presence 無しスカラーは、デフォルト値なら hasField() が false になる
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
