package demo.json;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;

import java.util.List;

/**
 * simlog.proto に無いフィールド型を確認するため、実行時に組み立てる Descriptor と DynamicMessage。
 *
 * <pre>
 * package x;
 * message Extra {
 *   repeated int64 ids = 1;
 *   sint64 s = 2;
 *   sfixed64 f = 3;
 *   uint64 u = 4;                     // 対象外 → 文字列のまま
 *   fixed64 fx = 5;                   // 対象外 → 文字列のまま
 *   int32 i32 = 6;                    // もともと数値
 *   google.protobuf.Int64Value w = 7; // 対象外(WKT) → 文字列のまま
 *   repeated Extra children = 8;
 *   map&lt;string, int64&gt; m = 9;         // 対象外 → 文字列のまま
 * }
 * enum Color { RED = 0; GREEN = 1; BLUE = 2; }
 * message Scalars {
 *   bool b = 1; bytes by = 2; Color e = 3; uint32 u32 = 4; fixed32 f32 = 5;
 *   float fl = 6; double db = 7; int32 neg = 8; repeated Color es = 9;
 *   google.protobuf.Timestamp ts = 10; google.protobuf.StringValue sv = 11; int64 id = 12;
 * }
 * </pre>
 */
final class DynamicFixtures {

    static final Descriptor EXTRA;
    static final Descriptor SCALARS;
    static final EnumDescriptor COLOR;

    static {
        try {
            FileDescriptor file = FileDescriptor.buildFrom(fileProto(), new FileDescriptor[]{
                    Int64Value.getDescriptor().getFile(), Timestamp.getDescriptor().getFile()});
            EXTRA = file.findMessageTypeByName("Extra");
            SCALARS = file.findMessageTypeByName("Scalars");
            COLOR = file.findEnumTypeByName("Color");
        } catch (DescriptorValidationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DynamicFixtures() {
    }

    private static FileDescriptorProto fileProto() {
        DescriptorProto mapEntry = DescriptorProto.newBuilder()
                .setName("MEntry")
                .addField(field("key", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL))
                .addField(field("value", 2, Type.TYPE_INT64, Label.LABEL_OPTIONAL))
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .build();
        DescriptorProto extra = DescriptorProto.newBuilder()
                .setName("Extra")
                .addField(field("ids", 1, Type.TYPE_INT64, Label.LABEL_REPEATED))
                .addField(field("s", 2, Type.TYPE_SINT64, Label.LABEL_OPTIONAL))
                .addField(field("f", 3, Type.TYPE_SFIXED64, Label.LABEL_OPTIONAL))
                .addField(field("u", 4, Type.TYPE_UINT64, Label.LABEL_OPTIONAL))
                .addField(field("fx", 5, Type.TYPE_FIXED64, Label.LABEL_OPTIONAL))
                .addField(field("i32", 6, Type.TYPE_INT32, Label.LABEL_OPTIONAL))
                .addField(field("w", 7, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL).setTypeName(".google.protobuf.Int64Value"))
                .addField(field("children", 8, Type.TYPE_MESSAGE, Label.LABEL_REPEATED).setTypeName(".x.Extra"))
                .addField(field("m", 9, Type.TYPE_MESSAGE, Label.LABEL_REPEATED).setTypeName(".x.Extra.MEntry"))
                .addNestedType(mapEntry)
                .build();
        EnumDescriptorProto color = EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(1))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(2))
                .build();
        DescriptorProto scalars = DescriptorProto.newBuilder()
                .setName("Scalars")
                .addField(field("b", 1, Type.TYPE_BOOL, Label.LABEL_OPTIONAL))
                .addField(field("by", 2, Type.TYPE_BYTES, Label.LABEL_OPTIONAL))
                .addField(field("e", 3, Type.TYPE_ENUM, Label.LABEL_OPTIONAL).setTypeName(".x.Color"))
                .addField(field("u32", 4, Type.TYPE_UINT32, Label.LABEL_OPTIONAL))
                .addField(field("f32", 5, Type.TYPE_FIXED32, Label.LABEL_OPTIONAL))
                .addField(field("fl", 6, Type.TYPE_FLOAT, Label.LABEL_OPTIONAL))
                .addField(field("db", 7, Type.TYPE_DOUBLE, Label.LABEL_OPTIONAL))
                .addField(field("neg", 8, Type.TYPE_INT32, Label.LABEL_OPTIONAL))
                .addField(field("es", 9, Type.TYPE_ENUM, Label.LABEL_REPEATED).setTypeName(".x.Color"))
                .addField(field("ts", 10, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL).setTypeName(".google.protobuf.Timestamp"))
                .addField(field("sv", 11, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL).setTypeName(".google.protobuf.StringValue"))
                .addField(field("id", 12, Type.TYPE_INT64, Label.LABEL_OPTIONAL))
                .build();
        return FileDescriptorProto.newBuilder()
                .setName("x/extra.proto")
                .setPackage("x")
                .setSyntax("proto3")
                .addDependency("google/protobuf/wrappers.proto")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(extra)
                .addMessageType(scalars)
                .addEnumType(color)
                .build();
    }

