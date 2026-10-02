# proto-int64-demo: Protobuf の int64 を JSON の数値として出力する実証

Protobuf 標準の JSON マッピング(Java の `JsonFormat`)では、int64 が文字列(`"123"`)で出力されます。
OpenAPI 側の定義(`type: integer`)に合わせるため、**int64 を JSON の数値(`123`)で出力する変換**
(`Int64JsonConverter`)を実装し、Go の gRPC サーバから受信したデータで検証しました。

## 構成

```
proto-int64-demo/
  proto/simlog.proto        共有 proto(Java/Go 両方でコード生成)
  server-go/                テスト用 gRPC サーバ(Go)
    main.go                 GetSimLog / StreamObjectLogs、:50051
    data.go                 決定的なテストデータ生成(乱数なし)
    data_test.go            データ生成のテスト
    gen.sh                  protoc で Go コードを生成(生成済みの proto/*.pb.go はコミット済み)
  client-java/              gRPC クライアント + JSON 変換 + テスト(Maven)
    src/main/java/demo/json/Int64JsonConverter.java   変換器本体
    src/main/java/demo/app/SimClient.java             3 パターンの JSON をファイル出力
    src/main/java/demo/app/Benchmark.java             性能の簡易計測
    src/test/java/demo/json/*Test.java                JUnit 5 テスト
```

## 前提ツール(検証した環境)

| ツール | バージョン | 備考 |
|---|---|---|
| Java (OpenJDK) | 21.0.12.1 | `maven.compiler.release=17` でビルド(Java 17 以上で動作する想定。17 での実行は未確認) |
| Maven | 3.9.11 | |
| Go | 1.24.7(インストール済み) | grpc-go 1.84.0 が `go 1.25.0` を要求するため、Go の toolchain 機能で **go1.25.0 が自動ダウンロード**されて使われた |
| protoc(Go 用) | libprotoc 3.21.12(システム) | |
| protoc-gen-go / protoc-gen-go-grpc | v1.36.12 / 1.6.2 | `$(go env GOPATH)/bin`(PATH 外なので `gen.sh` で PATH に追加) |
| protoc(Java 用) | 4.36.2 | protobuf-maven-plugin が Maven Central から自動取得(システムの protoc は使わない) |
| protoc-gen-grpc-java | 1.84.0 | 同上 |

主なライブラリ: protobuf-java / protobuf-java-util 4.36.2、grpc-java 1.84.0、jackson-databind 2.22.3、JUnit 5.14.4、
grpc-go 1.84.0、google.golang.org/protobuf 1.36.12。

> grpc-protobuf 1.84.0 は protobuf-java 3.25.9 に依存しているため、`dependencyManagement` で 4.36.2 に揃えています。
> この組み合わせでビルド・テストが通ることは確認しましたが、公式な互換性表は確認していません。

ネットワーク: Maven Central と proxy.golang.org への到達が必要です(初回のみ)。

## ビルド・実行手順

```bash
# 1) Go サーバ(別ターミナル)
cd server-go
./gen.sh                 # proto を変更したときのみ。生成コードはコミット済み
go test ./...
go build -o bin/server . && ./bin/server        # :50051 で待ち受け(-port で変更可)
#   → 2026/10/02 22:29:08 SimService gRPC server listening on :50051

# 2) Java クライアント
cd client-java
mvn test                 # 単体テスト + 統合テスト(サーバ未起動なら統合テスト 4 件はスキップ)
                         # 接続先変更: mvn test -Dgrpc.target=host:port

# 3 パターンの JSON を output/ に書き出す(引数: target outDir objectCount eventsPerObject)
mvn -q compile exec:java -Dexec.mainClass=demo.app.SimClient -Dexec.args="localhost:50051 output 2 3"

# 性能計測(引数: target objectCount eventsPerObject warmup iterations)
MAVEN_OPTS="-Dstdout.encoding=UTF-8" \
  mvn -q compile exec:java -Dexec.mainClass=demo.app.Benchmark -Dexec.args="localhost:50051 1000 50 20 30"
```

ロケールが POSIX の環境では Java の標準出力で日本語が `?` になるため、`-Dstdout.encoding=UTF-8` を付けています
(ファイル出力は常に UTF-8)。

## 変換の仕組み(`Int64JsonConverter`)

