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

    private static final long[] SPECIAL_LONGS = {0, 1, -1, 42, 9007199254740992L, 9007199254740993L, -9007199254740993L,
            Long.MAX_VALUE, Long.MIN_VALUE, Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L};
    private static final int[] SPECIAL_INTS = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 255};
    private static final float[] SPECIAL_FLOATS = {0f, -0f, 0.1f, 1f, -1.5f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.MIN_NORMAL, 1e-10f, 1.0e7f, 3.4028235e38f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
    private static final double[] SPECIAL_DOUBLES = {0d, -0d, 0.1, 1d, 1e21, 1e-7, 123456789.125, Double.MIN_VALUE,
            Double.MAX_VALUE, Double.MIN_NORMAL, 9007199254740993d, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY};
    private static final String[] SPECIAL_STRINGS = {"", "12345", "-9223372036854775808", "0", "a\nb\r\tc",
            "say \"hi\" \\ /", "<tag> & a=b 'q'", "日本語テキスト", "emoji 😀 𠮷", "\u0000\u0001\u001f\u007f",
            "  ", "true", "null", "NaN"};

    private final SplittableRandom random;
    private final int maxDepth;

    RandomMessages(long seed, int maxDepth) {
        this.random = new SplittableRandom(seed);
        this.maxDepth = maxDepth;
    }

    /** builder の型のメッセージを乱数で埋めて返す。 */
    Message next(Message.Builder builder) {
        return fill(builder, 0);
    }

    private Message fill(Message.Builder b, int depth) {
        // oneof ごとに、設定するメンバーを 1 つ均等に選ぶ(どれも設定しない場合を含む)
        Map<OneofDescriptor, FieldDescriptor> chosen = new HashMap<>();
        for (OneofDescriptor o : b.getDescriptorForType().getRealOneofs()) {
            int pick = random.nextInt(o.getFieldCount() + 1);
            chosen.put(o, pick == o.getFieldCount() ? null : o.getField(pick));
        }
        for (FieldDescriptor f : b.getDescriptorForType().getFields()) {
            OneofDescriptor oneof = f.getRealContainingOneof();
            if (oneof != null) {
                if (chosen.get(oneof) != f) {
                    continue;
                }
            } else if (random.nextInt(3) == 0) {
                continue; // 1/3 は未設定のまま
            }
            if (f.getType() == FieldDescriptor.Type.MESSAGE && depth >= maxDepth) {
                continue;
            }
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

    private Object value(FieldDescriptor f, Message.Builder parent, int depth) {
        return switch (f.getType()) {
            case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> nextLong();
            case INT32, SINT32, SFIXED32, UINT32, FIXED32 -> nextInt();
            case BOOL -> random.nextBoolean();
            case STRING -> nextString();
            case BYTES -> nextBytes();
            case FLOAT -> random.nextBoolean() ? SPECIAL_FLOATS[random.nextInt(SPECIAL_FLOATS.length)]
                    : Float.intBitsToFloat(random.nextInt());
            case DOUBLE -> random.nextBoolean() ? SPECIAL_DOUBLES[random.nextInt(SPECIAL_DOUBLES.length)]
                    : Double.longBitsToDouble(random.nextLong());
            case ENUM -> nextEnum(f.getEnumType());
            case MESSAGE -> nextMessage(f, parent, depth);
            case GROUP -> throw new IllegalArgumentException("group");
        };
    }

    private long nextLong() {
        return random.nextBoolean() ? SPECIAL_LONGS[random.nextInt(SPECIAL_LONGS.length)] : random.nextLong();
    }

    private int nextInt() {
        return random.nextBoolean() ? SPECIAL_INTS[random.nextInt(SPECIAL_INTS.length)] : random.nextInt();
    }

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

    private ByteString nextBytes() {
        byte[] bytes = new byte[random.nextInt(10)];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) random.nextInt(256);
        }
        return ByteString.copyFrom(bytes);
    }

    private Object nextEnum(EnumDescriptor type) {
        if (type.getFullName().equals("google.protobuf.NullValue")) {
            return NullValue.NULL_VALUE.getValueDescriptor();
        }
        if (random.nextInt(5) == 0) {
            return type.findValueByNumberCreatingIfUnknown(1000 + random.nextInt(10)); // proto3 の未知の値
        }
        return type.getValues().get(random.nextInt(type.getValues().size()));
    }

    private Object nextMessage(FieldDescriptor f, Message.Builder parent, int depth) {
        String name = f.getMessageType().getFullName();
        return switch (name) {
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
            case "google.protobuf.Any" -> Any.pack(Nested.newBuilder().setId(nextLong()).setName(nextString())
                    .addValues(nextLong()).build());
            case "google.protobuf.Struct" -> nextStruct(depth);
            case "google.protobuf.Value" -> nextValue(depth);
            case "google.protobuf.ListValue" -> ListValue.newBuilder().addValues(nextValue(depth)).addValues(nextValue(depth)).build();
            case "google.protobuf.FieldMask" -> FieldMask.newBuilder().addPaths("f_int64").addPaths("f_nested.name").build();
            case "google.protobuf.Empty" -> Empty.getDefaultInstance();
            default -> fill(parent.newBuilderForField(f), depth + 1);
        };
    }

    private int nanos() {
        return switch (random.nextInt(4)) {
            case 0 -> 0;
            case 1 -> random.nextInt(1000) * 1_000_000;
            case 2 -> random.nextInt(1_000_000) * 1_000;
            default -> random.nextInt(1_000_000_000);
        };
    }

    private Struct nextStruct(int depth) {
        Struct.Builder s = Struct.newBuilder();
        int n = random.nextInt(3);
        for (int i = 0; i < n; i++) {
            s.putFields("k" + i + nextString(), nextValue(depth + 1));
        }
        return s.build();
    }

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