    private static FieldDescriptorProto.Builder field(String name, int number, Type type, Label label) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type).setLabel(label);
    }

    static DynamicMessage extra(boolean withMap) {
        Descriptor entry = EXTRA.findNestedTypeByName("MEntry");
        DynamicMessage child = DynamicMessage.newBuilder(EXTRA)
                .addRepeatedField(EXTRA.findFieldByName("ids"), 7L)
                .build();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(EXTRA)
                .addRepeatedField(EXTRA.findFieldByName("ids"), 1L)
                .addRepeatedField(EXTRA.findFieldByName("ids"), Long.MAX_VALUE)
                .addRepeatedField(EXTRA.findFieldByName("ids"), -5L)
                .setField(EXTRA.findFieldByName("s"), -3L)
                .setField(EXTRA.findFieldByName("f"), Long.MIN_VALUE)
                .setField(EXTRA.findFieldByName("u"), -1L) // uint64 の 18446744073709551615
                .setField(EXTRA.findFieldByName("fx"), 5L)
                .setField(EXTRA.findFieldByName("i32"), 6)
                .setField(EXTRA.findFieldByName("w"), Int64Value.of(9007199254740993L))
                .addRepeatedField(EXTRA.findFieldByName("children"), child);
        if (withMap) {
            b.addRepeatedField(EXTRA.findFieldByName("m"), DynamicMessage.newBuilder(entry)
                    .setField(entry.findFieldByName("key"), "k")
                    .setField(entry.findFieldByName("value"), 8L)
                    .build());
        }
        return b.build();
    }

    /** bool / bytes / enum / unsigned / float / double / WKT の値の組み合わせ。 */
    static List<DynamicMessage> scalars() {
        return List.of(
                DynamicMessage.getDefaultInstance(SCALARS),
                scalars(true, ByteString.copyFromUtf8("héllo\n\u0000"), 1, -1, -2, 0.1f, 1e21, -7, 9007199254740993L),
                scalars(false, ByteString.EMPTY, 2, 4000000000L, 1, Float.NaN, Double.POSITIVE_INFINITY, 0, -1L),
                scalars(true, ByteString.copyFrom(new byte[]{(byte) 0xff, 0x00, 0x7f}), 0, 1, 0,
                        Float.NEGATIVE_INFINITY, Double.NaN, Integer.MIN_VALUE, Long.MIN_VALUE),
                scalars(false, ByteString.EMPTY, 0, 0, 0, 3.4028235e38f, -0.0, 0, 0L),
                scalars(false, ByteString.EMPTY, 0, 0, 0, 1.0f, 123456789.125, 0, 0L));
    }

    private static DynamicMessage scalars(boolean b, ByteString by, int color, long u32, int f32,
                                          float fl, double db, int neg, long id) {
        return DynamicMessage.newBuilder(SCALARS)
                .setField(SCALARS.findFieldByName("b"), b)
                .setField(SCALARS.findFieldByName("by"), by)
                .setField(SCALARS.findFieldByName("e"), COLOR.findValueByNumber(color))
                .setField(SCALARS.findFieldByName("u32"), (int) u32)
                .setField(SCALARS.findFieldByName("f32"), f32)
                .setField(SCALARS.findFieldByName("fl"), fl)
                .setField(SCALARS.findFieldByName("db"), db)
                .setField(SCALARS.findFieldByName("neg"), neg)
                .addRepeatedField(SCALARS.findFieldByName("es"), COLOR.findValueByNumber(2))
                .addRepeatedField(SCALARS.findFieldByName("es"), COLOR.findValueByNumber(0))
                .setField(SCALARS.findFieldByName("ts"), Timestamp.newBuilder().setSeconds(1700000000L).setNanos(5000).build())
                .setField(SCALARS.findFieldByName("sv"), StringValue.of("<tag> & \"q\" 日本"))
                .setField(SCALARS.findFieldByName("id"), id)
                .build();
    }
}
