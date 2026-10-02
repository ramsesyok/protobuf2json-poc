package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

/**
 * テストの期待値(オラクル)。ProtoJsonPrinter とは独立した方法で「あるべき出力」を作る。
 *
 * <ol>
 *   <li>Protobuf 公式の {@code JsonFormat.printer().omittingInsignificantWhitespace()} で出力する
 *       (出力規則の正解はこれ)</li>
 *   <li>その JSON をトークン単位で読み、Descriptor をたどって int64 / sint64 / sfixed64 のフィールドの
 *       文字列値だけを数値にする。数値トークンは元の表記({@code 1.0E21} 等)をそのまま保つ</li>
 *   <li>文字列は Jackson で書き直す(エスケープ規則だけが JsonFormat と異なる。これは仕様)</li>
 * </ol>
 */
final class JsonFormatOracle {

    private static final JsonFactory FACTORY = new JsonFactory();

    private final JsonFormat.Printer printer;

    JsonFormatOracle(JsonFormat.TypeRegistry typeRegistry) {
        this.printer = JsonFormat.printer().omittingInsignificantWhitespace().usingTypeRegistry(typeRegistry);
    }

    JsonFormatOracle() {
        this(JsonFormat.TypeRegistry.getEmptyTypeRegistry());
    }

    /** JsonFormat の出力そのもの(int64 は文字列)。 */
    String raw(MessageOrBuilder m) {
        try {
            return printer.print(m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 期待される ProtoJsonPrinter の出力。 */
    String expected(MessageOrBuilder m) {
        StringWriter out = new StringWriter();
        try (JsonParser p = FACTORY.createParser(raw(m));
             JsonGenerator g = FACTORY.createGenerator(out)) {
            p.nextToken();
            copyMessage(p, g, m.getDescriptorForType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void copyMessage(JsonParser p, JsonGenerator g, Descriptor d) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT || "google.protobuf".equals(d.getFile().getPackage())) {
            copyValue(p, g); // WKT は JsonFormat の表現のまま
            return;
        }
        g.writeStartObject();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            FieldDescriptor f = findByJsonName(d, name);
            g.writeFieldName(name);
            JsonToken t = p.nextToken();
            boolean array = t == JsonToken.START_ARRAY;
            if (array) {
                g.writeStartArray();
                p.nextToken();
            }
            while (!(array && p.currentToken() == JsonToken.END_ARRAY)) {
                switch (f.getType()) {
                    case INT64, SINT64, SFIXED64 -> {
                        String text = p.getText();
                        Long.parseLong(text); // 整数であることの確認
                        g.writeNumber(text);
                    }
                    case MESSAGE -> copyMessage(p, g, f.getMessageType());
                    default -> copyValue(p, g);
                }
                if (!array) {
                    break;
                }
                p.nextToken();
            }
            if (array) {
                g.writeEndArray();
            }
        }
        g.writeEndObject();
    }

    /** 値を 1 つ(オブジェクト・配列なら丸ごと)コピーする。数値は元の表記を保つ。 */
    private static void copyValue(JsonParser p, JsonGenerator g) throws IOException {
        JsonToken t = p.currentToken();
        switch (t) {
            case START_OBJECT -> {
                g.writeStartObject();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    g.writeFieldName(p.currentName());
                    p.nextToken();
                    copyValue(p, g);
                }
                g.writeEndObject();
            }
            case START_ARRAY -> {
                g.writeStartArray();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    copyValue(p, g);
                }
                g.writeEndArray();
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> g.writeNumber(p.getText());
            case VALUE_STRING -> g.writeString(p.getText());
            case VALUE_TRUE, VALUE_FALSE -> g.writeBoolean(t == JsonToken.VALUE_TRUE);
            case VALUE_NULL -> g.writeNull();
            default -> throw new IllegalStateException("unexpected token " + t);
        }
    }

    private static FieldDescriptor findByJsonName(Descriptor d, String jsonName) {
        for (FieldDescriptor f : d.getFields()) {
            if (f.getJsonName().equals(jsonName)) {
                return f;
            }
        }
        throw new IllegalStateException("no field with json_name " + jsonName + " in " + d.getFullName());
    }
}