1. `JsonFormat.printer().omittingInsignificantWhitespace()` で JSON 文字列化 → static final の Jackson `ObjectMapper` で `JsonNode` にパース
2. メッセージの `Descriptor` を再帰的にたどり、型が `INT64 / SINT64 / SFIXED64` のフィールドの値だけを `LongNode` に置き換える
   - キー名ではなく**型情報**で判定するので、`body = "12345"` のような数字だけの string は変換されない
   - 子 message、repeated(message / スカラー)、oneof 内の message、optional message に対応。キーが無ければ(未設定の oneof・optional、デフォルト値で省略)スキップ
   - JSON キーは `json_name`(lowerCamelCase)→ proto 名の順に探すので、`preservingProtoFieldNames()` 付きの Printer でも動く
3. `MessageOrBuilder` を受け取るので、`SimLog` 全体・`ObjectLog` 単体・Builder のいずれも変換可能

API:

| メソッド | 用途 |
|---|---|
| `toJson(msg)` | int64 を数値化した 1 行 JSON |
| `toJsonNode(msg)` | 同上の `JsonNode` |
| `toRawJson(msg)` | 比較用: 素の `JsonFormat` 出力(int64 は文字列) |
| `toNdjson(iterable)` / `writeNdjson(iterable, writer)` / `writeNdjsonLine(msg, writer)` | パターン3: 1 行 1 JSON + `\n` |
| `new Int64JsonConverter(printer)` | Printer を差し替え(`alwaysPrintFieldsWithNoPresence()` 等) |

**対象外**(変換せず JsonFormat の出力のまま残す。コードにもコメントで明記): uint64 / fixed64、
Well-Known Types(`Int64Value` 等のラッパー型、`Any`、`Timestamp` など `google.protobuf` パッケージの型)、map フィールド。

## Go サーバのテストデータ

`object_count` / `events_per_object` から決定的に生成します(乱数不使用)。

- int64 には `0, 1, -1, 42, 2^53+1 (9007199254740993), MaxInt64, MinInt64, -(2^53+1), 1234567890123` を巡回で混ぜる。`job_id = 2^53+1`
- oneof は Event 通し番号 g の `g % 3` で `comm_event` / `exec_event` / 未設定 を交互に
- string には `"12345"`, `"a\nb"`, `say "hi"`, `"日本語テキスト"`, `"-9223372036854775808"`, `"0"`, `<tag> & a=b 'q'`, `""`, `"plain"` を混ぜる
- `include_result=true` のとき `result = {exit_code: MinInt64, output: "9223372036854775807"}`

## 出力サンプル(`SimClient`、object_count=2, events_per_object=3、実際の出力)

**パターン1(result なし)** `output/pattern1.json`(実際は 1 行):

```json
{"jobId":9007199254740993,"logs":[{"timestamp":1700000000000,"events":[{"eventType":1,"commEvent":{"fromId":-1,"toId":42,"body":"12345"}},{"eventId":1,"eventType":-1,"execEvent":{"execId":9223372036854775807,"command":"a\nb","result":"say \"hi\""}},{"eventId":2,"eventType":42}]},{"timestamp":9223372036854775807,"objectId":1,"events":[{"eventId":3,"eventType":9007199254740993,"commEvent":{"fromId":42,"toId":9007199254740993,"body":"a\nb"}},{"eventId":4,"eventType":9223372036854775807,"execEvent":{"execId":-9223372036854775808,"command":"say \"hi\"","result":"日本語テキスト"}},{"eventId":5,"eventType":-9223372036854775808}]}]}
```

**パターン2(result あり)** `output/pattern2.json` — パターン1と同じ内容の末尾に `result` が付く:

```json
{"jobId":9007199254740993,"logs":[ …パターン1と同じ… ],"result":{"exitCode":-9223372036854775808,"output":"9223372036854775807"}}
```

**パターン3(NDJSON)** `output/pattern3-stream.ndjson`(StreamObjectLogs で受信するたびに 1 行書き出し):

```
{"timestamp":1700000000000,"events":[{"eventType":1,"commEvent":{"fromId":-1,"toId":42,"body":"12345"}},{"eventId":1,"eventType":-1,"execEvent":{"execId":9223372036854775807,"command":"a\nb","result":"say \"hi\""}},{"eventId":2,"eventType":42}]}
{"timestamp":9223372036854775807,"objectId":1,"events":[{"eventId":3,"eventType":9007199254740993,"commEvent":{"fromId":42,"toId":9007199254740993,"body":"a\nb"}},{"eventId":4,"eventType":9223372036854775807,"execEvent":{"execId":-9223372036854775808,"command":"say \"hi\"","result":"日本語テキスト"}},{"eventId":5,"eventType":-9223372036854775808}]}
```

比較用の素の JsonFormat 出力 `output/pattern3-stream.raw.ndjson`:

