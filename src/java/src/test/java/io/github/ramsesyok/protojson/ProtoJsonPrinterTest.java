// =====================================================================================================
// ProtoJsonPrinterTest: ProtoJsonPrinter(本ライブラリ)の振る舞いを 1 つずつ確かめるテスト
// =====================================================================================================
//
// 【このテストの読み方】
//   期待する出力を JSON 文字列のまま書いているので、「どんなメッセージが、どんな JSON になるか」の具体例集として読める。
//   ライブラリの仕様(出力規則)を知りたいときは、まずこのファイルを見るとよい。
//
// 【テストの分類】(@Nested で分類している。下で説明)
//   - Int64        : int64 系が JSON の数値になること(本ライブラリの目的)
//   - Presence     : どんなときにキーが出力される / 省略されるか
//   - Keys         : キー名と、キーの並び順
//   - Values       : enum / bytes / 浮動小数点 / 文字列のエスケープの表現
//   - WellKnown    : Timestamp などの Well-Known Types(google.protobuf の共通の型)の表現
//   - Unsupported  : 未対応の構造(map / group / extension)で例外になること
//   - Api          : 出力先(OutputStream / Writer / JsonGenerator)、Builder や DynamicMessage、スレッドセーフ
//   - PerRecord    : 1 レコードずつ JSON にする使い方(出力が必ず 1 行になる等)
//
// 【テストで使うメッセージ】
//   src/test/proto/protojson_test.proto(全フィールド型を持つ AllTypes など)と、
//   src/test/proto/protojson_test_proto2.proto(proto2 の確認用)から自動生成されたクラスを使う。
//
// 【Java の文字列の書き方の注意】
//   期待値の JSON を Java の文字列で書くため、次のように書き換えている。
//     JSON の "      → Java では \"
//     JSON の \n(バックスラッシュ + n の 2 文字) → Java では \\n
//   例えば JSON の {"a":"x\ny"} は、Java では "{\"a\":\"x\\ny\"}" と書く。
// =====================================================================================================

package io.github.ramsesyok.protojson;

// ---- Jackson(JSON を扱うライブラリ)----
import com.fasterxml.jackson.core.JsonFactory;       // JsonGenerator を作る「製造元」
import com.fasterxml.jackson.core.JsonGenerator;     // JSON を少しずつ書き出す道具
import com.fasterxml.jackson.databind.JsonNode;      // 読み込んだ JSON の 1 つの節(木構造)
import com.fasterxml.jackson.databind.ObjectMapper;  // JSON 文字列を読み込む道具
// ---- Protobuf の Well-Known Types(google.protobuf パッケージの共通の型)とユーティリティ ----
import com.google.protobuf.Any;            // 任意のメッセージを詰められる型
import com.google.protobuf.BoolValue;      // bool を包んだ型(ラッパー型)
import com.google.protobuf.ByteString;     // Protobuf の bytes 型の値
import com.google.protobuf.BytesValue;     // bytes のラッパー型
import com.google.protobuf.DoubleValue;    // double のラッパー型
import com.google.protobuf.Duration;       // 時間の長さ
import com.google.protobuf.DynamicMessage; // 生成コードを使わずに扱うメッセージ
import com.google.protobuf.Empty;          // 中身の無いメッセージ
import com.google.protobuf.FieldMask;      // フィールド名の一覧
import com.google.protobuf.FloatValue;     // float のラッパー型
import com.google.protobuf.Int32Value;     // int32 のラッパー型
import com.google.protobuf.Int64Value;     // int64 のラッパー型
import com.google.protobuf.ListValue;      // JSON の配列を表す型
import com.google.protobuf.NullValue;      // JSON の null を表す enum
import com.google.protobuf.StringValue;    // string のラッパー型
import com.google.protobuf.Struct;         // JSON のオブジェクトを表す型
import com.google.protobuf.Timestamp;      // 日時
import com.google.protobuf.UInt32Value;    // uint32 のラッパー型
import com.google.protobuf.UInt64Value;    // uint64 のラッパー型
import com.google.protobuf.Value;          // JSON の任意の値を表す型
import com.google.protobuf.util.JsonFormat;  // Protobuf 公式の JSON 変換(ここでは TypeRegistry を使うため)
// ---- テスト用 proto から生成されたクラス ----
import io.github.ramsesyok.protojson.testproto.AllTypes;     // すべてのフィールド型を持つメッセージ
import io.github.ramsesyok.protojson.testproto.Color;        // テスト用の enum
import io.github.ramsesyok.protojson.testproto.HasMapDeep;   // ネストした先に map を持つメッセージ
import io.github.ramsesyok.protojson.testproto.Nested;       // 入れ子用の小さなメッセージ
import io.github.ramsesyok.protojson.testproto.OutOfOrder;   // 宣言順とフィールド番号順が違うメッセージ
import io.github.ramsesyok.protojson.testproto.WithMap;      // map を持つメッセージ
import io.github.ramsesyok.protojson.testproto2.Extendable;  // proto2: extension を持てるメッセージ
import io.github.ramsesyok.protojson.testproto2.Proto2Plain; // proto2: 普通のメッセージ
import io.github.ramsesyok.protojson.testproto2.WithGroup;   // proto2: group を持つメッセージ
// ---- JUnit 5(テスト用ライブラリ)----
import org.junit.jupiter.api.DisplayName;                     // テスト結果に表示する名前
import org.junit.jupiter.api.Test;                            // 「これはテストメソッド」という印
import org.junit.jupiter.params.ParameterizedTest;            // 「値を変えて何回も実行するテスト」という印
import org.junit.jupiter.params.provider.ValueSource;         // ParameterizedTest に渡す値の一覧

