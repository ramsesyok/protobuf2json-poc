// =====================================================================================================
// package-info.java: パッケージ全体の説明を書くための特別なファイル
// =====================================================================================================
//
// このファイルにはクラスが無く、下の「package 宣言」に付けたコメント(Javadoc)だけがある。
// Javadoc(API 仕様書)を生成すると、このコメントがパッケージの説明ページになる。
// 本ライブラリの仕様(出力規則・JsonFormat との違い・制約・例外・安全性)は、ここにまとめて書いている。
//
// 【用語】
//   Protobuf(Protocol Buffers): Google が作った、データをコンパクトなバイナリで送受信するための形式。gRPC で使われる。
//   JSON: テキストでデータを表す形式。Web API などで広く使われる。
//   int64: 64bit の整数(Java の long)。約 ±922 京まで表せる。
//   JavaScript の数値(double)は約 ±9007 兆(2^53)を超える整数を正確に表せないので、
//   Protobuf 公式の JSON 変換は int64 を "123" のような文字列にしている。本ライブラリはこれを数値で出す。
// =====================================================================================================

/**
 * Protobuf メッセージを JSON に変換するライブラリ。int64 系を JSON の数値で出力する。
 *
 * <h2>背景</h2>
 * Protobuf 標準の JSON マッピング({@code com.google.protobuf.util.JsonFormat})は、
 * int64 / sint64 / sfixed64 を {@code "123"} のような文字列で出力する(JavaScript の Number で精度が落ちるため)。
 * OpenAPI 側の定義が {@code type: integer} の場合は数値で出す必要があるため、本ライブラリはそれらを
 * {@code 123} のような JSON の数値で出力する。それ以外の出力規則は JsonFormat の既定
 * ({@code JsonFormat.printer().omittingInsignificantWhitespace()})と同じにしている。
 *
 * <h2>使い方</h2>
 * <pre>{@code
 * // スレッドセーフなので 1 つ作って使い回す
 * ProtoJsonPrinter printer = ProtoJsonPrinter.create();
 *
 * String json = printer.print(message);              // 1 メッセージ → JSON 文字列(常に 1 行)
 * printer.writeTo(message, outputStream);            // UTF-8 で直接書き出す(文字列を作らない)
 *
 * // log を 1 レコードずつ JSON にする(DB に保存する等)
 * for (ObjectLog log : simLog.getLogsList()) {
 *     repository.save(printer.print(log));
 * }
 * }</pre>
 *
 * NDJSON(1 行 1 レコード)の書き出しは本ライブラリには含めていない。利用例(src/examples の
 * {@code NdjsonWriter})を参照。
 *
 * <h2>出力規則(JsonFormat の既定と同じもの)</h2>
 * <ul>
 *   <li>キー名は json_name(既定は lowerCamelCase。例: {@code job_id} → {@code jobId})</li>
 *   <li>キーの順序はフィールド番号順</li>
 *   <li>presence を持たないフィールド(proto3 の通常のスカラー)はデフォルト値(0、空文字、false 等)ならキーごと省略。
 *       repeated は空なら省略。未設定の oneof / optional / message フィールドも省略</li>
 *   <li>oneof・optional・message はデフォルト値でも「設定されていれば」出力する(例: {@code "result":{}})</li>
 *   <li>enum は名前の文字列(proto3 で未知の値は数値)、bytes は Base64(パディングあり)</li>
 *   <li>uint32 / fixed32 は符号なしの数値、uint64 / fixed64 は符号なし 10 進の<b>文字列</b>(下記)</li>
 *   <li>float / double は数値。NaN / Infinity / -Infinity は文字列</li>
 *   <li>Well-Known Types(google.protobuf.*)は JsonFormat と同じ表現(Timestamp は RFC 3339 文字列等)</li>
 * </ul>
 *
 * <h2>JsonFormat と異なる点</h2>
 * <ul>
 *   <li><b>int64 / sint64 / sfixed64 を JSON の数値で出力する</b>(本ライブラリの目的)</li>
 *   <li>文字列のエスケープは Jackson の規則に従う。JsonFormat は {@code < > & = '} を Unicode エスケープ
 *       (バックスラッシュ + u003c 等)で出力するが、本ライブラリはそのまま出力する(JSON の値としては同一)</li>
 *   <li>空白・改行を含む整形出力、{@code preservingProtoFieldNames} 等の JsonFormat のオプションは無い</li>
 * </ul>
 *
 * <h2>対象外・制約</h2>
 * <ul>
 *   <li>uint64 / fixed64 は文字列のまま(2^63 以上の値を受け側の 64bit 符号付き整数で扱えないため)</li>
 *   <li>Well-Known Types の中身は JsonFormat の表現のまま。{@code Int64Value} 等のラッパー型は文字列、
 *       {@code Any} に詰めたメッセージ内の int64 も文字列になる</li>
 *   <li><b>未対応</b>(該当する型を出力しようとすると {@link java.lang.UnsupportedOperationException}):
 *       map フィールド、group(proto2 の group / editions の DELIMITED)、extension を持てる message(proto2)。
 *       値の有無にかかわらず、その型(ネストした型を含む)を最初に出力しようとした時点で例外になる</li>
 *   <li>proto3 を対象として検証している。proto2 は通常のメッセージ(optional / required)の出力だけ確認している</li>
 *   <li>受け取る側が JSON の数値を double でパースすると(JavaScript の {@code JSON.parse} 等)、
 *       2^53 を超える int64 は丸められる。受け側が 64bit 整数として扱えることを確認すること</li>
 * </ul>
 *
 * <h2>例外</h2>
 * <ul>
 *   <li>{@link java.lang.NullPointerException}: 引数が null</li>
 *   <li>{@link java.lang.UnsupportedOperationException}: 未対応の構造(map / group / extension)を含む型</li>
 *   <li>{@link java.lang.IllegalArgumentException}: 値が JSON にできない。範囲外の Timestamp / Duration、
 *       TypeRegistry に登録していない型を詰めた Any、入れ子が深すぎるメッセージ(Jackson の上限 1000 段を超える)</li>
 *   <li>{@link java.io.IOException}: 書き出し先(ファイル・通信など)への書き込みの失敗だけ。
 *       {@code print} は文字列に書くので発生しない</li>
 * </ul>
 * 例外が起きた場合、それまでに書いた部分は出力先に残る(途中までの不完全な JSON)。
 * 本ライブラリが作る JsonGenerator では閉じ括弧を自動で補わないので、途中までの出力が正しい JSON に見えることはない。
 * 受け取った側に不完全なデータを使わせないよう、例外が起きたら出力を破棄すること(HTTP なら応答をエラーにする等)。
 *
 * <h2>安全性・リソース</h2>
 * <ul>
 *   <li>入力は、アプリケーションが既に持っている Protobuf のメッセージだけ。外部から来た JSON を読み込むことは無い
 *       (Any / Struct 等の出力で、JsonFormat が作った JSON を読み直すだけ)</li>
 *   <li>文字列は Jackson がエスケープして書くので、値やキーに引用符・改行・制御文字があっても JSON の構造は壊れない</li>
 *   <li>出力の大きさは入力の大きさにほぼ比例する(Base64 で約 4/3 倍、制御文字のエスケープで最大 6 倍)</li>
 *   <li>入れ子の深さは Jackson の上限(1000 段)で止まるので、スタックオーバーフローにはならない。
 *       gRPC で受信したメッセージは、Protobuf の制限で入れ子が 100 段までになっている</li>
 *   <li>型ごとの書き出し手順のキャッシュは {@link io.github.ramsesyok.protojson.ProtoJsonPrinter} のインスタンスごとに持ち、
 *       型の数に上限(1 万)がある。static なキャッシュは持たないので、アプリの再デプロイ時にも printer と一緒に解放される</li>
 *   <li>{@link io.github.ramsesyok.protojson.ProtoJsonPrinter} は不変でスレッドセーフ。ファイルやスレッドなど、
 *       閉じる必要のある資源は持たない</li>
 * </ul>
 *
 * <h2>クラス構成</h2>
 * <ul>
 *   <li>{@link io.github.ramsesyok.protojson.ProtoJsonPrinter}: 利用者向けの入口。message を JSON に書き出す</li>
 *   <li>{@code MessageLayout}(内部): message 型ごとの出力手順(フィールド順・JSON キー名)のキャッシュと、未対応の型の検出</li>
 *   <li>{@code ScalarValues}(内部): スカラー値(数値・文字列・enum・bytes 等)の書き出し規則</li>
 *   <li>{@code WellKnownTypes}(内部): Timestamp / Duration / ラッパー型等の書き出し</li>
 * </ul>
 */
package io.github.ramsesyok.protojson;
