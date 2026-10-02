package demo.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int64Value;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * simlog.proto に無いフィールド型の扱いを、実行時に組み立てた Descriptor + DynamicMessage で確認する。
 *
 * <pre>
 * message Extra {
 *   repeated int64 ids = 1;
 *   sint64 s = 2;
 *   sfixed64 f = 3;
 *   uint64 u = 4;                    // 対象外 → 文字列のまま
 *   fixed64 fx = 5;                  // 対象外 → 文字列のまま
 *   int32 i32 = 6;                   // もともと数値
 *   google.protobuf.Int64Value w = 7; // 対象外(WKT) → 文字列のまま
 *   repeated Extra children = 8;
 *   map&lt;string, int64&gt; m = 9;        // 対象外 → 文字列のまま
 * }
 * </pre>
 */
class FieldTypeCoverageTest {

    private static Descriptor extra;
    private final Int64JsonConverter converter = new Int64JsonConverter();

    @BeforeAll
    static void buildDescriptor() throws Exception {
        DescriptorProto mapEntry = DescriptorProto.newBuilder()
                .setName("MEntry")
                .addField(field("key", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL))
                .addField(field("value", 2, Type.TYPE_INT64, Label.LABEL_OPTIONAL))
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .build();
        DescriptorProto msg = DescriptorProto.newBuilder()
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
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("x/extra.proto")
                .setPackage("x")
                .setSyntax("proto3")
                .addDependency("google/protobuf/wrappers.proto")
                .addMessageType(msg)
                .build();
        extra = FileDescriptor.buildFrom(file, new FileDescriptor[]{Int64Value.getDescriptor().getFile()})
                .findMessageTypeByName("Extra");
    }

    private static FieldDescriptorProto.Builder field(String name, int number, Type type, Label label) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type).setLabel(label);
    }

    @Test
    @DisplayName("repeated int64 / sint64 / sfixed64 は数値化、uint64 / fixed64 / Int64Value / map は文字列のまま")
    void fieldTypes() throws Exception {
        Descriptor entry = extra.findNestedTypeByName("MEntry");
        DynamicMessage child = DynamicMessage.newBuilder(extra)
                .addRepeatedField(extra.findFieldByName("ids"), 7L)
                .build();
        DynamicMessage m = DynamicMessage.newBuilder(extra)
                .addRepeatedField(extra.findFieldByName("ids"), 1L)
                .addRepeatedField(extra.findFieldByName("ids"), Long.MAX_VALUE)
                .addRepeatedField(extra.findFieldByName("ids"), -5L)
                .setField(extra.findFieldByName("s"), -3L)
                .setField(extra.findFieldByName("f"), Long.MIN_VALUE)
                .setField(extra.findFieldByName("u"), -1L) // uint64 の 18446744073709551615
                .setField(extra.findFieldByName("fx"), 5L)
                .setField(extra.findFieldByName("i32"), 6)
                .setField(extra.findFieldByName("w"), Int64Value.of(9007199254740993L))
                .addRepeatedField(extra.findFieldByName("children"), child)
                .addRepeatedField(extra.findFieldByName("m"), DynamicMessage.newBuilder(entry)
                        .setField(entry.findFieldByName("key"), "k")
                        .setField(entry.findFieldByName("value"), 8L)
                        .build())
                .build();

        assertEquals("{\"ids\":[\"1\",\"9223372036854775807\",\"-5\"],\"s\":\"-3\",\"f\":\"-9223372036854775808\","
                + "\"u\":\"18446744073709551615\",\"fx\":\"5\",\"i32\":6,\"w\":\"9007199254740993\","
                + "\"children\":[{\"ids\":[\"7\"]}],\"m\":{\"k\":\"8\"}}", converter.toRawJson(m));

        String json = converter.toJson(m);
        assertEquals("{\"ids\":[1,9223372036854775807,-5],\"s\":-3,\"f\":-9223372036854775808,"
                + "\"u\":\"18446744073709551615\",\"fx\":\"5\",\"i32\":6,\"w\":\"9007199254740993\","
                + "\"children\":[{\"ids\":[7]}],\"m\":{\"k\":\"8\"}}", json);

        JsonNode n = Int64JsonConverter.mapper().readTree(json);
        assertTrue(n.get("ids").get(1).isLong());
        assertTrue(n.get("u").isTextual());
        assertTrue(n.get("w").isTextual());
        assertTrue(n.at("/m/k").isTextual());
    }
}