import java.io.ByteArrayOutputStream;          // 書いたバイト列をメモリに溜める OutputStream
import java.io.IOException;                    // 入出力の例外
import java.io.StringWriter;                   // 書いた文字列をメモリに溜める Writer
import java.nio.charset.StandardCharsets;      // 文字コードの定数(UTF_8 など)
import java.util.ArrayList;                    // 要素を追加できるリスト
import java.util.List;                         // リストの型
import java.util.concurrent.ExecutorService;   // 複数のスレッドで処理を実行する仕組み(スレッドプール)
import java.util.concurrent.Executors;         // ExecutorService を作るための道具
import java.util.concurrent.Future;            // 別スレッドで実行中の処理の「結果の引換券」

// import static で、assertEquals などを「Assertions.」を付けずに呼べるようにしている
import static org.junit.jupiter.api.Assertions.assertEquals;  // 2 つの値が等しいこと
import static org.junit.jupiter.api.Assertions.assertFalse;   // 条件が false であること
import static org.junit.jupiter.api.Assertions.assertThrows;  // 指定した例外が発生すること
import static org.junit.jupiter.api.Assertions.assertTrue;    // 条件が true であること

/**
 * ProtoJsonPrinter の振る舞い(出力例そのものが仕様の説明になるよう、期待値は JSON 文字列で書く)。
 * 注: テスト用 proto の message 名 {@code Nested} と JUnit の {@code @Nested} が衝突するため、
 * JUnit 側は完全修飾名で書いている。
 */
class ProtoJsonPrinterTest {

    /** JSON 文字列を読み込む道具(Jackson)。数値か文字列かなど、JSON の「型」を確かめるのに使う。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** テスト対象。既定の設定の ProtoJsonPrinter。 */
    private final ProtoJsonPrinter printer = ProtoJsonPrinter.create();

    // -------------------------------------------------------------------------------------------------
    // @Nested を付けたクラス(入れ子クラス)は、テストを分類するためのもの。テスト結果も分類ごとにまとめて表示される。
    // 本来は「@Nested」と書けばよいが、テスト用 proto に Nested という名前のメッセージがあり名前がぶつかるので、
    // 「@org.junit.jupiter.api.Nested」とパッケージ名から全部書いている(完全修飾名)。
    // -------------------------------------------------------------------------------------------------

    @org.junit.jupiter.api.Nested
    @DisplayName("int64 系は JSON の数値")
    class Int64 {

        // @ParameterizedTest + @ValueSource: 下の 6 つの値それぞれについて、同じテストを 1 回ずつ(合計 6 回)実行する。
        // 値はメソッドの引数 v として渡される。name = "{0}" は、結果の表示名に v の値を使うという意味。
        // 値の選び方: 9007199254740993 は 2^53+1(JavaScript の数値では正確に表せない値)、
        //             Long.MAX_VALUE / Long.MIN_VALUE は Java の long の最大値 / 最小値。
        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {1, -1, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE, -9007199254740993L})
        @DisplayName("int64 / sint64 / sfixed64 が数値で出力され、long として値が一致する")
        void int64Family(long v) throws Exception {
            // int64 系の 3 つの型(int64 / sint64 / sfixed64)と、int64 の repeated に同じ値を入れる
            AllTypes m = AllTypes.newBuilder().setFInt64(v).setFSint64(v).setFSfixed64(v).addRInt64(v).build();
            String json = printer.print(m);

            // 値が "123" のようにダブルクォートで囲まれず、そのまま数値として出力されていること
            assertEquals("{\"fInt64\":" + v + ",\"fSint64\":" + v + ",\"fSfixed64\":" + v + ",\"rInt64\":[" + v + "]}", json);

            // JSON として読み込んでも、整数の数値であり、long として元の値と一致すること(桁落ちしていない)
            JsonNode n = MAPPER.readTree(json);
            for (String key : List.of("fInt64", "fSint64", "fSfixed64")) {
                assertTrue(n.get(key).isIntegralNumber());
                assertEquals(v, n.get(key).longValue());
            }
        }

