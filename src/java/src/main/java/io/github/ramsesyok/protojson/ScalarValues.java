// =====================================================================================================
// ScalarValues: message 以外の値(数値・文字列・真偽値・enum・bytes)を JSON に書く規則(ライブラリ内部のクラス)
// =====================================================================================================
//
// 【役割】
//   フィールドの型ごとに、値を JSON でどう表すかを決めている。
//   ★本ライブラリの目的である「int64 / sint64 / sfixed64 を JSON の数値で書く」処理はここにある。
//   それ以外は Protobuf 公式の JsonFormat と同じ表現にしている。
//
// 【型と JSON の表現の対応】
//     int64 / sint64 / sfixed64 → 数値          例: 9223372036854775807   (JsonFormat は "9223372036854775807")
//     int32 / sint32 / sfixed32 → 数値          例: -5
//     uint32 / fixed32          → 符号なしの数値 例: 4294967295
//     uint64 / fixed64          → 符号なしの文字列 例: "18446744073709551615"(対象外。JsonFormat と同じ)
//     bool                      → true / false
//     string                    → 文字列(改行などはエスケープされる)
//     bytes                     → Base64 の文字列 例: "YQ=="
//     float / double            → 数値。NaN / Infinity / -Infinity は文字列
//     enum                      → 値の名前の文字列 例: "COLOR_RED"(定義に無い数値は数値のまま)
//
// 【Java で値がどんな型で渡されるか】
//   Protobuf のリフレクション API(getField など)は、値を Object 型で返す。中身の型はフィールドの型で決まっている
//   (int64 系なら Long、int32 系なら Integer、string なら String、enum なら EnumValueDescriptor など)。
//   そのため、(Long) value のようにキャスト(型の変換)してから使う。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonGenerator;                // JSON を少しずつ書き出す道具(Jackson)
import com.google.protobuf.ByteString;                          // Protobuf の bytes 型の値
import com.google.protobuf.Descriptors.EnumValueDescriptor;     // enum の値 1 つ分の説明書(名前と番号)
import com.google.protobuf.Descriptors.FieldDescriptor;         // フィールドの説明書(ここでは型の種類に使う)

import java.io.IOException;  // 書き出しの失敗を表す例外
import java.util.Base64;     // Base64 への変換(Java 標準)

/**
 * message 以外の値(スカラー・enum・bytes)を JSON に書き出す規則。
 *
 * <p><b>int64 / sint64 / sfixed64 を JSON の数値で書く</b>点を除き、JsonFormat の既定と同じ表現にしている。
 * 値の Java 型は {@code FieldDescriptor#getType()} ごとに Protobuf のリフレクション API の仕様で決まっている
 * (int64 系は {@link Long}、int32 系は {@link Integer}、enum は {@link EnumValueDescriptor} 等)。
 */
final class ScalarValues {

    /** Base64 への変換器。JsonFormat と同じ標準 Base64(パディングの = あり)。スレッドセーフなので共有してよい。 */
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    // static メソッドだけの道具箱なので、インスタンスを作れないようにコンストラクタを private にしている
    private ScalarValues() {
    }

    /**
     * 値を 1 つ書き出す。
     *
     * @param type  フィールドの型({@code MESSAGE} / {@code GROUP} 以外)
     * @param value リフレクション API({@code getField} / {@code getRepeatedField})が返した値
     * @param g     書き出し先
     * @throws IOException 書き出しに失敗した場合
     */
    static void write(FieldDescriptor.Type type, Object value, JsonGenerator g) throws IOException {
        // switch 文(case 型 -> 処理): 型ごとに書き方を分ける。Java 14 以降の書き方で、break を書かなくてよい
        switch (type) {
            // ★ 本ライブラリの目的: 64bit 符号付き整数は JSON の数値(JsonFormat は "123" のような文字列)
            //   (long) (Long) value は、Object → Long → long(基本型)と 2 段階で変換している
            case INT64, SINT64, SFIXED64 -> g.writeNumber((long) (Long) value);

            case INT32, SINT32, SFIXED32 -> g.writeNumber((int) (Integer) value);
            // 符号なし 32bit は Java の int に符号付きで入っている(4294967295 は -1 として入っている)ので、
            // Integer.toUnsignedLong で「符号なしとして読んだ値」の long に広げてから書く
            case UINT32, FIXED32 -> g.writeNumber(Integer.toUnsignedLong((Integer) value));
            // 符号なし 64bit は対象外。JsonFormat と同じく符号なし 10 進の文字列にする
            // (2^63 以上の値は、受け側の 64bit 符号付き整数に入らないため数値にしない)
            case UINT64, FIXED64 -> g.writeString(Long.toUnsignedString((Long) value));

            case BOOL -> g.writeBoolean((Boolean) value);
            case STRING -> g.writeString((String) value);  // 改行・ダブルクォート等のエスケープは Jackson が行う
            case BYTES -> g.writeString(BASE64.encodeToString(((ByteString) value).toByteArray()));
            case FLOAT -> writeFloat((Float) value, g);
            case DOUBLE -> writeDouble((Double) value, g);
            case ENUM -> writeEnum((EnumValueDescriptor) value, g);
            // message はこのクラスでは扱わない(ProtoJsonPrinter が入れ子として処理する)。来たら呼び出し側の誤り
            case MESSAGE, GROUP -> throw new IllegalArgumentException("not a scalar type: " + type);
        }
    }

    /** NaN / ±Infinity は JSON の数値で表せないので、JsonFormat と同じく "NaN" / "Infinity" / "-Infinity" の文字列。 */
    private static void writeFloat(float f, JsonGenerator g) throws IOException {
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            g.writeString(Float.toString(f));  // Float.toString は "NaN" / "Infinity" / "-Infinity" を返す
        } else {
            g.writeNumber(f); // Jackson の既定では Float.toString と同じ表記(JsonFormat と同じ)
        }
    }

    /** double 版(考え方は float と同じ)。 */
    private static void writeDouble(double d, JsonGenerator g) throws IOException {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            g.writeString(Double.toString(d));
        } else {
            g.writeNumber(d); // Jackson の既定では Double.toString と同じ表記(JsonFormat と同じ)
        }
    }

    /** enum の値を書く。 */
    private static void writeEnum(EnumValueDescriptor e, JsonGenerator g) throws IOException {
        if ("google.protobuf.NullValue".equals(e.getType().getFullName())) {
            g.writeNull(); // google.protobuf.NullValue は JSON の null
        } else if (e.getIndex() == -1) {
            // proto3 の enum は定義に無い数値も保持できる。名前が無いので数値で出す(JsonFormat と同じ)。
            // 定義に無い値は getIndex()(定義の中での位置)が -1 になる
            g.writeNumber(e.getNumber());
        } else {
            g.writeString(e.getName());
        }
    }
}
