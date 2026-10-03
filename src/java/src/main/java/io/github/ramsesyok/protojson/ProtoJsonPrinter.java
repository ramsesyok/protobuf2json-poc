package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Protobuf メッセージを JSON に書き出す。int64 / sint64 / sfixed64 は JSON の数値で出力する。
 *
 * <p>JsonFormat を使わず、Protobuf のリフレクション API(フィールドの有無と値の取得)で message をたどり、
 * Jackson の {@link JsonGenerator} へ直接書き出す(1 パス)。JsonFormat の出力を加工する方式に比べて
 * 約 3 倍速く、メモリ割り当ても少ない。出力規則・対象外・未対応の構造はパッケージの説明
 * ({@code package-info.java})を参照。
 *
 * <p>インスタンスは不変でスレッドセーフ。アプリケーション全体で 1 つ作って使い回すことを想定している。
 *
 * <pre>{@code
 * ProtoJsonPrinter printer = ProtoJsonPrinter.create();
 * String json = printer.print(simLog);
 * printer.writeTo(simLog, httpResponseOutputStream);
 * }</pre>
 *
 * <p>例外:
 * <ul>
 *   <li>{@link UnsupportedOperationException}: map / group / extension を含む型を出力しようとした</li>
 *   <li>{@link IllegalArgumentException}: 値が JSON にできない(範囲外の Timestamp / Duration、
 *       TypeRegistry に無い型を詰めた Any 等)</li>
 *   <li>{@link IOException}: 書き出し先への書き込みに失敗した</li>
 * </ul>
 * 例外が起きた時点までの JSON は書き出し先に出力済みのことがある(途中までの不完全な JSON)。
 */
public final class ProtoJsonPrinter {

    /** oneof のどのメンバーも設定されていないことを表す印(writeMessage 内で使う)。 */
    private static final Object NO_MEMBER_SET = new Object();

    private final JsonFactory jsonFactory;
    private final WellKnownTypes wellKnownTypes;

    private ProtoJsonPrinter(Builder builder) {
        this.jsonFactory = builder.jsonFactory;
        this.wellKnownTypes = new WellKnownTypes(builder.typeRegistry, builder.jsonFactory);
    }

    /** 既定の設定で作る。 */
    public static ProtoJsonPrinter create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============================================================================================
    // 利用者向け API
    // ============================================================================================

    /**
     * message を JSON 文字列にする。
     *
     * <p>結果は常に 1 行(改行文字を含まない)。文字列フィールド中の改行は JSON のエスケープ({@code \n})になる。
     * そのため、レコードごとに DB へ保存する、1 行 1 レコードのファイル(NDJSON)に追記する、といった使い方ができる。
     */
    public String print(MessageOrBuilder message) {
        StringWriter out = new StringWriter();
        try {
            writeTo(message, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // StringWriter では発生しない
        }
        return out.toString();
    }

    /**
     * message を JSON として UTF-8 で書き出す(改行なし)。文字列を作らないので {@link #print} より効率がよい。
     * {@code out} は閉じない(flush はする)。
     */
    public void writeTo(MessageOrBuilder message, OutputStream out) throws IOException {
        try (JsonGenerator g = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            writeTo(message, g);
        }
    }

    /** message を JSON として書き出す(改行なし)。{@code out} は閉じない(flush はする)。 */
    public void writeTo(MessageOrBuilder message, Writer out) throws IOException {
        try (JsonGenerator g = jsonFactory.createGenerator(out)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            writeTo(message, g);
        }
    }

    /**
     * message を JSON の値 1 つとして既存の {@link JsonGenerator} に書き出す。
     * 他の JSON の中に埋め込む場合に使う(例: {@code {"data": <message>}})。generator の flush / close はしない。
     *
     * <pre>{@code
     * g.writeStartObject();
     * g.writeStringField("type", "simlog");
     * g.writeFieldName("data");
     * printer.writeTo(simLog, g);
     * g.writeEndObject();
     * }</pre>
     */
    public void writeTo(MessageOrBuilder message, JsonGenerator generator) throws IOException {
        Objects.requireNonNull(message, "message");
        writeMessage(message, generator);
    }

    // ============================================================================================
    // 書き出し処理
    // ============================================================================================

    private void writeMessage(MessageOrBuilder m, JsonGenerator g) throws IOException {
        Descriptor descriptor = m.getDescriptorForType();
        if (MessageLayout.isWellKnownType(descriptor)) {
            wellKnownTypes.write(m, g);
            return;
        }
        MessageLayout layout = MessageLayout.of(descriptor);

        // oneof ごとに「どのメンバーが設定されているか」を最初に必要になった時点で 1 回だけ調べて覚えておく。
        // (メンバーごとに hasField を呼ぶと、oneof の種類が多いほど無駄が増えるため)
        // 要素: null = 未確認、NO_MEMBER_SET = どれも未設定、それ以外 = 設定されているメンバーの FieldDescriptor
        Object[] oneofCases = null;

        g.writeStartObject();
        for (MessageLayout.Field field : layout.fields) {
            FieldDescriptor fd = field.descriptor;

            if (fd.isRepeated()) {
                int count = m.getRepeatedFieldCount(fd);
                if (count == 0) {
                    continue; // 空の repeated は出力しない
                }
                g.writeFieldName(field.jsonName);
                g.writeStartArray();
                for (int i = 0; i < count; i++) {
                    writeValue(fd, m.getRepeatedField(fd, i), g);
                }
                g.writeEndArray();
                continue;
            }

            if (field.oneofIndex >= 0) {
                if (oneofCases == null) {
                    oneofCases = new Object[layout.oneofCount];
                }
                Object setMember = oneofCases[field.oneofIndex];
                if (setMember == null) {
                    FieldDescriptor set = m.getOneofFieldDescriptor(fd.getRealContainingOneof());
                    setMember = set == null ? NO_MEMBER_SET : set;
                    oneofCases[field.oneofIndex] = setMember;
                }
                if (setMember != fd) {
                    continue; // この oneof では別のメンバーが設定されている(または何も設定されていない)
                }
            } else if (!m.hasField(fd)) {
                // presence を持たないスカラー(proto3 の通常のフィールド)は、デフォルト値のとき hasField が false。
                // optional / message フィールドは「設定されていない」とき false。
                continue;
            }
            g.writeFieldName(field.jsonName);
            writeValue(fd, m.getField(fd), g);
        }
        g.writeEndObject();
    }

    private void writeValue(FieldDescriptor fd, Object value, JsonGenerator g) throws IOException {
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            writeMessage((MessageOrBuilder) value, g);
        } else {
            ScalarValues.write(fd.getType(), value, g);
        }
    }