```
{"timestamp":"1700000000000","events":[{"eventType":"1","commEvent":{"fromId":"-1","toId":"42","body":"12345"}},{"eventId":"1","eventType":"-1","execEvent":{"execId":"9223372036854775807","command":"a\nb","result":"say \"hi\""}},{"eventId":"2","eventType":"42"}]}
…
```

- `pattern3-stream.ndjson`(streaming 受信)と `pattern3-logs.ndjson`(GetSimLog の `logs` 要素)はバイト単位で一致(`cmp` で確認)。
  1000×50 でも両方 1000 行。
- 1 件目の ObjectLog は `object_id = 0`、最初の Event は `event_id = 0` のため、**キー自体が出力されていない**(下記「仕様判断」参照)。

## テスト結果

`mvn clean test`(Go サーバ起動中): **30 件すべて成功**(Tests run: 30, Failures: 0, Errors: 0, Skipped: 0)。
サーバ停止中に実行すると統合テスト 4 件はスキップされ、残り 26 件が成功(Skipped: 4)することも確認。
Go 側 `go test ./...`: 2 件成功。

| 結果 | テストクラス / グループ | テスト | 内容 |
|---|---|---|---|
| PASS | 正しさ | allInt64FieldsAreNumbers | 9 種の int64(jobId, timestamp, objectId, eventId, eventType, exitCode, fromId, toId, execId)が数値で値一致、ツリー全体で 19 個 |
| PASS | 正しさ | numericLookingStringsStayStrings | `body="12345"`, `command="-9223372036854775808"`, `result="9007199254740993"`, `output="9223372036854775807"` が文字列のまま |
| PASS | 正しさ | oneofAllCases | comm_event / exec_event / 未設定 の 3 通り |
| PASS | 正しさ | optionalResultPresence | result あり→キーあり、なし→キー無し |
| PASS | 正しさ | matchesHandWrittenExpectedJson | 手書きの期待 JSON と**文字列として完全一致** |
| PASS | 正しさ | sameAsRawExceptInt64Quotes | 素の出力と JsonNode を並行走査し、差分は int64 の `"…"`→数値 のみ(キー順も一致) |
| PASS | 正しさ | sameAsRawStringExceptInt64Quotes | 素の出力文字列から int64 の引用符を正規表現で外したものと完全一致(`<>&='` を含まないデータ) |
| PASS | 正しさ | htmlSensitiveCharsAreEscapedDifferently | `<>&='` のエスケープ差(下記「問題点」)を明示的に確認 |
| PASS | 正しさ | roundTripWithJsonFormatParser | 変換後 JSON を `JsonFormat.parser()` で読むと元のメッセージと equals |
| PASS | 大きな値 | longValuesRoundTrip ×8 | 2^53+1, MAX, MIN, -1, -(2^53+1), 1, 2^31, -(2^31)-1 が long として一致 |
| PASS | 大きな値 | doubleLosesPrecision | 参考: 2^53+1 を double で読むと 9007199254740992 になる |
| PASS | 大きな値 | zeroIsOmittedByDefault | 0 はキーごと省略 |
| PASS | 大きな値 | zeroIsPrintedWithAlwaysPrint | `alwaysPrintFieldsWithNoPresence()` なら 0 も数値 `0` で出力 |
| PASS | NDJSON | oneJsonPerLine | 1 行 1 JSON・各行単独でパース可・行数一致・`"a\nb ..."` を含む body も 1 行 |
| PASS | NDJSON | writerAndStringAgree / empty | Writer 版と String 版の一致、0 件で空文字列 |
| PASS | 汎用性 | acceptsAnyMessageOrBuilder | ObjectLog 単体・Builder・ExecEvent 単体 |
| PASS | 汎用性 | protoFieldNames | `preservingProtoFieldNames()` の snake_case キーでも数値化 |
| PASS | FieldTypeCoverageTest | fieldTypes | DynamicMessage で repeated int64 / sint64 / sfixed64 は数値化、uint64 / fixed64 / Int64Value / map は文字列のまま |
| PASS | Go サーバとの統合テスト | pattern1 | 12×9 件、全 int64 数値・素の出力との差分 int64 のみ・0 値キー無し・round trip |
| PASS | Go サーバとの統合テスト | pattern2 | `result.exitCode = MinInt64`(数値)、`result.output` は文字列 |
| PASS | Go サーバとの統合テスト | pattern3 | streaming 12 件 → 12 行、各行パース可、`a\nb` を含む、unary の logs から作った NDJSON と一致 |
| PASS | Go サーバとの統合テスト | specialStrings | 数字のみ・改行・ダブルクォート・日本語・`<>&='` の文字列が保持される |

