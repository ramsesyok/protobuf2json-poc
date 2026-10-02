package demo.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Protobuf メッセージを JSON 化する際に、int64 系フィールドを文字列ではなく JSON 数値で出力する変換器。
 *
 * <p>Protobuf 標準 JSON マッピング({@link JsonFormat})は 64bit 整数を {@code "123"} のような文字列で出力する。
 * 本クラスは次の 2 パスで数値化する。
 * <ol>
 *   <li>{@link JsonFormat.Printer} で JSON 文字列化し、Jackson で {@link JsonNode} にパースする</li>
 *   <li>メッセージの {@link Descriptor} を再帰的にたどり、型が {@code INT64 / SINT64 / SFIXED64} の
 *       フィールドの値だけを {@link LongNode} に置き換える</li>
 * </ol>
 * JSON のキー名ではなく Descriptor の型情報で判定するため、数字だけを含む string フィールド
 * (例: {@code body = "12345"})が誤って数値化されることはない。
 *
 * <p>対応している構造: 単数フィールド、子 message、repeated(スカラー/message)、oneof 内の message
 * (未設定ならキーが無いのでスキップ)、optional message(未設定ならキーが無いのでスキップ)。
 *
 * <p><b>対象外(変換せず JsonFormat の出力のまま残す)</b>:
 * <ul>
 *   <li>uint64 / fixed64: Java の long に収まらない値(2^63 以上)があり得るため</li>
 *   <li>Well-Known Types(google.protobuf.*。{@code Int64Value} 等のラッパー型、{@code Any}、
 *       {@code Timestamp} など): JsonFormat が特殊な表現で出力するため、中には立ち入らない</li>
 *   <li>map フィールド</li>
 * </ul>
 *
 * <p>インスタンスはスレッドセーフ({@link JsonFormat.Printer} と {@link ObjectMapper} はいずれもスレッドセーフ)。
 */
public final class Int64JsonConverter {

    /** 使い回す ObjectMapper(生成コストが高いため static final)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WELL_KNOWN_TYPES_PACKAGE = "google.protobuf";

    private final JsonFormat.Printer printer;

    /** {@code JsonFormat.printer().omittingInsignificantWhitespace()} を使う既定の変換器。 */
    public Int64JsonConverter() {
        this(JsonFormat.printer().omittingInsignificantWhitespace());
    }

    /**
     * 任意の Printer を使う変換器。{@code preservingProtoFieldNames()} や
     * {@code alwaysPrintFieldsWithNoPresence()} を付けた Printer も渡せる。
     */
    public Int64JsonConverter(JsonFormat.Printer printer) {
        this.printer = printer;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 比較用: 変換しない素の JsonFormat 出力(int64 は文字列のまま)。 */
    public String toRawJson(MessageOrBuilder message) {
        try {
            return printer.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("JsonFormat failed to print message", e);
        }
    }

    /** int64 系を数値化した JsonNode を返す。 */
    public JsonNode toJsonNode(MessageOrBuilder message) {
        JsonNode node;
        try {
            node = MAPPER.readTree(toRawJson(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Jackson failed to parse JsonFormat output", e);
        }
        convertMessage(node, message.getDescriptorForType());
        return node;
    }

    /** int64 系を数値化した JSON 文字列(1 行)を返す。 */
    public String toJson(MessageOrBuilder message) {
        try {
            return MAPPER.writeValueAsString(toJsonNode(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Jackson failed to write JSON", e);
        }
    }

    /**
     * 各メッセージを 1 行 1 JSON にして改行({@code \n})連結した NDJSON(JSON Lines)を書き出す。
     * 各行の末尾に {@code \n} を付ける。文字列中の改行は JSON エスケープ({@code \n})されるため行は割れない。
     */
    public void writeNdjson(Iterable<? extends MessageOrBuilder> messages, Writer out) {
        try {
            for (MessageOrBuilder m : messages) {
                writeNdjsonLine(m, out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** NDJSON の 1 行分(末尾 {@code \n} 付き)を書き出す。server streaming で 1 件ずつ受信する場合用。 */
    public void writeNdjsonLine(MessageOrBuilder message, Writer out) throws IOException {
        out.write(toJson(message));
        out.write('\n');
    }

    public String toNdjson(Iterable<? extends MessageOrBuilder> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageOrBuilder m : messages) {
            sb.append(toJson(m)).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------

    private static void convertMessage(JsonNode node, Descriptor descriptor) {
        // 対象外: Int64Value / Any / Timestamp 等は JsonFormat の特殊表現のまま
        if (isWellKnownType(descriptor) || !(node instanceof ObjectNode obj)) {
            return;
        }
        for (FieldDescriptor field : descriptor.getFields()) {
            // JsonFormat は既定で lowerCamelCase の json_name、preservingProtoFieldNames() 時は proto 名を使う
            String key = field.getJsonName();
            JsonNode value = obj.get(key);
            if (value == null) {
                key = field.getName();
                value = obj.get(key);
            }
            // キーが無い = 未設定の optional / oneof、またはデフォルト値(0 等)で省略されたフィールド
            if (value == null || value.isNull() || field.isMapField()) {
                continue;
            }
            switch (field.getType()) {
                case INT64, SINT64, SFIXED64 -> {
                    if (field.isRepeated()) {
                        ArrayNode array = (ArrayNode) value;
                        for (int i = 0; i < array.size(); i++) {
                            array.set(i, toLongNode(array.get(i), field));
                        }
                    } else {
                        obj.set(key, toLongNode(value, field));
                    }
                }
                case MESSAGE, GROUP -> {
                    Descriptor child = field.getMessageType();
                    if (isWellKnownType(child)) {
                        // 対象外。WKT は JSON 上オブジェクトとは限らない(Int64Value は "123"、Timestamp は文字列等)
                        continue;
                    }
                    if (field.isRepeated()) {
                        for (JsonNode element : value) {
                            convertMessage(element, child);
                        }
                    } else {
                        convertMessage(value, child);
                    }
                }
                default -> {
                    // string / bool / int32 / double / enum / bytes / uint64 / fixed64 等はそのまま
                }
            }
        }
    }

    private static JsonNode toLongNode(JsonNode value, FieldDescriptor field) {
        if (value.isTextual()) {
            try {
                return LongNode.valueOf(Long.parseLong(value.textValue()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "int64 field " + field.getFullName() + " has non-integer value: " + value, e);
            }
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value;
        }
        throw new IllegalStateException(
                "Unexpected JSON value for int64 field " + field.getFullName() + ": " + value);
    }

    static boolean isWellKnownType(Descriptor descriptor) {
        return WELL_KNOWN_TYPES_PACKAGE.equals(descriptor.getFile().getPackage());
    }
}
