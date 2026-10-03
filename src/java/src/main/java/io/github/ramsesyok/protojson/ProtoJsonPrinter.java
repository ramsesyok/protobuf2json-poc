// =====================================================================================================
// ProtoJsonPrinter: Protobuf のメッセージを JSON に変換する(本ライブラリの入口。利用者が使うクラス)
// =====================================================================================================
//
// 【何をするクラスか】
//   gRPC などで受け取った Protobuf のメッセージを JSON に変換する。
//   Protobuf 公式の JsonFormat と同じ規則で出力するが、int64 / sint64 / sfixed64 だけは
//   "123" のような文字列ではなく、123 のような JSON の数値で出力する(OpenAPI の type: integer に合わせるため)。
//
// 【使い方】
//   ProtoJsonPrinter printer = ProtoJsonPrinter.create();   // 1 つ作って、アプリ全体で使い回す
//   String json = printer.print(message);                    // JSON 文字列にする(常に 1 行)
//   printer.writeTo(message, outputStream);                  // UTF-8 で直接書き出す(文字列を作らないので速い)
//
// 【しくみ】
//   メッセージの型情報(Descriptor)を見ながらフィールドを 1 つずつたどり、Jackson の JsonGenerator(JSON を少しずつ
//   書き出す道具)で直接 JSON を書く。JsonFormat で一度 JSON を作ってから書き換える方式より約 3 倍速い。
//   入れ子のメッセージは、writeMessage が自分自身を呼び出して書く(再帰呼び出し)。
//
// 【このクラスと内部クラスの関係】
//   ProtoJsonPrinter(このクラス): 入口。メッセージをたどってフィールドごとに書く
//     ├─ MessageLayout : 型ごとの書き出し手順(フィールドの順番・キー名)。初回に作ってキャッシュする
//     ├─ ScalarValues  : 数値・文字列などの値の書き方 ★int64 を数値にしている所
//     └─ WellKnownTypes: Timestamp などの共通の型の書き方
//
// 【スレッドセーフ】
//   インスタンスは作った後に設定が変わらない(不変)。複数のスレッドから同時に使ってよい。
//   型ごとの書き出し手順をインスタンスの中にキャッシュするので、1 つ作って使い回すほど速くなる
//   (リクエストごとに作り直すと、毎回キャッシュが空の状態から始まり遅くなる)。
// =====================================================================================================

package io.github.ramsesyok.protojson;

import com.fasterxml.jackson.core.JsonEncoding;                    // 文字コード(UTF-8 など)
import com.fasterxml.jackson.core.JsonFactory;                     // JsonGenerator を作る製造元
import com.fasterxml.jackson.core.JsonGenerator;                   // JSON を少しずつ書き出す道具
import com.fasterxml.jackson.core.exc.StreamConstraintsException;  // Jackson の上限(入れ子の深さ等)を超えたときの例外
import com.fasterxml.jackson.core.json.JsonWriteFeature;           // JSON の書き方に関する設定項目
import com.google.protobuf.Descriptors.Descriptor;                 // メッセージの型の説明書
import com.google.protobuf.Descriptors.FieldDescriptor;            // フィールドの説明書
import com.google.protobuf.MessageOrBuilder;                       // すべての Protobuf メッセージ(と Builder)に共通の型
import com.google.protobuf.util.JsonFormat;                        // Protobuf 公式の JSON 変換(TypeRegistry のため)

import java.io.IOException;           // 入出力の失敗を表す例外
import java.io.OutputStream;          // バイト列の書き出し先(ファイル・HTTP 応答など)
import java.io.StringWriter;          // 書いた文字をメモリに溜める Writer
import java.io.UncheckedIOException;  // IOException を、throws 宣言が要らない例外に包むためのクラス
import java.io.Writer;                // 文字の書き出し先
import java.util.Objects;             // null チェックなどの便利メソッド