**テスト自体の検出力の確認**(ミューテーション): 変換器を一時的に壊して実行し、元に戻した。

- 子 message(oneof 内の `commEvent` / `execEvent`、`result`)に再帰しないようにする → 30 件中 13 件失敗
- 「数字だけの string も数値化する」素朴な実装を混ぜる → 30 件中 13 件失敗

**開発中に検出して修正した不具合**(いずれもテストで発見):

1. 変換器: WKT(`Int64Value` 等)のフィールドを `ObjectNode` にキャストして `ClassCastException`(WKT は JSON 上オブジェクトとは限らない)。WKT 判定をキャスト前に移動して修正。
2. Go のデータ生成: 特殊値リストの長さ 9 が oneof の周期 3 の倍数だったため、`body` などに 9 種中 3 種しか現れず `"a\nb"` が生成されていなかった。payload の値を `g/3` で選ぶよう修正し、Go のテストで全種類の出現を検証するようにした。

## 性能計測(参考値)

条件: object_count=1000, events_per_object=50(Event 50,000 件、protobuf 1,589,295 bytes)。
gRPC で 1 回受信したメッセージをメモリ上で変換(通信時間は含まない)。ウォームアップ 20 回後、30 回の平均と最小。
`System.nanoTime` による簡易計測。Intel Xeon 2.10GHz × 4 vCPU のクラウドコンテナ、Java 21、JVM オプション既定(ヒープ既定)。

3 回実行した結果:

| ケース | 平均 [ms] run1 / run2 / run3 | 最小 [ms] run1 / run2 / run3 | 出力サイズ [bytes] |
|---|---|---|---:|
| P1 素の JsonFormat のみ | 54.4 / 57.6 / 75.1 | 49.9 / 53.7 / 55.4 | 4,820,527 |
| P1 Int64JsonConverter(2パス) | 116.0 / 132.4 / 180.3 | 104.4 / 108.5 / 110.6 | 4,372,513 |
| P3 素の JsonFormat のみ(1000 行) | 59.9 / 62.1 / 63.5 | 55.7 / 58.1 / 60.6 | 4,820,490 |
| P3 Int64JsonConverter(2パス、1000 行) | 113.1 / 109.4 / 123.8 | 106.0 / 105.8 / 111.2 | 4,372,478 |

P1 の内訳(各段階を単独計測、run1 / run2 / run3 の平均 [ms]):

| 段階 | 平均 [ms] |
|---|---|
| `JsonFormat.print` | 62.1 / 54.8 / 56.5 |
| Jackson `readTree`(4.8MB) | 25.6 / 20.6 / 19.9 |
| `toJsonNode`(print + readTree + Descriptor 走査・置換) | 97.3 / 100.0 / 98.8 |
| Jackson `writeValueAsString` | 27.0 / 26.1 / 27.7 |

所見:

- 2 パス変換は素の JsonFormat の **約 2 倍**(最小値で P1: 約 50–55ms → 約 105–110ms)。追加コストは「Jackson パース 約 20ms + 走査・置換 約 15–20ms + Jackson 書き出し 約 25ms」。
- P1(全体を 1 JSON)と P3(1000 行に分割)で総コストはほぼ同じ。P3 は 1 行ずつ処理できるので、ピークメモリは小さくできる(未計測)。
- 平均が最小より大きく振れる回(run3 の P1 180ms 等)があり、GC の影響と推測(GC ログは未取得)。
- 出力サイズは変換後のほうが 448,014 bytes 小さい。内訳は **int64 の引用符 2 bytes × 140,667 個 = 281,334** と、
  **`<` 等のエスケープが生文字になった分 5 bytes × 33,336 個 = 166,680** で、差分と完全に一致することを確認した。

## 仕様判断(実際の出力に基づく)

| 項目 | 挙動 | 根拠 |
|---|---|---|
| フィールド名 | `job_id` → `jobId` の lowerCamelCase(JsonFormat 既定) | 出力サンプル。`preservingProtoFieldNames()` 付き Printer を渡せば snake_case になり、変換器もそのまま動く(テスト protoFieldNames) |
| 値 0(proto3 の暗黙 presence) | **キーごと省略**。int64 の 0、空文字列も同様 | `{"events":[{"commEvent":{}}]}`(テスト zeroIsOmittedByDefault)、Go サーバ由来の `object_id=0` / `event_id=0` もキー無し |
| 0 を出したい場合 | `JsonFormat.printer().alwaysPrintFieldsWithNoPresence()` を渡すと `"timestamp":0` のように数値で出る。空の repeated は `[]` | テスト zeroIsPrintedWithAlwaysPrint |
| optional `result` | 未設定ならキー無し(alwaysPrint でも出ない)。デフォルト値のまま**設定**すると `"result":{}` が出る | 同上 |
| oneof | 設定されている側のキーだけが出る。未設定ならどちらのキーも無い。中身が全部デフォルトでも `"commEvent":{}` は出る | テスト oneofAllCases、zeroIsOmittedByDefault |
| キー順 | JsonFormat の順序(フィールド番号順)が保持される | テスト sameAsRawExceptInt64Quotes |

