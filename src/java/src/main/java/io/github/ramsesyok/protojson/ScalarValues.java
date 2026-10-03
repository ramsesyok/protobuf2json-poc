package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import java.io.IOException;
import java.util.Base64;

/**
 * message 以外の値(スカラー・enum・bytes)を JSON に書き出す規則。
 *
 * <p><b>int64 / sint64 / sfixed64 を JSON の数値で書く</b>点を除き、JsonFormat の既定と同じ表現にしている。
 * 値の Java 型は {@code FieldDescriptor#getType()} ごとに Protobuf のリフレクション API の仕様で決まっている
 * (int64 系は {@link Long}、int32 系は {@link Integer}、enum は {@link EnumValueDescriptor} 等)。
 */
final class ScalarValues {

    private static final Base64.Encoder BASE64 = Base64.getEncoder(); // JsonFormat と同じ標準 Base64(パディングあり)

    private ScalarValues() {
    }

    /**
     * 値を 1 つ書き出す。
     *
     * @param type  フィールドの型({@code MESSAGE} / {@code GROUP} 以外)
     * @param value リフレクション API({@code getField} / {@code getRepeatedField})が返した値
     */
    static void write(FieldDescriptor.Type type, Object value, JsonGenerator g) throws IOException {
        switch (type) {
            // ★ 本ライブラリの目的: 64bit 符号付き整数は JSON の数値(JsonFormat は "123" のような文字列)
            case INT64, SINT64, SFIXED64 -> g.writeNumber((long) (Long) value);

            case INT32, SINT32, SFIXED32 -> g.writeNumber((int) (Integer) value);
            // 符号なし 32bit は Java の int に符号付きで入っているので、long に広げてから書く
            case UINT32, FIXED32 -> g.writeNumber(Integer.toUnsignedLong((Integer) value));
            // 符号なし 64bit は対象外。JsonFormat と同じく符号なし 10 進の文字列にする
            case UINT64, FIXED64 -> g.writeString(Long.toUnsignedString((Long) value));

            case BOOL -> g.writeBoolean((Boolean) value);
            case STRING -> g.writeString((String) value);
            case BYTES -> g.writeString(BASE64.encodeToString(((ByteString) value).toByteArray()));
            case FLOAT -> writeFloat((Float) value, g);
            case DOUBLE -> writeDouble((Double) value, g);
            case ENUM -> writeEnum((EnumValueDescriptor) value, g);
            case MESSAGE, GROUP -> throw new IllegalArgumentException("not a scalar type: " + type);
        }
    }

    /** NaN / ±Infinity は JSON の数値で表せないので、JsonFormat と同じく "NaN" / "Infinity" / "-Infinity" の文字列。 */
    private static void writeFloat(float f, JsonGenerator g) throws IOException {
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            g.writeString(Float.toString(f));
        } else {
            g.writeNumber(f); // Jackson の既定では Float.toString と同じ表記(JsonFormat と同じ)
        }
    }

    private static void writeDouble(double d, JsonGenerator g) throws IOException {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            g.writeString(Double.toString(d));
        } else {
            g.writeNumber(d); // Jackson の既定では Double.toString と同じ表記(JsonFormat と同じ)
        }
    }

    private static void writeEnum(EnumValueDescriptor e, JsonGenerator g) throws IOException {
        if ("google.protobuf.NullValue".equals(e.getType().getFullName())) {
            g.writeNull(); // google.protobuf.NullValue は JSON の null
        } else if (e.getIndex() == -1) {
            // proto3 の enum は定義に無い数値も保持できる。名前が無いので数値で出す(JsonFormat と同じ)
            g.writeNumber(e.getNumber());
        } else {
            g.writeString(e.getName());
        }
    }
}