    // ============================================================================================
    // 設定
    // ============================================================================================

    /** {@link ProtoJsonPrinter} の設定。 */
    public static final class Builder {

        private JsonFormat.TypeRegistry typeRegistry = JsonFormat.TypeRegistry.getEmptyTypeRegistry();
        private JsonFactory jsonFactory = defaultJsonFactory();

        private Builder() {
        }

        /**
         * {@code google.protobuf.Any} に詰める可能性のある message 型を登録したレジストリ。
         * Any を使わなければ不要。Any の中身は JsonFormat で出力されるため、中の int64 は文字列になる。
         */
        public Builder typeRegistry(JsonFormat.TypeRegistry typeRegistry) {
            this.typeRegistry = Objects.requireNonNull(typeRegistry, "typeRegistry");
            return this;
        }

        /**
         * 使用する Jackson の {@link JsonFactory}(既定は {@link #defaultJsonFactory()})。
         * アプリケーションの ObjectMapper と設定を揃えたい場合に渡す。
         * 非 ASCII のエスケープや数値の書式に関する Feature を変えると出力が変わる点に注意。
         * 特に {@link JsonWriteFeature#COMBINE_UNICODE_SURROGATES_IN_UTF8} が無効だと、
         * OutputStream(UTF-8)へ書く場合だけ絵文字等がサロゲートペアの Unicode エスケープ(バックスラッシュ + uD83D 等の 2 つ組)になる。
         */
        public Builder jsonFactory(JsonFactory jsonFactory) {
            this.jsonFactory = Objects.requireNonNull(jsonFactory, "jsonFactory");
            return this;
        }

        /**
         * 既定の JsonFactory。Jackson の既定に対して次の 1 点だけ変えている。
         * <ul>
         *   <li>{@link JsonWriteFeature#COMBINE_UNICODE_SURROGATES_IN_UTF8} を有効化: Jackson 2.x の既定では
         *       UTF-8 で書く場合だけ BMP 外の文字(絵文字等)をサロゲートペアの Unicode エスケープで書くため、
         *       {@link #print}(Writer 経由)と {@link #writeTo(MessageOrBuilder, OutputStream)} の出力が異なってしまう。
         *       有効にすると UTF-8 でもそのまま書くので両者が一致する(JsonFormat もそのまま書く)</li>
         * </ul>
         */
        public static JsonFactory defaultJsonFactory() {
            return JsonFactory.builder()
                    .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
                    .build();
        }

        public ProtoJsonPrinter build() {
            return new ProtoJsonPrinter(this);
        }
    }
}