        @Test
        @DisplayName("数字だけの string は文字列のまま")
        void numericStringsStayStrings() {
            // string 型のフィールドに数字だけを入れても、数値に変換してはいけない(型で判断しているため変換されない)
            AllTypes m = AllTypes.newBuilder().setFString("12345").addRString("-9223372036854775808")
                    .setCString("9007199254740993").build();
            assertEquals("{\"fString\":\"12345\",\"rString\":[\"-9223372036854775808\"],\"cString\":\"9007199254740993\"}",
                    printer.print(m));
        }

        @Test
        @DisplayName("uint64 / fixed64 は対象外(JsonFormat と同じく符号なしの文字列)、uint32 / fixed32 は符号なしの数値")
        void unsigned() {
            // Java には符号なし整数が無いので、uint32 の最大値 4294967295 は Java の int では -1 として保持される。
            // 同様に uint64 の最大値 18446744073709551615 は Java の long では -1 になる。
            // JSON では、これを符号なしの値として出力する(-1 とは出さない)。
            AllTypes m = AllTypes.newBuilder().setFUint32(-1).setFFixed32(-2).setFUint64(-1).setFFixed64(5).build();
            assertEquals("{\"fUint32\":4294967295,\"fFixed32\":4294967294,"
                    + "\"fUint64\":\"18446744073709551615\",\"fFixed64\":\"5\"}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("フィールドの有無(presence)")
    class Presence {
        // presence(プレゼンス)とは「そのフィールドに値が設定されているかどうか」を区別できる性質のこと。
        // proto3 の普通のスカラー(int64 x = 1; など)は presence を持たず、「0 が入っている」と「何も入れていない」を
        // 区別できない。そのため、0(デフォルト値)のときは JSON にキーを出さない決まりになっている。

        @Test
        @DisplayName("presence の無いフィールドはデフォルト値(0 / 空文字 / false / 先頭の enum)なら省略")
        void defaultsOmitted() {
            // わざとデフォルト値を設定しても、出力は空のオブジェクト {} になる
            AllTypes m = AllTypes.newBuilder().setFInt64(0).setFString("").setFBool(false)
                    .setFEnum(Color.COLOR_UNSPECIFIED).setFDouble(0.0).build();
            assertEquals("{}", printer.print(m));
        }

        @Test
        @DisplayName("proto3 optional はデフォルト値でも設定されていれば出力")
        void optionalPresent() {
            // proto で「optional」を付けたフィールドは presence を持つので、0 や空文字でも「設定した」なら出力される
            AllTypes m = AllTypes.newBuilder().setOInt64(0).setOString("").setOEnum(Color.COLOR_UNSPECIFIED)
                    .setOBool(false).setODouble(0.0).build();
            assertEquals("{\"oInt64\":0,\"oString\":\"\",\"oEnum\":\"COLOR_UNSPECIFIED\",\"oBool\":false,\"oDouble\":0.0}",
                    printer.print(m));
        }

        @Test
        @DisplayName("message フィールドは設定されていれば中身が空でも {} を出力")
        void emptyMessagePresent() {
            // message 型のフィールドは presence を持つ。中身が空のメッセージを設定すると {} が出力される
            assertEquals("{\"fNested\":{}}", printer.print(AllTypes.newBuilder().setFNested(Nested.getDefaultInstance()).build()));
        }

        @Test
        @DisplayName("oneof: 設定されたメンバーだけを出力(デフォルト値でも出力)、未設定ならどのキーも無い")
        void oneof() {
            // oneof は「複数のフィールドのうち、どれか 1 つだけ」を持てる仕組み。設定されたものだけが出力される。
            // AllTypes には oneof が 2 つある(choice と second)。それぞれ独立して 1 つずつ設定できる。
            assertEquals("{\"cInt64\":0}", printer.print(AllTypes.newBuilder().setCInt64(0).build()));  // 0 でも出る
            assertEquals("{\"cString\":\"x\",\"sBool\":false}",
                    printer.print(AllTypes.newBuilder().setCString("x").setSBool(false).build()));      // 2 つの oneof
            assertEquals("{\"cNested\":{\"id\":1}}",
                    printer.print(AllTypes.newBuilder().setCNested(Nested.newBuilder().setId(1)).build()));
            assertEquals("{\"cEnum\":\"COLOR_UNSPECIFIED\"}",
                    printer.print(AllTypes.newBuilder().setCEnum(Color.COLOR_UNSPECIFIED).build()));
            assertEquals("{\"sDouble\":1.5}", printer.print(AllTypes.newBuilder().setSDouble(1.5).build()));
            assertEquals("{}", printer.print(AllTypes.getDefaultInstance()));                           // 何も設定なし
        }

        @Test
        @DisplayName("空の repeated は省略")
        void emptyRepeatedOmitted() {
            // repeated(リスト)のフィールドは、要素が 0 個なら "rInt64":[] のようには出さず、キーごと省略する
            assertEquals("{\"fInt64\":1}", printer.print(AllTypes.newBuilder().setFInt64(1).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("キー名と順序")
    class Keys {

        @Test
        @DisplayName("キーは json_name(lowerCamelCase、json_name 指定があればそれ)")
        void jsonName() {
            // proto のフィールド名 f_int64 は、JSON では fInt64(単語の区切りを大文字にする lowerCamelCase)になる。
            // proto で [json_name = "customJSON"] と指定したフィールドは、その名前になる。
            assertEquals("{\"fInt64\":1,\"customJSON\":2}",
                    printer.print(AllTypes.newBuilder().setFInt64(1).setCustomJson(2).build()));
        }

        @Test
        @DisplayName("キーの順序はフィールド番号順(宣言順ではない)")
        void fieldNumberOrder() {
            // OutOfOrder は proto の中で z(5番) → name(3番) → a(1番) → inner(4番) → ids(2番) の順に書いてある。
            // JSON のキーは、書いた順ではなくフィールド番号の小さい順(a, ids, name, inner, z)に並ぶ(JsonFormat と同じ)。
            OutOfOrder m = OutOfOrder.newBuilder().setZ(5).setName("n").setA(1).addIds(2)
                    .setInner(Nested.newBuilder().setId(3)).build();
            assertEquals("{\"a\":1,\"ids\":[2],\"name\":\"n\",\"inner\":{\"id\":3},\"z\":5}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("値の表現")
    class Values {

        @Test
        @DisplayName("enum は名前、proto3 で未知の値は数値")
        void enums() {
            // enum は値の名前(文字列)で出力する。
            // proto3 の enum は、定義に無い数値(ここでは 1000)も保持できる。名前が無いので数値で出力する。
            // addREnumValue(1000) は、enum のリストに「数値で」値を追加するメソッド(生成コードが用意している)。
            assertEquals("{\"fEnum\":\"COLOR_RED\",\"rEnum\":[\"COLOR_GREEN\",1000]}",
                    printer.print(AllTypes.newBuilder().setFEnum(Color.COLOR_RED)
                            .addREnum(Color.COLOR_GREEN).addREnumValue(1000).build()));
        }

        @Test
        @DisplayName("bytes は標準 Base64(パディングあり)")
        void bytes() {
            // bytes(バイト列)は JSON にそのまま書けないので、Base64 という方式で文字列にする。
            // (byte) 0xfb のような書き方は、16 進数の値を byte 型に変換している。
            // "a" は Base64 で "YQ=="(末尾の = は長さを揃えるための「パディング」)、空のバイト列は "" になる。
            assertEquals("{\"fBytes\":\"+/8A\",\"rBytes\":[\"YQ==\",\"\"]}",
                    printer.print(AllTypes.newBuilder().setFBytes(ByteString.copyFrom(new byte[]{(byte) 0xfb, (byte) 0xff, 0}))
                            .addRBytes(ByteString.copyFromUtf8("a")).addRBytes(ByteString.EMPTY).build()));
        }

        @Test
        @DisplayName("float / double は数値、NaN / Infinity は文字列")
        void floatingPoint() {
            // NaN(非数)や Infinity(無限大)は JSON の数値で表せないので、"NaN" / "Infinity" という文字列で出力する。
            // 1e21 は 1×10^21。Java の書き方に合わせて 1.0E21 と出力される(JsonFormat と同じ)。
            // -0.0 は「マイナスのゼロ」。浮動小数点数には +0.0 と -0.0 の区別がある。
            AllTypes m = AllTypes.newBuilder().setFFloat(0.1f).setFDouble(1e21)
                    .addRFloat(Float.NaN).addRFloat(Float.NEGATIVE_INFINITY).addRFloat(-0.0f)
                    .addRDouble(Double.POSITIVE_INFINITY).addRDouble(123456789.125).build();
            assertEquals("{\"fFloat\":0.1,\"fDouble\":1.0E21,\"rFloat\":[\"NaN\",\"-Infinity\",-0.0],"
                    + "\"rDouble\":[\"Infinity\",1.23456789125E8]}", printer.print(m));
        }

        @Test
        @DisplayName("文字列のエスケープ: 改行・引用符・制御文字はエスケープ、日本語・絵文字・< > & = ' はそのまま")
        void stringEscaping() {
            // 入力の文字列: 改行、ダブルクォート、バックスラッシュ、制御文字(コード 1 の文字)、日本語、絵文字、記号
            // JSON では、改行は \n、ダブルクォートは \"、バックスラッシュは \\、制御文字はユニコードエスケープになる。
            // 日本語・絵文字・< > & = ' はそのまま出力する(Protobuf 公式の JsonFormat は < > & = ' もエスケープするが、
            // 本ライブラリはしない。JSON の値としてはどちらも同じ)。
            AllTypes m = AllTypes.newBuilder().setFString("a\nb \"q\" \\ \u0001 日本 😀 <tag> & a=b 'q'").build();
            assertEquals("{\"fString\":\"a\\nb \\\"q\\\" \\\\ \\u0001 日本 😀 <tag> & a=b 'q'\"}", printer.print(m));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Well-Known Types(JsonFormat と同じ表現)")
    class WellKnown {
        // Well-Known Types とは、Protobuf が最初から用意している共通の型(google.protobuf パッケージ)のこと。
        // JSON にするときの表現が特別に決められている(例: Timestamp は日時の文字列)。本ライブラリもそれに合わせる。

        @Test
        @DisplayName("Timestamp / Duration は文字列、ラッパー型は中の値(Int64Value / UInt64Value は文字列のまま)")
        void timestampDurationWrappers() {
            AllTypes m = AllTypes.newBuilder()
                    // 日時: 1700000000 秒(1970-01-01 からの経過秒数)+ 0.123 秒 → "2023-11-14T22:13:20.123Z"
                    .setWTimestamp(Timestamp.newBuilder().setSeconds(1700000000L).setNanos(123000000))
                    // 時間の長さ: -3.5 秒 → "-3.500s"
                    .setWDuration(Duration.newBuilder().setSeconds(-3).setNanos(-500000000))
                    // ラッパー型(値を 1 つ包んだ型)は、中の値をそのまま出す。
                    // ただし Int64Value / UInt64Value は対象外で、JsonFormat と同じく文字列のまま
                    .setWInt64(Int64Value.of(9007199254740993L))
                    .setWUint64(UInt64Value.of(-1))
                    .setWInt32(Int32Value.of(-5))
                    .setWUint32(UInt32Value.of(-1))
                    .setWBool(BoolValue.of(false))
                    .setWString(StringValue.of("s"))
                    .setWBytes(BytesValue.of(ByteString.copyFromUtf8("a")))
                    .setWFloat(FloatValue.of(Float.NaN))
                    .setWDouble(DoubleValue.of(0.5))
                    // Timestamp のリスト(5000 ナノ秒 = 5 マイクロ秒 → 小数部 6 桁で出力)
                    .addRTimestamp(Timestamp.newBuilder().setSeconds(0).setNanos(5000))
                    .addRInt64Value(Int64Value.of(1))
                    .build();
            assertEquals("{\"wTimestamp\":\"2023-11-14T22:13:20.123Z\",\"wDuration\":\"-3.500s\","
                    + "\"wInt64\":\"9007199254740993\",\"wUint64\":\"18446744073709551615\",\"wInt32\":-5,"
                    + "\"wUint32\":4294967295,\"wBool\":false,\"wString\":\"s\",\"wBytes\":\"YQ==\","
                    + "\"wFloat\":\"NaN\",\"wDouble\":0.5,\"rTimestamp\":[\"1970-01-01T00:00:00.000005Z\"],"
                    + "\"rInt64Value\":[\"1\"]}", printer.print(m));
        }

        @Test
        @DisplayName("Struct / Value / ListValue / FieldMask / Empty は JsonFormat の表現")
        void structAndOthers() {
            // Struct は JSON のオブジェクト、Value は任意の JSON の値、ListValue は配列をそのまま表す型。
            // FieldMask はフィールド名をカンマ区切りの文字列で表す(名前は lowerCamelCase に変換される)。
            AllTypes m = AllTypes.newBuilder()
                    .setWStruct(Struct.newBuilder().putFields("k", Value.newBuilder().setNumberValue(1.5).build()))
                    .setWValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
                    .setWList(ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("<x>")))
                    .setWMask(FieldMask.newBuilder().addPaths("f_int64").addPaths("f_nested.name"))
                    .setWEmpty(Empty.getDefaultInstance())
                    .build();
            assertEquals("{\"wStruct\":{\"k\":1.5},\"wValue\":null,\"wList\":[\"<x>\"],"
                    + "\"wMask\":\"fInt64,fNested.name\",\"wEmpty\":{}}", printer.print(m));
        }

        @Test
        @DisplayName("Any: TypeRegistry に登録した型なら出力できる(中の int64 は JsonFormat と同じく文字列)")
        void anyWithRegistry() {
            // Any は「どんなメッセージでも詰められる」型。JSON にするには、中身の型を知っている必要があるので、
            // TypeRegistry(型の登録簿)に Nested を登録した printer を作る。
            ProtoJsonPrinter withRegistry = ProtoJsonPrinter.builder()
                    .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                    .build();
            // Any.pack(メッセージ) で、メッセージを Any に詰める
            AllTypes m = AllTypes.newBuilder().setWAny(Any.pack(Nested.newBuilder().setId(5).build())).build();
            // "@type" に中身の型名が入る。Any の中身は JsonFormat で出力されるので、int64 の id は "5"(文字列)のまま
            assertEquals("{\"wAny\":{\"@type\":\"type.googleapis.com/protojson.test.Nested\",\"id\":\"5\"}}",
                    withRegistry.print(m));
        }

        @Test
        @DisplayName("Any: TypeRegistry に無い型は IllegalArgumentException")
        void anyWithoutRegistry() {
            AllTypes m = AllTypes.newBuilder().setWAny(Any.pack(Nested.newBuilder().setId(5).build())).build();
            // assertThrows(例外の種類, 処理) は、「その処理を実行すると、その種類の例外が発生する」ことを確かめる。
            // 発生した例外が戻り値として返るので、メッセージの中身も確かめられる。
            // 「() -> printer.print(m)」はラムダ式で、「printer.print(m) を実行する処理」を値として渡している。
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> printer.print(m));
            // 例外のメッセージに、解決方法(typeRegistry に登録すること)が書かれていること
            assertTrue(e.getMessage().contains("typeRegistry"), e.getMessage());
        }

        @Test
        @DisplayName("範囲外の Timestamp / Duration は IllegalArgumentException")
        void invalidTimestampDuration() {
            // Timestamp は 9999-12-31T23:59:59Z(1970-01-01 から 253402300799 秒)までしか表せない。
            // 253402300800 秒はその 1 秒後なので範囲外。
            assertThrows(IllegalArgumentException.class, () -> printer.print(
                    AllTypes.newBuilder().setWTimestamp(Timestamp.newBuilder().setSeconds(253402300800L)).build()));
            // Duration は秒とナノ秒の符号が揃っていなければならない(+1 秒と -1 ナノ秒の組み合わせは不正)
            assertThrows(IllegalArgumentException.class, () -> printer.print(
                    AllTypes.newBuilder().setWDuration(Duration.newBuilder().setSeconds(1).setNanos(-1)).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("未対応の構造は型の時点で UnsupportedOperationException")
    class Unsupported {
        // 本ライブラリは map / group / extension に対応していない。
        // 「その型のメッセージを出力しようとした時点で必ず例外」になる(値が入っているかどうかに関係なく)。
        // こうすることで、「特定のデータが来たときだけ本番で失敗する」ことを防ぎ、テストで早めに気付けるようにしている。

        @Test
        @DisplayName("map フィールド(値が空でも、ネストした先にあっても)")
        void map() {
            // WithMap は map フィールド counts を持つ。counts に何も入れていなくても例外になる
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> printer.print(WithMap.newBuilder().setId(1).build()));
            // 例外のメッセージに、どのフィールドが原因かが書かれていること
            assertTrue(e.getMessage().contains("map field 'counts'"), e.getMessage());
            // HasMapDeep は自分は map を持たないが、フィールドの型(WithMap)が map を持つ → これも例外になる
            assertThrows(UnsupportedOperationException.class, () -> printer.print(HasMapDeep.getDefaultInstance()));
        }

        @Test
        @DisplayName("proto2 の group と extension")
        void proto2() {
            // group と extension は proto2(古い文法)の機能
            assertThrows(UnsupportedOperationException.class, () -> printer.print(WithGroup.getDefaultInstance()));
            assertThrows(UnsupportedOperationException.class, () -> printer.print(Extendable.getDefaultInstance()));
        }

        @Test
        @DisplayName("参考: proto2 の通常のメッセージ(optional / required)は JsonFormat と同じ出力")
        void proto2Plain() {
            // JsonFormatOracle は「Protobuf 公式の JsonFormat の出力の int64 だけを数値にしたもの」を作るテスト用の道具。
            // 本ライブラリの出力がそれと同じなら、JsonFormat と同じ規則で出力できていると言える。
            JsonFormatOracle oracle = new JsonFormatOracle();
            for (Proto2Plain m : List.of(
                    Proto2Plain.newBuilder().setId(0).build(),
                    Proto2Plain.newBuilder().setId(Long.MIN_VALUE).setCount(5).setName("").build(),
                    Proto2Plain.newBuilder().setId(1).setCount(0).build())) {
                assertEquals(oracle.expected(m), printer.print(m));
            }
            // proto2 のフィールドは presence を持つので、0 でも設定していれば出力される
            assertEquals("{\"id\":0}", printer.print(Proto2Plain.newBuilder().setId(0).build()));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("入出力 API")
    class Api {

        /** このグループのテストで共通に使うメッセージ(int64 の最小値、日本語・絵文字・改行、入れ子のリスト)。 */
        private final AllTypes sample = AllTypes.newBuilder().setFInt64(Long.MIN_VALUE).setFString("日本語 😀\n")
                .addRNested(Nested.newBuilder().setId(1)).build();

        @Test
        @DisplayName("writeTo(OutputStream) は UTF-8 で print と同じ内容を書き、ストリームを閉じない")
        void outputStream() throws IOException {
            // CloseTrackingOutputStream は、このファイルの最後で定義した「close されたかを記録できる」書き出し先
            CloseTrackingOutputStream out = new CloseTrackingOutputStream();
            printer.writeTo(sample, out);
            // バイト列を UTF-8 として読んだ文字列が、print の結果と同じであること(絵文字も同じ形で書かれていること)
            assertEquals(printer.print(sample), out.toString(StandardCharsets.UTF_8));
            // writeTo は書き出し先を閉じない(続けて別のデータを書けるように)ことを確かめる
            assertFalse(out.closed);
        }

        @Test
        @DisplayName("writeTo(Writer) は print と同じ内容")
        void writer() throws IOException {
            StringWriter out = new StringWriter();
            printer.writeTo(sample, out);
            assertEquals(printer.print(sample), out.toString());
        }

        @Test
        @DisplayName("writeTo(JsonGenerator) で他の JSON に埋め込める")
        void embedInGenerator() throws IOException {
            // 自分で JsonGenerator を使って JSON を書いている途中に、メッセージの JSON を値として埋め込む例。
            // 作りたい JSON: {"type":"allTypes","data": <sample の JSON> }
            StringWriter out = new StringWriter();
            try (JsonGenerator g = new JsonFactory().createGenerator(out)) {
                g.writeStartObject();                     // {
                g.writeStringField("type", "allTypes");   // "type":"allTypes"
                g.writeFieldName("data");                 // ,"data":
                printer.writeTo(sample, g);               // <sample の JSON>
                g.writeEndObject();                       // }
            }
            assertEquals("{\"type\":\"allTypes\",\"data\":" + printer.print(sample) + "}", out.toString());
        }

        @Test
        @DisplayName("Builder・DynamicMessage を渡しても同じ出力")
        void messageOrBuilderVariants() throws Exception {
            String expected = printer.print(sample);
            // Builder(組み立て途中のメッセージ)を渡しても同じ JSON になること
            assertEquals(expected, printer.print(sample.toBuilder()));
            // DynamicMessage(生成コードを使わずに、型情報(Descriptor)だけで扱うメッセージ)でも同じ JSON になること。
            // toByteString() で Protobuf のバイナリにし、parseFrom で DynamicMessage として読み直している。
            assertEquals(expected, printer.print(DynamicMessage.parseFrom(AllTypes.getDescriptor(), sample.toByteString())));
        }

        @Test
        @DisplayName("複数スレッドから同時に使っても結果が同じ(スレッドセーフ)")
        void threadSafety() throws Exception {
            // ProtoJsonPrinter は「1 つ作って、アプリ全体で(複数のスレッドから同時に)使ってよい」と説明している。
            // それが本当かを、8 つのスレッドから同時に同じ printer を使って確かめる。

            // (1) 乱数でテスト用のメッセージを 200 個作り、1 スレッドで JSON にした結果を「正解」として覚えておく
            RandomMessages random = new RandomMessages(99, 2);  // 99 は乱数の種(同じ種なら毎回同じデータになる)
            List<AllTypes> messages = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            ProtoJsonPrinter shared = ProtoJsonPrinter.builder()
                    .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                    .build();
            for (int i = 0; i < 200; i++) {
                // random.next は Message 型を返すので、(AllTypes) で AllTypes 型に変換(キャスト)している
                AllTypes m = (AllTypes) random.next(AllTypes.newBuilder());
                messages.add(m);
                expected.add(shared.print(m)); // 1 スレッドで出した結果を正解とする
            }

            // (2) 8 スレッドのスレッドプールを作り、各スレッドで「200 個 × 20 周」JSON にして正解と比べる
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    // submit(処理) で、処理を別スレッドで実行させる。戻り値の Future から、後で結果を受け取れる。
                    // 処理は「全部正解と同じなら true、1 つでも違えば false」を返す。
                    results.add(pool.submit(() -> {
                        for (int round = 0; round < 20; round++) {
                            for (int i = 0; i < messages.size(); i++) {
                                if (!expected.get(i).equals(shared.print(messages.get(i)))) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    }));
                }
                // (3) 全スレッドの結果が true であること。r.get() はそのスレッドの処理が終わるまで待ってから結果を返す。
                for (Future<Boolean> r : results) {
                    assertTrue(r.get());
                }
            } finally {
                pool.shutdownNow(); // スレッドプールを止める(テストが失敗しても必ず実行)
            }
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("1 レコードずつの JSON 化")
    class PerRecord {

        @Test
        @DisplayName("出力は常に 1 行(文字列中の改行・復帰・行区切り文字はエスケープされ、改行文字を含まない)")
        void alwaysSingleLine() throws Exception {
            // DB に 1 件ずつ保存したり、1 行 1 レコードのファイルに書いたりするには、出力が必ず 1 行である必要がある。
            // 改行(\n)・復帰(\r)を含む文字列や、乱数で作ったいろいろなデータで確かめる。
            RandomMessages random = new RandomMessages(11, 3);
            ProtoJsonPrinter withRegistry = ProtoJsonPrinter.builder()
                    .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(Nested.getDescriptor()).build())
                    .build();
            List<AllTypes> messages = new ArrayList<>();
            messages.add(AllTypes.newBuilder().setFString("a\nb\r\nc").addRString("\n").build());  // 改行入りの文字列
            for (int i = 0; i < 500; i++) {
                messages.add((AllTypes) random.next(AllTypes.newBuilder())); // 制御文字を含む文字列も生成される
            }
            for (AllTypes m : messages) {
                String json = withRegistry.print(m);
                // 出力に改行文字・復帰文字が含まれていないこと(文字列中の改行は \n という 2 文字になっているはず)
                assertFalse(json.contains("\n") || json.contains("\r"), json);
                MAPPER.readTree(json); // 単独でパースできる(JSON として正しくなければ例外が発生してテストが失敗する)
            }
            // 改行入りの文字列が、エスケープされた形で出力されていること
            assertEquals("{\"fString\":\"a\\nb\\r\\nc\",\"rString\":[\"\\n\"]}", printer.print(messages.get(0)));
        }

        @Test
        @DisplayName("SimLog の logs のような repeated の各要素を、そのまま 1 件ずつ JSON にできる")
        void eachElementOfRepeated() throws Exception {
            // 親メッセージ(AllTypes)の中に、Nested のリスト(rNested)を 3 件入れる。
            // これは SimLog の中に ObjectLog のリスト(logs)が入っているのと同じ形。
            AllTypes parent = AllTypes.newBuilder()
                    .addRNested(Nested.newBuilder().setId(Long.MAX_VALUE).setName("a\nb"))
                    .addRNested(Nested.getDefaultInstance())
                    .addRNested(Nested.newBuilder().setId(-1).addValues(9007199254740993L))
                    .build();

            // リストの要素を 1 件ずつ取り出して JSON にする
            List<String> records = new ArrayList<>();
            for (Nested record : parent.getRNestedList()) {
                records.add(printer.print(record));
            }
            assertEquals(List.of("{\"id\":9223372036854775807,\"name\":\"a\\nb\"}", "{}",
                    "{\"id\":-1,\"values\":[9007199254740993]}"), records);

            // 親をまとめて JSON にした結果の各要素と同じ内容
            // (= 1 件ずつ JSON にしても、まとめて JSON にしても、レコードの中身は同じになる)
            JsonNode whole = MAPPER.readTree(printer.print(parent)).get("rNested");
            for (int i = 0; i < records.size(); i++) {
                assertEquals(whole.get(i), MAPPER.readTree(records.get(i)));
            }
        }
    }

    /**
     * テスト用の書き出し先: close() が呼ばれたかどうかを記録する。
     * ByteArrayOutputStream を継承(extends)し、close() だけを上書き(@Override)している。
     */
    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        boolean closed; // 何も入れなければ false から始まる

        @Override
        public void close() throws IOException {
            closed = true;
            super.close(); // 親クラス(ByteArrayOutputStream)の close() も呼ぶ
        }
    }
}