/**
 * Protobuf メッセージを JSON に書き出す。int64 / sint64 / sfixed64 は JSON の数値で出力する。
 *
 * <p>JsonFormat を使わず、Protobuf のリフレクション API(フィールドの有無と値の取得)で message をたどり、
 * Jackson の {@link JsonGenerator} へ直接書き出す(1 パス)。JsonFormat の出力を加工する方式に比べて
 * 約 3 倍速く、メモリ割り当ても少ない。出力規則・対象外・未対応の構造はパッケージの説明
 * ({@code package-info.java})を参照。
 *
 * <p>インスタンスは不変でスレッドセーフ。アプリケーション全体で 1 つ作って使い回すことを想定している
 * (型ごとの書き出し手順をインスタンス内にキャッシュするため。キャッシュは型の数に上限があり、printer と一緒に解放される)。
 *
 * <pre>{@code
 * ProtoJsonPrinter printer = ProtoJsonPrinter.create();
 * String json = printer.print(simLog);
 * printer.writeTo(simLog, httpResponseOutputStream);
 * }</pre>
 *
 * <p>例外:
 * <ul>
 *   <li>{@link NullPointerException}: 引数が null</li>
 *   <li>{@link UnsupportedOperationException}: map / group / extension を含む型を出力しようとした</li>
 *   <li>{@link IllegalArgumentException}: 値が JSON にできない(範囲外の Timestamp / Duration、
 *       TypeRegistry に無い型を詰めた Any、入れ子が深すぎる(Jackson の上限 1000 段を超える)等)</li>
 *   <li>{@link IOException}: 書き出し先への書き込みに失敗した(ディスクの空き不足、通信の切断など)</li>
 * </ul>
 * 例外が起きた時点までの JSON は書き出し先に出力済みのことがある(途中までの不完全な JSON)。
 * {@code writeTo(OutputStream)} / {@code writeTo(Writer)} では、閉じ括弧を自動で補わないので、
 * 途中までの出力が「正しい JSON」に見えてしまうことはない。
 */
public final class ProtoJsonPrinter {

    /** oneof のどのメンバーも設定されていないことを表す印(writeMessage 内で使う)。 */
    private static final Object NO_MEMBER_SET = new Object();

    /** JsonGenerator を作る製造元(Jackson の設定を持つ)。 */
    private final JsonFactory jsonFactory;
    /** Timestamp などの共通の型の書き出し担当。 */
    private final WellKnownTypes wellKnownTypes;
    /** 型ごとの書き出し手順のキャッシュ(このインスタンス専用)。 */
    private final MessageLayout.Cache layouts;

    // コンストラクタは private。インスタンスは create() か builder() を通して作る
    private ProtoJsonPrinter(Builder builder) {
        this.jsonFactory = builder.jsonFactory;
        this.wellKnownTypes = new WellKnownTypes(builder.typeRegistry);
        this.layouts = new MessageLayout.Cache(builder.layoutCacheSize);
    }

    /** 既定の設定で作る。 */
    public static ProtoJsonPrinter create() {
        return builder().build();
    }

