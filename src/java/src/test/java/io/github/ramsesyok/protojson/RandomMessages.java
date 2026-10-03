// =====================================================================================================
// RandomMessages: テスト用のメッセージを乱数で作る道具(テストそのものではない)
// =====================================================================================================
//
// 【何のためにあるか】
//   人が手でテストデータを書くと、思いつく範囲のデータしか試せない。そこで、メッセージの各フィールドに
//   乱数で値を入れたデータを大量に(数千件)作り、ライブラリがどんなデータでも正しく動くかを確かめる。
//   (このようなテストの方法を「ランダムテスト」や「ファジング」と呼ぶ)
//
// 【乱数なのに毎回同じ結果になる理由】
//   乱数は「シード(種)」と呼ばれる最初の数で決まる。同じシードを使えば、毎回まったく同じ並びの乱数が出る。
//   テストが失敗したときに同じデータで再現できるよう、シードを固定している(new RandomMessages(シード, 深さ))。
//
// 【どうやって任意のメッセージを埋めるか】
//   Protobuf のメッセージは、自分の型情報(Descriptor)を持っている。「どんなフィールドがあって、それぞれ何の型か」が
//   分かるので、フィールドを 1 つずつ見て、型に合った乱数の値を入れていく(リフレクションと呼ばれる方法)。
//   そのため、AllTypes でも OutOfOrder でも、どの型のメッセージでも同じコードで埋められる。
//
// 【わざと混ぜている値】
//   普通の乱数だけでは、バグが起きやすい「境界の値」がなかなか出ないので、特別な値(最大値・最小値・0・-0.0・NaN、
//   改行や制御文字や絵文字を含む文字列など)を約半分の確率で選ぶようにしている。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import io.github.ramsesyok.protojson.testproto.Nested;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * テスト用に、任意の message 型をリフレクションで乱数(シード固定)で埋める。
 * 境界値・特殊値(MIN/MAX、0、-0.0、NaN、制御文字、サロゲートペア等)を高い確率で混ぜる。
 */
final class RandomMessages {

    // ---------------------------------------------------------------------------------------------
    // わざと混ぜる特別な値の一覧(型ごと)
    // ---------------------------------------------------------------------------------------------

