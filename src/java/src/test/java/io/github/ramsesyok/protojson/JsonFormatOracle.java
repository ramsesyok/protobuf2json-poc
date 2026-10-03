// =====================================================================================================
// JsonFormatOracle: テストの「正解」を作る道具(テストそのものではない)
// =====================================================================================================
//
// 【オラクルとは】
//   テストでは「プログラムの出力」と「正解」を比べる。その正解を作る仕組みを、テストの世界では「オラクル(神託)」と呼ぶ。
//   正解をテスト対象と同じコードで作ってしまうと、両方に同じバグがあっても気付けない。
//   そこで、テスト対象(ProtoJsonPrinter)とはまったく別の方法で正解を作る。
//
// 【正解の作り方】
//   1. Protobuf 公式の JsonFormat でメッセージを JSON にする(JSON の出力規則の「正解」はこれ。ただし int64 は "123" の文字列)
//   2. その JSON を先頭から 1 つずつ(トークン単位で)読みながら、そのまま書き写す
//   3. 書き写すときに、int64 / sint64 / sfixed64 のフィールドの値だけ、"123"(文字列)を 123(数値)に変える
//   → これが「JsonFormat の出力の int64 だけを数値にしたもの」= ProtoJsonPrinter が出すべき出力
//
// 【トークンとは】
//   JSON を意味のある最小単位に区切ったもの。{"a":[1,"x"]} なら、
//   {(オブジェクト開始) "a"(キー) [(配列開始) 1(数値) "x"(文字列) ](配列終了) }(オブジェクト終了) の 7 つ。
//   Jackson の JsonParser は、nextToken() を呼ぶたびに次のトークンへ進む。
// =====================================================================================================

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

    /** JsonParser(読む道具)と JsonGenerator(書く道具)を作る製造元。 */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Protobuf 公式の JSON 変換器(1 行で出力する設定)。 */
    private final JsonFormat.Printer printer;

    /**
     * @param typeRegistry Any の中身の型を登録した登録簿(Any を含むメッセージを扱う場合に必要)
     */
    JsonFormatOracle(JsonFormat.TypeRegistry typeRegistry) {
        this.printer = JsonFormat.printer().omittingInsignificantWhitespace().usingTypeRegistry(typeRegistry);
    }

    /** Any を扱わない場合のコンストラクタ(空の登録簿を使う)。 */
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

    /**
     * メッセージ 1 つ分の JSON を、p(読む側)から g(書く側)へ書き写す。int64 系のフィールドだけ数値に変える。
     *
     * <p>呼ばれたとき、p はそのメッセージの最初のトークン(通常は {)を指している。
     * 入れ子のメッセージがあれば、自分自身(copyMessage)を呼び出して書き写す(再帰呼び出し)。
     *
     * @param d このメッセージの型情報(Descriptor)。どのフィールドが int64 かを調べるのに使う
     */
    private static void copyMessage(JsonParser p, JsonGenerator g, Descriptor d) throws IOException {
        // Well-Known Types(google.protobuf の型)は、JsonFormat の特別な表現のまま書き写す(int64 の変換もしない)
        if (p.currentToken() != JsonToken.START_OBJECT || "google.protobuf".equals(d.getFile().getPackage())) {
            copyValue(p, g); // WKT は JsonFormat の表現のまま
            return;
        }
        g.writeStartObject();
        // キー(FIELD_NAME)が続く間、「キー: 値」を 1 組ずつ書き写す。キーが無くなったら(} に来たら)終わる
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            // キーの名前から、そのフィールドの型情報を探す
            FieldDescriptor f = findByJsonName(d, name);
            g.writeFieldName(name);
            JsonToken t = p.nextToken();
            // 値が [ で始まるなら repeated(配列)。配列なら要素ごとに、そうでなければ 1 回だけ下の処理をする
            boolean array = t == JsonToken.START_ARRAY;
            if (array) {
                g.writeStartArray();
                p.nextToken();
            }
            while (!(array && p.currentToken() == JsonToken.END_ARRAY)) {
                switch (f.getType()) {
                    // ★int64 系: JsonFormat は "123" と書くので、中身の 123 を数値として書く
                    case INT64, SINT64, SFIXED64 -> {
                        String text = p.getText();
                        Long.parseLong(text); // 整数であることの確認
                        g.writeNumber(text);
                    }
                    // 入れ子のメッセージ: 再帰呼び出しで書き写す
                    case MESSAGE -> copyMessage(p, g, f.getMessageType());
                    // それ以外(文字列・真偽値・float など): そのまま書き写す
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

    /** JSON のキー名(json_name)から、フィールドの型情報を探す。見つからなければ例外(テストデータか仕組みの誤り)。 */
    private static FieldDescriptor findByJsonName(Descriptor d, String jsonName) {
        for (FieldDescriptor f : d.getFields()) {
            if (f.getJsonName().equals(jsonName)) {
                return f;
            }
        }
        throw new IllegalStateException("no field with json_name " + jsonName + " in " + d.getFullName());
    }
}