    /** 設定を変えて作るための Builder を返す。例: {@code ProtoJsonPrinter.builder().typeRegistry(r).build()} */
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
     *
     * @param message JSON にするメッセージ(生成コードのメッセージ、Builder、DynamicMessage のどれでもよい)
     * @return JSON 文字列
     */
    public String print(MessageOrBuilder message) {
        StringWriter out = new StringWriter();
        try {
            writeTo(message, out);
        } catch (IOException e) {
            // StringWriter への書き込みは失敗しないので、ここに来ることは基本的に無い。
            // throws IOException を宣言しなくて済むよう、UncheckedIOException に包んでいる
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * message を JSON として UTF-8 で書き出す(改行なし)。文字列を作らないので {@link #print} より効率がよい。
     * {@code out} は閉じない(flush はする)。
     *
     * @param message JSON にするメッセージ
     * @param out     書き出し先(HTTP 応答の OutputStream など)
     * @throws IOException 書き出し先への書き込みに失敗した場合
     */
    public void writeTo(MessageOrBuilder message, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        // try-with-resources: { } を抜けるときに generator が自動で閉じられ、溜まっていた内容が out に書き出される
        try (JsonGenerator g = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            configureOwnedGenerator(g);
            writeTo(message, g);
        }
    }

    /**
     * message を JSON として書き出す(改行なし)。{@code out} は閉じない(flush はする)。
     *
     * @param message JSON にするメッセージ
     * @param out     書き出し先
     * @throws IOException 書き出し先への書き込みに失敗した場合
     */
    public void writeTo(MessageOrBuilder message, Writer out) throws IOException {
        Objects.requireNonNull(out, "out");
        try (JsonGenerator g = jsonFactory.createGenerator(out)) {
            configureOwnedGenerator(g);
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
     *
     * @param message   JSON にするメッセージ
     * @param generator 書き出しに使う JsonGenerator(呼び出し側で作ったもの)
     * @throws IOException 書き出し先への書き込みに失敗した場合
     */
    public void writeTo(MessageOrBuilder message, JsonGenerator generator) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(generator, "generator");
        try {
            writeMessage(message, generator);
        } catch (StreamConstraintsException e) {
            // Jackson の上限(主に入れ子の深さ 1000 段)を超えた。StreamConstraintsException は IOException の一種だが、
            // 原因は書き出し先ではなくメッセージの形なので、IllegalArgumentException にして I/O の失敗と区別できるようにする。
            // (gRPC で受信したメッセージは Protobuf の制限で入れ子が 100 段までなので、通常はここに来ない)
            throw new IllegalArgumentException("message cannot be written as JSON: " + e.getMessage(), e);
        }
    }

    /** キャッシュしている型の数(テスト用。package-private なので利用者からは見えない)。 */
    int cachedLayoutCount() {
        return layouts.size();
    }

    /**
     * このクラスが自分で作った JsonGenerator の設定。
     * <ul>
     *   <li>AUTO_CLOSE_TARGET を無効: generator を閉じても、利用者から預かった out は閉じない</li>
     *   <li>AUTO_CLOSE_JSON_CONTENT を無効: 途中で例外が起きて generator を閉じるとき、Jackson が閉じ括弧
     *       (波括弧や角括弧の閉じ側)を自動で補わないようにする。補われると、途中までの不完全なデータが「正しい JSON」に
     *       見えてしまい、受け取った側が完全なデータと誤認する恐れがあるため</li>
     * </ul>
     */
    private static void configureOwnedGenerator(JsonGenerator g) {
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
    }

    // ============================================================================================
    // 書き出し処理
    // ============================================================================================

    /** メッセージ m を 1 つの JSON オブジェクト({...})として書く。入れ子のメッセージは再帰呼び出しで書く。 */
    private void writeMessage(MessageOrBuilder m, JsonGenerator g) throws IOException {
        Descriptor descriptor = m.getDescriptorForType();   // このメッセージの型の説明書
        if (MessageLayout.isWellKnownType(descriptor)) {
            wellKnownTypes.write(m, g);   // Timestamp などの共通の型は、専用の書き方に任せる
            return;
        }
        MessageLayout layout = layouts.get(descriptor);    // この型の書き出し手順(初回だけ作られる)

        // oneof ごとに「どのメンバーが設定されているか」を最初に必要になった時点で 1 回だけ調べて覚えておく。
        // (メンバーごとに hasField を呼ぶと、oneof の種類が多いほど無駄が増えるため)
        // 要素: null = 未確認、NO_MEMBER_SET = どれも未設定、それ以外 = 設定されているメンバーの FieldDescriptor
        Object[] oneofCases = null;

        g.writeStartObject();   // {
        // フィールドを番号順に 1 つずつ見て、出力すべきものだけ「"キー":値」を書く
        for (MessageLayout.Field field : layout.fields) {
            FieldDescriptor fd = field.descriptor;

            // ---- repeated(リスト)のフィールド ----
            if (fd.isRepeated()) {
                int count = m.getRepeatedFieldCount(fd);   // 要素の数
                if (count == 0) {
                    continue; // 空の repeated は出力しない(continue は「このフィールドは飛ばして次へ」)
                }
                g.writeFieldName(field.jsonName);   // "キー":
                g.writeStartArray();                // [
                for (int i = 0; i < count; i++) {
                    writeValue(fd, m.getRepeatedField(fd, i), g);   // i 番目の要素
                }
                g.writeEndArray();                  // ]
                continue;
            }

            // ---- 単数のフィールド: 出力するかどうかを決める ----
            if (field.oneofIndex >= 0) {
                // oneof のメンバー: その oneof で設定されているのがこのフィールドなら出力する
                if (oneofCases == null) {
                    oneofCases = new Object[layout.oneofCount];
                }
                Object setMember = oneofCases[field.oneofIndex];
                if (setMember == null) {
                    // まだ調べていない oneof: どのメンバーが設定されているかを 1 回だけ調べる
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
            g.writeFieldName(field.jsonName);       // "キー":
            writeValue(fd, m.getField(fd), g);      // 値
        }
        g.writeEndObject();     // }
    }

    /** 値を 1 つ書く。message 型なら入れ子のオブジェクトとして書き、それ以外は ScalarValues に任せる。 */
    private void writeValue(FieldDescriptor fd, Object value, JsonGenerator g) throws IOException {
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            writeMessage((MessageOrBuilder) value, g);   // 再帰呼び出し(入れ子のメッセージ)
        } else {
            ScalarValues.write(fd.getType(), value, g);
        }
    }

    // ============================================================================================
    // 設定
    // ============================================================================================

    /**
     * {@link ProtoJsonPrinter} の設定。値を設定するメソッドは自分自身(this)を返すので、
     * {@code builder().typeRegistry(r).jsonFactory(f).build()} のように続けて書ける(メソッドチェーン)。
     */
    public static final class Builder {

        private JsonFormat.TypeRegistry typeRegistry = JsonFormat.TypeRegistry.getEmptyTypeRegistry();
        private JsonFactory jsonFactory = defaultJsonFactory();
        private int layoutCacheSize = MessageLayout.Cache.DEFAULT_MAX_ENTRIES;

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
         * 型ごとの書き出し手順をキャッシュする型の数の上限(テスト用。package-private なので利用者からは見えない)。
         * 上限を超えた型は、キャッシュせずに毎回手順を作る。
         */
        Builder layoutCacheSize(int layoutCacheSize) {
            this.layoutCacheSize = layoutCacheSize;
            return this;
        }

        /**
         * 既定の JsonFactory。Jackson の既定に対して次の 1 点だけ変えている。
         * <ul>
         *   <li>{@link JsonWriteFeature#COMBINE_UNICODE_SURROGATES_IN_UTF8} を有効化: Jackson 2.x の既定では
         *       UTF-8 で書く場合だけ BMP 外の文字(絵文字等)をサロゲートペアの Unicode エスケープで書くため、
         *       {@link ProtoJsonPrinter#print}(Writer 経由)と
         *       {@link ProtoJsonPrinter#writeTo(MessageOrBuilder, OutputStream)} の出力が異なってしまう。
         *       有効にすると UTF-8 でもそのまま書くので両者が一致する(JsonFormat もそのまま書く)</li>
         * </ul>
         */
        public static JsonFactory defaultJsonFactory() {
            return JsonFactory.builder()
                    .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
                    .build();
        }

        /** 設定した内容で ProtoJsonPrinter を作る。 */
        public ProtoJsonPrinter build() {
            return new ProtoJsonPrinter(this);
        }
    }
}
