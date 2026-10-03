// =====================================================================================================
// MessageLayout: メッセージの型ごとの「JSON への書き出し手順」(ライブラリ内部のクラス)
// =====================================================================================================
//
// 【役割】
//   メッセージを JSON にするには、「どのフィールドを、どの順番で、どんなキー名で書くか」を知る必要がある。
//   この情報はメッセージの型(Descriptor)から分かるが、毎回調べ直すと遅いので、型ごとに 1 回だけ調べて
//   「書き出し手順」(MessageLayout)としてまとめ、キャッシュ(一時保存)しておく。
//
// 【Descriptor とは】
//   Protobuf のメッセージが持っている「自分の型の説明書」。フィールドの名前・番号・型・JSON でのキー名などが入っている。
//   生成コードのメッセージでも DynamicMessage でも getDescriptorForType() で取り出せる。
//   本ライブラリは Descriptor だけを見て動くので、特定の proto に依存しない。
//
// 【未対応の構造のチェック】
//   手順を作るときに、その型と、そこからたどれるすべての型を調べ、未対応の構造(map / group / extension)があれば
//   例外を投げる。値が入っているかどうかに関係なく「型の時点で」失敗させるので、
//   「特定のデータが来たときだけ本番で失敗する」ことが無い(テストの時点で気付ける)。
//
// 【キャッシュとメモリ】
//   キャッシュは ProtoJsonPrinter のインスタンスごとに持つ(static にしない)。理由は 2 つ。
//     1. static だと、アプリを再デプロイしても古いクラスの型情報を握り続け、メモリが解放されない恐れがある
//        (アプリケーションサーバでの「クラスローダリーク」)。インスタンスごとなら、printer と一緒に解放される。
//     2. 実行時に型(Descriptor)を作り続ける特殊な使い方でも、キャッシュが際限なく増えないよう上限を設けている。
//        上限を超えた型は、キャッシュせずに毎回手順を作る(結果は同じで、少し遅くなるだけ)。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.SerializableString;   // Jackson がそのまま書ける「エンコード済みの文字列」
import com.fasterxml.jackson.core.io.SerializedString;  // SerializableString の実装クラス
import com.google.protobuf.Descriptors.Descriptor;       // メッセージの型の説明書
import com.google.protobuf.Descriptors.FieldDescriptor;  // フィールドの説明書(名前・番号・型など)
import com.google.protobuf.Descriptors.OneofDescriptor;  // oneof の説明書

import java.util.ArrayDeque;                       // 両端から出し入れできるキュー(ここではスタックとして使う)
import java.util.Comparator;                       // 並べ替えの順番の決め方
import java.util.Deque;                            // ArrayDeque の型
import java.util.HashSet;                          // 重複しない集合
import java.util.List;                             // リスト
import java.util.Set;                              // 集合の型
import java.util.concurrent.ConcurrentHashMap;     // 複数スレッドから同時に使える「キー → 値」の表
import java.util.concurrent.ConcurrentMap;         // ConcurrentHashMap の型
import java.util.function.Predicate;               // 「条件を判定する処理」を表す型

/**
 * message 型ごとの「JSON への書き出し手順」。{@link Cache} を通して、型ごとに 1 回だけ作ってキャッシュする。
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
 * <p>インスタンスは不変(作った後に中身が変わらない)なので、複数のスレッドから同時に使ってよい。
 */
// final class: 継承できないクラス。package-private(public を付けない)なので、このパッケージの外からは見えない
final class MessageLayout {

    /** 1 フィールド分の書き出し情報。 */
    static final class Field {
        /** フィールドの説明書(型・番号・値の取り出しに使う)。 */
        final FieldDescriptor descriptor;
        /**
         * JSON のキー名(json_name)。Jackson がそのまま書ける形でエンコード済み。
         * キー名は書き出すたびに同じなので、エンコードを 1 回で済ませて高速にしている。
         */
        final SerializableString jsonName;
        /** 属する oneof の番号({@link OneofDescriptor#getIndex()})。oneof に属さない場合は -1。 */
        final int oneofIndex;

        Field(FieldDescriptor descriptor) {
            this.descriptor = descriptor;
            // getJsonName() は JSON でのキー名(f_int64 → fInt64。json_name の指定があればその名前)
            this.jsonName = new SerializedString(descriptor.getJsonName());
            // proto3 の optional は内部的に「1 メンバーだけの合成 oneof」になるが、
            // 本物の oneof ではないので getRealContainingOneof() は null を返す(通常の hasField で判定する)
            OneofDescriptor oneof = descriptor.getRealContainingOneof();
            // 三項演算子: oneof が null なら -1、そうでなければ oneof の番号
            this.oneofIndex = oneof == null ? -1 : oneof.getIndex();
        }
    }

    /** フィールド番号順のフィールド。JSON のキーはこの順番で書く。 */
    final Field[] fields;
    /** oneof(合成 oneof を含む)の総数。oneof の判定結果を入れる配列の大きさに使う。 */
    final int oneofCount;

