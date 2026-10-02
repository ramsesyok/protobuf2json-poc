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
 * String json = printer.print(message);              // 1 メッセージ → JSON 文字列
 * printer.writeTo(message, outputStream);            // UTF-8 で直接書き出す(推奨: 文字列を作らない)
 *
 * // NDJSON(JSON Lines): 1 メッセージ 1 行
 * try (NdjsonWriter w = printer.ndjsonWriter(outputStream)) {
 *     for (ObjectLog log : logs) {
 *         w.write(log);
 *     }
 * }
 * }</pre>
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
 *   <li>proto3 を対象として検証している。proto2 は未検証</li>
 *   <li>受け取る側が JSON の数値を double でパースすると(JavaScript の {@code JSON.parse} 等)、
 *       2^53 を超える int64 は丸められる。受け側が 64bit 整数として扱えることを確認すること</li>
 * </ul>
 *
 * <h2>クラス構成</h2>
 * <ul>
 *   <li>{@link io.github.ramsesyok.protojson.ProtoJsonPrinter}: 利用者向けの入口。message を JSON に書き出す</li>
 *   <li>{@link io.github.ramsesyok.protojson.NdjsonWriter}: 1 メッセージ 1 行の NDJSON を書き出す</li>
 *   <li>{@code MessageLayout}(内部): message 型ごとの出力手順(フィールド順・JSON キー名)のキャッシュと、未対応の型の検出</li>
 *   <li>{@code ScalarValues}(内部): スカラー値(数値・文字列・enum・bytes 等)の書き出し規則</li>
 *   <li>{@code WellKnownTypes}(内部): Timestamp / Duration / ラッパー型等の書き出し</li>
 * </ul>
 */
package io.github.ramsesyok.protojson;
