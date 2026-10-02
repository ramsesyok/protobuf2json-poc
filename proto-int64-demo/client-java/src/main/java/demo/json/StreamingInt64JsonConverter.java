package demo.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link Int64JsonConverter} のストリーミング版。
 *
 * <p>JsonFormat の出力を Jackson の {@link JsonParser} でトークン単位に読み、Descriptor を追いながら
 * {@link JsonGenerator} へそのままコピーする。型が {@code INT64 / SINT64 / SFIXED64} のフィールドの値だけ
 * 文字列トークンを数値として書き出す。JsonNode ツリーを作らないので、割り当てと走査のコストが減る。
 *
 * <p>JsonFormat を 1 回通すので、フィールド名・デフォルト値の省略・oneof・WKT の表現などは
 * Printer の設定どおり(preservingProtoFieldNames / alwaysPrintFieldsWithNoPresence も可)。
 * 対象外の型(uint64 / fixed64、Well-Known Types、map)は {@link Int64JsonConverter} と同じく変換しない。
 * 出力は {@link Int64JsonConverter#toJson} と文字列として一致する(同じ Jackson の JsonFactory を使うため)。
 *
 * <p>インスタンスはスレッドセーフ。
 */
public final class StreamingInt64JsonConverter {

    private static final JsonFactory FACTORY = Int64JsonConverter.mapper().getFactory();

    /** Descriptor ごとの「JSON キー → FieldDescriptor」索引(json_name と proto 名の両方を登録)。 */
    private static final ConcurrentMap<Descriptor, Map<String, FieldDescriptor>> FIELD_INDEX = new ConcurrentHashMap<>();

    private final JsonFormat.Printer printer;

    public StreamingInt64JsonConverter() {
        this(JsonFormat.printer().omittingInsignificantWhitespace());
    }

    public StreamingInt64JsonConverter(JsonFormat.Printer printer) {
        this.printer = printer;
    }

    public String toJson(MessageOrBuilder message) {
        String raw = print(message);
        StringWriter out = new StringWriter(raw.length());
        try {
            transcode(raw, message.getDescriptorForType(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** 1 メッセージ分の JSON を書き出す(改行は付けない)。Writer は閉じない。 */
    public void writeJson(MessageOrBuilder message, Writer out) throws IOException {
        transcode(print(message), message.getDescriptorForType(), out);
    }

    /** NDJSON の 1 行分(末尾 {@code \n} 付き)を書き出す。 */
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

    private String print(MessageOrBuilder message) {
        try {
            return printer.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("JsonFormat failed to print message", e);
        }
    }

    private static void transcode(String raw, Descriptor descriptor, Writer out) throws IOException {
        try (JsonParser p = FACTORY.createParser(raw);
             JsonGenerator g = FACTORY.createGenerator(out)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            p.nextToken();
            copyMessage(p, g, descriptor);
        }
    }

    /** 現在のトークンが message の値(通常 START_OBJECT)である状態で呼ぶ。終了時は対応する END_OBJECT 上にいる。 */
    private static void copyMessage(JsonParser p, JsonGenerator g, Descriptor descriptor) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT || Int64JsonConverter.isWellKnownType(descriptor)) {
            g.copyCurrentStructure(p); // 対象外: WKT は JsonFormat の特殊表現のまま
            return;
        }
        Map<String, FieldDescriptor> index = fieldIndex(descriptor);
        g.writeStartObject();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            g.writeFieldName(name);
            JsonToken value = p.nextToken();
            FieldDescriptor field = index.get(name);
            if (field == null || field.isMapField() || value == JsonToken.VALUE_NULL) {
                g.copyCurrentStructure(p);
                continue;
            }
            switch (field.getType()) {
                case INT64, SINT64, SFIXED64 -> {
                    if (value == JsonToken.START_ARRAY) {
                        g.writeStartArray();
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            writeInt64(p, g, field);
                        }
                        g.writeEndArray();
                    } else {
                        writeInt64(p, g, field);
                    }
                }
                case MESSAGE, GROUP -> {
                    Descriptor child = field.getMessageType();
                    if (value == JsonToken.START_ARRAY) {
                        g.writeStartArray();
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            copyMessage(p, g, child);
                        }
                        g.writeEndArray();
                    } else {
                        copyMessage(p, g, child);
                    }
                }
                default -> g.copyCurrentStructure(p);
            }
        }
        g.writeEndObject();
    }

    private static void writeInt64(JsonParser p, JsonGenerator g, FieldDescriptor field) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            String text = p.getText();
            try {
                g.writeNumber(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "int64 field " + field.getFullName() + " has non-integer value: " + text, e);
            }
        } else if (t == JsonToken.VALUE_NUMBER_INT) {
            g.copyCurrentEvent(p);
        } else {
            throw new IllegalStateException("Unexpected JSON token for int64 field " + field.getFullName() + ": " + t);
        }
    }

    private static Map<String, FieldDescriptor> fieldIndex(Descriptor descriptor) {
        return FIELD_INDEX.computeIfAbsent(descriptor, d -> {
            Map<String, FieldDescriptor> m = new HashMap<>();
            for (FieldDescriptor f : d.getFields()) {
                m.put(f.getName(), f);
                m.put(f.getJsonName(), f); // json_name を優先
            }
            return Map.copyOf(m);
        });
    }
}