    /**
     * 64bit 整数の特別な値。0・±1・2^53 と 2^53+1(JavaScript の数値で正確に表せる限界の前後)・long の最大値と最小値・
     * int の範囲をちょうど超える値(Integer.MAX_VALUE + 1L など。末尾の L を付けて long として計算している)。
     */
    private static final long[] SPECIAL_LONGS = {0, 1, -1, 42, 9007199254740992L, 9007199254740993L, -9007199254740993L,
            Long.MAX_VALUE, Long.MIN_VALUE, Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L};
    /** 32bit 整数の特別な値。 */
    private static final int[] SPECIAL_INTS = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 255};
    /**
     * float の特別な値。-0f(マイナスのゼロ)、Float.MIN_VALUE(0 より大きい最小の値)、MAX_VALUE(最大の値)、
     * MIN_NORMAL、NaN(非数)、正負の無限大など。
     */
    private static final float[] SPECIAL_FLOATS = {0f, -0f, 0.1f, 1f, -1.5f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.MIN_NORMAL, 1e-10f, 1.0e7f, 3.4028235e38f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
    /** double の特別な値(float と同様。1e21 は JSON での表記が 1.0E21 になる値)。 */
    private static final double[] SPECIAL_DOUBLES = {0d, -0d, 0.1, 1d, 1e21, 1e-7, 123456789.125, Double.MIN_VALUE,
            Double.MAX_VALUE, Double.MIN_NORMAL, 9007199254740993d, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY};
    /**
     * 文字列の特別な値。空文字、数字だけ(数値に誤変換されないか)、改行・タブ・ダブルクォート・バックスラッシュ、
     * HTML で特別な記号、日本語、絵文字(サロゲートペアと呼ばれる 2 文字分で表される文字)、制御文字(コード 0〜31 と 127)、
     * 行区切り文字(U+2028 / U+2029。見た目は空白だが、JavaScript では改行扱いになることがある文字)、
     * JSON のキーワードと同じ綴り("true" / "null" / "NaN")。
     */
    private static final String[] SPECIAL_STRINGS = {"", "12345", "-9223372036854775808", "0", "a\nb\r\tc",
            "say \"hi\" \\ /", "<tag> & a=b 'q'", "日本語テキスト", "emoji 😀 𠮷", "\u0000\u0001\u001f\u007f",
            "  ", "true", "null", "NaN"};

    /** 乱数の発生器。SplittableRandom は Java 標準の、高速で品質のよい乱数発生器。 */
    private final SplittableRandom random;
    /** メッセージの入れ子の深さの上限(AllTypes は自分自身を入れ子にできるので、上限が無いと無限に深くなる)。 */
    private final int maxDepth;

    /**
     * @param seed     乱数のシード(同じ値なら毎回同じデータが作られる)
     * @param maxDepth 入れ子の深さの上限
     */
    RandomMessages(long seed, int maxDepth) {
        this.random = new SplittableRandom(seed);
        this.maxDepth = maxDepth;
    }

    /** builder の型のメッセージを乱数で埋めて返す。 */
    Message next(Message.Builder builder) {
        return fill(builder, 0);
    }

    /**
     * builder の各フィールドに乱数の値を入れて、メッセージを完成させる。
     *
     * @param b     値を入れる Builder(組み立て途中のメッセージ)
     * @param depth 今の入れ子の深さ(一番外側が 0)
     */
    private Message fill(Message.Builder b, int depth) {
        // oneof ごとに、設定するメンバーを 1 つ均等に選ぶ(どれも設定しない場合を含む)
        Map<OneofDescriptor, FieldDescriptor> chosen = new HashMap<>();
        for (OneofDescriptor o : b.getDescriptorForType().getRealOneofs()) {
            int pick = random.nextInt(o.getFieldCount() + 1);
            chosen.put(o, pick == o.getFieldCount() ? null : o.getField(pick));
        }
        // getFields() で、このメッセージ型のフィールドの一覧(型情報)を取り出して 1 つずつ処理する
        for (FieldDescriptor f : b.getDescriptorForType().getFields()) {
            OneofDescriptor oneof = f.getRealContainingOneof();
            // oneof のメンバーなら、上で選ばれたものだけ設定する。それ以外のフィールドは 3 回に 1 回は何も入れない
            if (oneof != null) {
                if (chosen.get(oneof) != f) {
                    continue;
                }
            } else if (random.nextInt(3) == 0) {
                continue; // 1/3 は未設定のまま
            }
            // 入れ子が深くなりすぎたら、message 型のフィールドには何も入れない(無限に深くならないように)
            if (f.getType() == FieldDescriptor.Type.MESSAGE && depth >= maxDepth) {
                continue;
            }
            // repeated(リスト)なら 0〜3 個の値を追加し、そうでなければ値を 1 つ設定する
            if (f.isRepeated()) {
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    b.addRepeatedField(f, value(f, b, depth));
                }
            } else {
                b.setField(f, value(f, b, depth));
            }
        }
        return b.build();
    }

    /**
     * フィールドの型に合った乱数の値を 1 つ作る。
     * 戻り値の型が Object なのは、型によって Long / Integer / String / メッセージ など、返すものが違うため。
     * switch 式(case 型 -> 値)で、型ごとに作り方を分けている。
     */
    private Object value(FieldDescriptor f, Message.Builder parent, int depth) {
        return switch (f.getType()) {
            case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> nextLong();
            case INT32, SINT32, SFIXED32, UINT32, FIXED32 -> nextInt();
            case BOOL -> random.nextBoolean();
            case STRING -> nextString();
            case BYTES -> nextBytes();
            // 浮動小数点数: 半分は特別な値、半分は「ビット列をそのまま乱数にした値」(どんな値でも出る。NaN の変種も出る)
            case FLOAT -> random.nextBoolean() ? SPECIAL_FLOATS[random.nextInt(SPECIAL_FLOATS.length)]
                    : Float.intBitsToFloat(random.nextInt());
            case DOUBLE -> random.nextBoolean() ? SPECIAL_DOUBLES[random.nextInt(SPECIAL_DOUBLES.length)]
                    : Double.longBitsToDouble(random.nextLong());
            case ENUM -> nextEnum(f.getEnumType());
            case MESSAGE -> nextMessage(f, parent, depth);
            case GROUP -> throw new IllegalArgumentException("group");
        };
    }

    /** long の値: 半分の確率で特別な値、残りは完全な乱数。 */
    private long nextLong() {
        return random.nextBoolean() ? SPECIAL_LONGS[random.nextInt(SPECIAL_LONGS.length)] : random.nextLong();
    }

    /** int の値: 半分の確率で特別な値、残りは完全な乱数。 */
    private int nextInt() {
        return random.nextBoolean() ? SPECIAL_INTS[random.nextInt(SPECIAL_INTS.length)] : random.nextInt();
    }

    /**
     * 文字列: 半分の確率で特別な値。残りは、いろいろな種類の文字を 0〜19 文字ランダムに並べた文字列。
     * 文字は「コードポイント」(Unicode での文字の番号)で選び、appendCodePoint で追加している。
     * 0x20 のような書き方は 16 進数(0x20 = 32)。
     */
    private String nextString() {
        if (random.nextBoolean()) {
            return SPECIAL_STRINGS[random.nextInt(SPECIAL_STRINGS.length)];
        }
        StringBuilder sb = new StringBuilder();
        int len = random.nextInt(20);
        for (int i = 0; i < len; i++) {
            int cp = switch (random.nextInt(5)) {
                case 0 -> random.nextInt(0x20);              // 制御文字
                case 1 -> 0x20 + random.nextInt(0x5f);       // ASCII 表示文字
                case 2 -> 0x3040 + random.nextInt(0x60);     // ひらがな
                case 3 -> 0x1F600 + random.nextInt(0x50);    // 絵文字(サロゲートペア)
                default -> 0xA0 + random.nextInt(0xD000);    // その他の BMP(サロゲート領域より手前)
            };
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** バイト列: 0〜9 バイトの、中身が完全に乱数のバイト列。 */
    private ByteString nextBytes() {
        byte[] bytes = new byte[random.nextInt(10)];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) random.nextInt(256);
        }
        return ByteString.copyFrom(bytes);
    }

    /**
     * enum の値: 定義されている値からランダムに選ぶ。5 回に 1 回は、定義に無い数値(1000〜1009)を使う
     * (proto3 の enum は定義に無い数値も保持できるので、その場合の出力も確かめるため)。
     */
    private Object nextEnum(EnumDescriptor type) {
        if (type.getFullName().equals("google.protobuf.NullValue")) {
            return NullValue.NULL_VALUE.getValueDescriptor();
        }
        if (random.nextInt(5) == 0) {
            return type.findValueByNumberCreatingIfUnknown(1000 + random.nextInt(10)); // proto3 の未知の値
        }
        return type.getValues().get(random.nextInt(type.getValues().size()));
    }

    /**
     * message 型のフィールドの値を作る。
     * Well-Known Types(google.protobuf の共通の型)は、正しい値の範囲が決まっているものが多いので型ごとに作る。
     * それ以外(AllTypes や Nested など)は、fill を呼んで中身をさらに乱数で埋める(入れ子が 1 段深くなる)。
     */
    private Object nextMessage(FieldDescriptor f, Message.Builder parent, int depth) {
        String name = f.getMessageType().getFullName();
        return switch (name) {
            // 日時: 0001-01-01 〜 9999-12-31 の範囲の秒数(Timestamp が表せる範囲)
            case "google.protobuf.Timestamp" -> Timestamp.newBuilder()
                    .setSeconds(random.nextLong(-62135596800L, 253402300800L))
                    .setNanos(nanos()).build();
            case "google.protobuf.Duration" -> {
                // seconds と nanos は同じ符号でなければならない(seconds が 0 ならどちらでもよい)
                long seconds = random.nextLong(-315576000000L, 315576000001L);
                boolean negative = seconds < 0 || (seconds == 0 && random.nextBoolean());
                yield Duration.newBuilder().setSeconds(seconds).setNanos(negative ? -nanos() : nanos()).build();
            }
            case "google.protobuf.Int64Value" -> Int64Value.of(nextLong());
            case "google.protobuf.UInt64Value" -> UInt64Value.of(nextLong());
            case "google.protobuf.Int32Value" -> Int32Value.of(nextInt());
            case "google.protobuf.UInt32Value" -> UInt32Value.of(nextInt());
            case "google.protobuf.BoolValue" -> BoolValue.of(random.nextBoolean());
            case "google.protobuf.StringValue" -> StringValue.of(nextString());
            case "google.protobuf.BytesValue" -> BytesValue.of(nextBytes());
            case "google.protobuf.FloatValue" -> FloatValue.of(SPECIAL_FLOATS[random.nextInt(SPECIAL_FLOATS.length)]);
            case "google.protobuf.DoubleValue" -> DoubleValue.of(SPECIAL_DOUBLES[random.nextInt(SPECIAL_DOUBLES.length)]);
            // Any: Nested を詰める(テストでは TypeRegistry に Nested を登録しておく必要がある)
            case "google.protobuf.Any" -> Any.pack(Nested.newBuilder().setId(nextLong()).setName(nextString())
                    .addValues(nextLong()).build());
            case "google.protobuf.Struct" -> nextStruct(depth);
            case "google.protobuf.Value" -> nextValue(depth);
            case "google.protobuf.ListValue" -> ListValue.newBuilder().addValues(nextValue(depth)).addValues(nextValue(depth)).build();
            case "google.protobuf.FieldMask" -> FieldMask.newBuilder().addPaths("f_int64").addPaths("f_nested.name").build();
            case "google.protobuf.Empty" -> Empty.getDefaultInstance();
            // newBuilderForField(f) は、そのフィールドの型の Builder を作る
            default -> fill(parent.newBuilderForField(f), depth + 1);
        };
    }

    /**
     * Timestamp / Duration のナノ秒部分。JSON の小数部が 0 桁・3 桁・6 桁・9 桁になる値を、それぞれ同じくらいの確率で作る
     * (ミリ秒単位・マイクロ秒単位・ナノ秒単位で表示の桁数が変わるので、全部試すため)。
     */
    private int nanos() {
        return switch (random.nextInt(4)) {
            case 0 -> 0;
            case 1 -> random.nextInt(1000) * 1_000_000;
            case 2 -> random.nextInt(1_000_000) * 1_000;
            default -> random.nextInt(1_000_000_000);
        };
    }

    /** Struct(JSON のオブジェクトを表す型): 0〜2 個のキーと値を持つ。 */
    private Struct nextStruct(int depth) {
        Struct.Builder s = Struct.newBuilder();
        int n = random.nextInt(3);
        for (int i = 0; i < n; i++) {
            s.putFields("k" + i + nextString(), nextValue(depth + 1));
        }
        return s.build();
    }

    /**
     * Value(JSON の任意の値を表す型): null・数値・文字列・真偽値・オブジェクト・配列 のどれかをランダムに作る。
     * 入れ子が深くなったら、オブジェクトと配列(さらに入れ子になるもの)は選ばない。
     */
    private Value nextValue(int depth) {
        int kind = depth >= maxDepth ? random.nextInt(4) : random.nextInt(6);
        return switch (kind) {
            case 0 -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
            case 1 -> Value.newBuilder().setNumberValue(random.nextBoolean() ? random.nextDouble() * 1e6 : random.nextInt()).build();
            case 2 -> Value.newBuilder().setStringValue(nextString()).build();
            case 3 -> Value.newBuilder().setBoolValue(random.nextBoolean()).build();
            case 4 -> Value.newBuilder().setStructValue(nextStruct(depth + 1)).build();
            default -> Value.newBuilder().setListValue(ListValue.newBuilder().addValues(nextValue(depth + 1))).build();
        };
    }
}