    private MessageLayout(Descriptor descriptor) {
        // ストリーム API で「フィールド一覧 → 番号順に並べ替え → Field に変換 → 配列にする」を 1 つの式で書いている。
        //   stream()   : リストの要素を順番に流す
        //   sorted(...): 並べ替える(Comparator.comparingInt(...) は「この数値の小さい順」という意味)
        //   map(...)   : 各要素を別のものに変換する(Field::new は new Field(要素) と同じ意味の「メソッド参照」)
        //   toArray(...): 配列にする
        this.fields = descriptor.getFields().stream()
                .sorted(Comparator.comparingInt(FieldDescriptor::getNumber))
                .map(Field::new)
                .toArray(Field[]::new);
        this.oneofCount = descriptor.getOneofs().size();
    }

    /**
     * google.protobuf パッケージの型(Timestamp / Any / ラッパー型 等)かどうか。
     * これらは JsonFormat が特別な表現(Timestamp は日時の文字列など)で出力するので、別扱いにする。
     */
    static boolean isWellKnownType(Descriptor descriptor) {
        return "google.protobuf".equals(descriptor.getFile().getPackage());
    }

    /**
     * descriptor から到達できる message 型をたどり、未対応の構造が無いか検査する。
     *
     * <p>再帰呼び出しではなく「これから調べる型の一覧」(pending)を使ってたどる。入れ子が深い型や、
     * 自分自身を含む型(AllTypes の中に AllTypes がある等)でも、スタックを使い切らず、無限ループにもならない。
     *
     * @param root         調べ始める型
     * @param alreadyValid 検査済みとみなしてよい型か(キャッシュ済みの型は検査済みなので飛ばす)
     * @throws UnsupportedOperationException 未対応の構造が見つかった場合
     */
    private static void validate(Descriptor root, Predicate<Descriptor> alreadyValid) {
        Set<Descriptor> visited = new HashSet<>();                  // 一度調べた型(同じ型を二度調べない)
        Deque<Descriptor> pending = new ArrayDeque<>(List.of(root)); // これから調べる型
        while (!pending.isEmpty()) {
            Descriptor d = pending.pop(); // 1 つ取り出す
            // visited.add は「新しく追加できたら true、すでにあれば false」を返す
            if (!visited.add(d) || isWellKnownType(d) || alreadyValid.test(d)) {
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
                    pending.push(f.getMessageType()); // message 型のフィールドは、その型も後で調べる
                }
            }
        }
    }

    /** 未対応の構造を知らせる例外を作る(メッセージはログで読みやすいよう英語)。 */
    private static UnsupportedOperationException unsupported(Descriptor d, String what) {
        return new UnsupportedOperationException(
                "ProtoJsonPrinter does not support " + what + " in message " + d.getFullName());
    }

    // =================================================================================================
    // キャッシュ
    // =================================================================================================

    /**
     * 書き出し手順のキャッシュ。{@link ProtoJsonPrinter} のインスタンスごとに 1 つ持つ。
     *
     * <p>複数のスレッドから同時に使ってよい(ConcurrentHashMap を使っているため)。
     * 保持する型の数に上限があり、上限に達した後の新しい型はキャッシュせず、毎回手順を作る。
     */
    static final class Cache {

        /**
         * 既定の上限。通常のアプリケーションの message 型の数(生成コードの型の数)より十分大きい値にしている。
         * 1 つの手順の大きさはフィールド数に比例する小さなもので、上限まで溜まっても数十 MB 程度に収まる。
         */
        static final int DEFAULT_MAX_ENTRIES = 10_000;

        /** 型 → 書き出し手順 の表。 */
        private final ConcurrentMap<Descriptor, MessageLayout> layouts = new ConcurrentHashMap<>();
        /** 保持する型の数の上限。 */
        private final int maxEntries;

        Cache(int maxEntries) {
            if (maxEntries < 0) {
                throw new IllegalArgumentException("maxEntries must be >= 0: " + maxEntries);
            }
            this.maxEntries = maxEntries;
        }

        /**
         * 指定した message 型の書き出し手順を返す(キャッシュ済みならそれを返す)。
         *
         * @throws UnsupportedOperationException その型、またはそこから到達できる型に未対応の構造がある場合
         */
        MessageLayout get(Descriptor descriptor) {
            MessageLayout layout = layouts.get(descriptor);
            if (layout != null) {
                return layout; // キャッシュにあった(ほとんどの呼び出しはここで終わるので速い)
            }
            // 初めての型: 未対応の構造が無いか調べてから、手順を作る
            // layouts::containsKey は「layouts にその型があるか」を判定する処理(メソッド参照)
            validate(descriptor, layouts::containsKey);
            layout = new MessageLayout(descriptor);
            if (layouts.size() < maxEntries) {
                // putIfAbsent: まだ無ければ入れる。別のスレッドが先に入れていたら、そちらを返す(どちらを使っても結果は同じ)
                MessageLayout existing = layouts.putIfAbsent(descriptor, layout);
                if (existing != null) {
                    return existing;
                }
            }
            return layout; // 上限に達していたらキャッシュせずに返す
        }

        /** キャッシュしている型の数(テスト用)。 */
        int size() {
            return layouts.size();
        }
    }
}