OpenAPI 側で 0 のフィールドが `required` になっている場合は、`alwaysPrintFieldsWithNoPresence()` を使うかどうか決める必要があります。

## 発見した問題点・制約・未対応事項

1. **文字列エスケープがバイト単位では JsonFormat と異なる**: JsonFormat は `< > & = '` を `< > & = '` にエスケープするが、
   Jackson で再出力すると生文字になる。JSON としての値は同一(パース結果は一致)だが、バイト単位の比較や署名をする場合は注意。
   日本語はどちらも UTF-8 の生文字、改行は `\n`、ダブルクォートは `\"` で同じ。
2. **受信側の精度**: 出力は正しい 64bit 整数だが、利用側が double でパースする(JavaScript の `JSON.parse`、Jackson で `double` として読む等)と
   2^53 を超える値は丸められる(2^53+1 → 9007199254740992)。Protobuf が int64 を文字列にしているのはこのため。
   他社側のパーサが 64bit 整数を扱えることは別途確認が必要(本検証の範囲外)。
3. **Jackson のノード型**: 変換器が作るノードは `LongNode` だが、出力を再パースすると Jackson は int に収まる値を `IntNode` にする
   (`isLong()` は false、`isIntegralNumber()` / `canConvertToLong()` は true)。また `LongNode(1).equals(IntNode(1))` は false。
   テストは `isIntegralNumber()` + `longValue()` で判定している。
4. **性能**: 2 パスのため素の JsonFormat の約 2 倍の時間、かつ全体の JsonNode ツリーをメモリに持つ。
   改善案(未実装・未検証): Jackson のストリーミング API(`JsonParser` → `JsonGenerator`)で Descriptor を追いながら変換してツリーを作らない、
   または Descriptor を使って直接 JSON を書く自前 Printer。
5. **対象外の型**: uint64 / fixed64、Well-Known Types(`Int64Value`、`Any`、`Timestamp` 等)、map は文字列のまま(DynamicMessage のテストで挙動を確認)。
   特に `Any` の中身(packed message)の int64 は数値化されない。必要なら TypeRegistry を使って型を解決する拡張が要る。
6. **proto の変更への追従**: 変換は Descriptor ベースなので、フィールド追加には自動で追従する。ただし今回のテストのキー名リスト
   (`JsonAssertions.INT64_KEYS`)は手書きなので、proto を変えたらテスト側の更新が必要。
7. **依存バージョン**: grpc-java 1.84.0 は protobuf-java 3.25.x 依存だが 4.36.2 に上書きしている(動作は確認、公式互換性は未確認)。
   Go は toolchain の自動ダウンロード(go1.25.0)に依存している。

## 確認できたこと / 確認できていないこと

**実際に実行して確認できたこと**

- Go サーバのビルド・起動(`:50051`、起動ログ出力)・`go test`(2 件成功)
- Java のビルド(protoc 4.36.2 / protoc-gen-grpc-java 1.84.0 によるコード生成を含む)
- Java クライアントから Go サーバへの GetSimLog / StreamObjectLogs 呼び出しと、3 パターンの JSON / NDJSON 出力
- JUnit 5 テスト 30 件すべて成功(サーバ起動時)。サーバ停止時は統合テスト 4 件がスキップされ 26 件成功
- テストが誤実装を検出できること(ミューテーション 2 種でそれぞれ 13 件失敗)
- 1000×50(Event 5 万件)での変換時間・出力サイズの計測(3 回)と、サイズ差の内訳
- 上記「仕様判断」「問題点」の 1〜3、5 の挙動(テストで確認)

**確認できていないこと**

- Java 17 での実行(ビルドは `release 17` 指定だが、実行は Java 21 のみ)
- Windows / macOS など他 OS での動作
- 他社側のクライアント(OpenAPI 生成コード等)で、出力 JSON がどう解釈されるか
- メモリ使用量(ピークヒープ)と GC の影響の計測
- 改善案(ストリーミング変換等)の効果
- 1000×50 を超える大規模データ、並行実行時の性能
- grpc-java と protobuf-java 4.x の組み合わせの公式サポート状況
