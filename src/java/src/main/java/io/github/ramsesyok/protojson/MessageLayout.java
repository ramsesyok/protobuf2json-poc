package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * message 型ごとの「JSON への書き出し手順」。Descriptor ごとに 1 回だけ作ってキャッシュする。
 *
 * <p>保持する情報:
 * <ul>
 *   <li>フィールドをフィールド番号順に並べた配列(JsonFormat と同じ出力順。宣言順ではない)</li>
 *   <li>各フィールドの JSON キー名(json_name)を Jackson 用にエンコード済みの形で保持</li>
 *   <li>各フィールドが属する oneof の番号(oneof に属さなければ -1)</li>
 * </ul>
 *
 * <p>作成時に、その型と、そこから到達できる message 型(Well-Known Types を除く)をすべて検査し、
 * 未対応の構造(map / group / extension)があれば {@link UnsupportedOperationException} を投げる。
 * 値が入っているかどうかに関係なく型の時点で弾くので、「特定のデータの時だけ本番で失敗する」ことが無い。
 *
 * <p>キャッシュは Descriptor をキーにしたマップで、要素は削除しない。通常 Descriptor は生成コードの
 * static な値なので問題ないが、実行時に Descriptor を大量に組み立てる用途(DynamicMessage を動的スキーマで
 * 使い続ける等)ではキャッシュが増え続ける点に注意。
 */
final class MessageLayout {

    private static final ConcurrentMap<Descriptor, MessageLayout> CACHE = new ConcurrentHashMap<>();

    /** 1 フィールド分の書き出し情報。 */
    static final class Field {
        final FieldDescriptor descriptor;
        /** JSON のキー名(json_name)。Jackson がそのまま書ける形でエンコード済み。 */
        final SerializableString jsonName;
        /** 属する oneof の番号({@link OneofDescriptor#getIndex()})。oneof に属さない場合は -1。 */
        final int oneofIndex;

        Field(FieldDescriptor descriptor) {
            this.descriptor = descriptor;
            this.jsonName = new SerializedString(descriptor.getJsonName());
            // proto3 の optional は内部的に「1 メンバーだけの合成 oneof」になるが、
            // 本物の oneof ではないので getRealContainingOneof() は null を返す(通常の hasField で判定する)
            OneofDescriptor oneof = descriptor.getRealContainingOneof();
            this.oneofIndex = oneof == null ? -1 : oneof.getIndex();
        }
    }

    /** フィールド番号順のフィールド。 */
    final Field[] fields;
    /** oneof(合成 oneof を含む)の総数。oneof の判定結果を入れる配列の大きさに使う。 */
    final int oneofCount;

    private MessageLayout(Descriptor descriptor) {
        this.fields = descriptor.getFields().stream()
                .sorted(Comparator.comparingInt(FieldDescriptor::getNumber))
                .map(Field::new)
                .toArray(Field[]::new);
        this.oneofCount = descriptor.getOneofs().size();
    }

    /**
     * 指定した message 型の書き出し手順を返す(キャッシュ済みならそれを返す)。
     *
     * @throws UnsupportedOperationException その型、またはそこから到達できる型に未対応の構造がある場合
     */
    static MessageLayout of(Descriptor descriptor) {
        MessageLayout layout = CACHE.get(descriptor);
        if (layout != null) {
            return layout;
        }
        validate(descriptor);
        return CACHE.computeIfAbsent(descriptor, MessageLayout::new);
    }

    /** google.protobuf パッケージの型(Timestamp / Any / ラッパー型 等)。JsonFormat が特別な表現で出力する。 */
    static boolean isWellKnownType(Descriptor descriptor) {
        return "google.protobuf".equals(descriptor.getFile().getPackage());
    }

    /** descriptor から到達できる message 型をたどり、未対応の構造が無いか検査する。 */
    private static void validate(Descriptor root) {
        Set<Descriptor> visited = new HashSet<>();
        Deque<Descriptor> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Descriptor d = pending.pop();
            if (!visited.add(d) || isWellKnownType(d) || CACHE.containsKey(d)) {
                continue; // 検査済み、または WKT(JsonFormat に任せるので中は見ない)
            }
            if (d.isExtendable()) {
                throw unsupported(d, "extendable messages (proto2 extensions)");
            }
            for (FieldDescriptor f : d.getFields()) {
                if (f.isMapField()) {
                    throw unsupported(d, "map field '" + f.getName() + "'");
                }
                if (f.getType() == FieldDescriptor.Type.GROUP) {
                    throw unsupported(d, "group (DELIMITED encoded) field '" + f.getName() + "'");
                }
                if (f.getType() == FieldDescriptor.Type.MESSAGE) {
                    pending.push(f.getMessageType());
                }
            }
        }
    }

    private static UnsupportedOperationException unsupported(Descriptor d, String what) {
        return new UnsupportedOperationException(
                "ProtoJsonPrinter does not support " + what + " in message " + d.getFullName());
    }
}
