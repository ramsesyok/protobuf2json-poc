# protobuf-int64-json

Protobuf メッセージを JSON に変換する Java ライブラリです。**int64 / sint64 / sfixed64 を JSON の数値で出力します**。

Protobuf 標準の JSON マッピング(`JsonFormat`)は int64 を `"123"` のような文字列で出力するため、
OpenAPI で `type: integer` と定義された API にそのまま使えません。本ライブラリは int64 系だけを数値にし、
それ以外の出力規則は `JsonFormat.printer().omittingInsignificantWhitespace()` と同じにしています。

検証用 PoC(`proto-int64-demo/`)で比較した 3 方式のうち、最速だった「B: リフレクションから Jackson へ直接書く 1 パス方式」を
製品として使える形に整えたものです。経緯と比較結果は `proto-int64-demo/README.md` を参照してください。

```
src/
  java/                 ライブラリ本体とテスト(Maven、Java 17 以上)
    src/main/java/io/github/ramsesyok/protojson/
      ProtoJsonPrinter.java   利用者向けの入口(JSON 文字列 / OutputStream / Writer / JsonGenerator へ出力)
      MessageLayout.java      (内部)message 型ごとの出力手順のキャッシュ、未対応の型の検出
      ScalarValues.java       (内部)スカラー値の書き出し規則 ★int64 を数値にしている箇所
      WellKnownTypes.java     (内部)Timestamp / Duration / ラッパー型 / Any 等の書き出し
      package-info.java       仕様の説明(出力規則・JsonFormat との違い・制約)
    src/test/...            テスト(下記)
  examples/             利用例(ライブラリを使うアプリの立場で書いた別の Maven プロジェクト。下記「利用例」)
                        NDJSON の書き出し(NdjsonWriter)もここにある(ライブラリには含めていない)
  go/                   動作テスト用の gRPC サーバ(Go、厳密な検証はしていない)
  proto/simlog.proto    動作テスト用の proto(Go サーバ・Java の統合テスト・利用例で共有)
```

## 使い方

```java
// 不変・スレッドセーフ。アプリケーションで 1 つ作って使い回す
ProtoJsonPrinter printer = ProtoJsonPrinter.create();

// 1 メッセージ → JSON(結果は常に 1 行。文字列中の改行はエスケープされる)
String json = printer.print(simLog);
printer.writeTo(simLog, response.getOutputStream());   // UTF-8 で直接書く(文字列を作らないので速い)

// log を 1 レコードずつ JSON にする(DB に保存する等)
for (ObjectLog log : simLog.getLogsList()) {
    repository.save(printer.print(log));
}

// 他の JSON に埋め込む
generator.writeFieldName("data");
printer.writeTo(simLog, generator);

// google.protobuf.Any を使う場合は、詰める型を登録する
ProtoJsonPrinter withAny = ProtoJsonPrinter.builder()
        .typeRegistry(JsonFormat.TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build())
        .build();
```

本ライブラリの責務は「1 メッセージ → JSON」だけです。NDJSON(1 行 1 レコードのファイル・ストリーム)が必要な場合は、
`examples/` の `NdjsonWriter`(公開 API の `writeTo(message, JsonGenerator)` だけで作った 70 行ほどのクラス)を
コピーして使ってください。

出力例(`proto/simlog.proto` の SimLog):

```json
{"jobId":9007199254740993,"logs":[{"timestamp":1700000000000,"events":[{"eventType":1,"commEvent":{"fromId":-1,"toId":42,"body":"12345"}}]}],"result":{"exitCode":-9223372036854775808,"output":"9223372036854775807"}}
```

## 利用例(`examples/`): log を 1 レコードずつ JSON にする

`examples/` は本ライブラリを通常の依存として使う、独立した Maven プロジェクトです。Go の動作テスト用サーバから
SimLog を受信し、`logs` の各レコード(ObjectLog)を 1 件ずつ JSON にします。

| クラス | 内容 | 向いている用途 |
|---|---|---|
| `PerRecordJsonExample` | `GetSimLog`(unary)で SimLog 全体を受信し、`logs` の各レコードを `printer.print(log)` で 1 件ずつ JSON 文字列にする | レコードごとに保存・送信する(DB の 1 行、メッセージキューの 1 メッセージ等) |
| `StreamingNdjsonExample` | `StreamObjectLogs`(server streaming)で 1 レコードずつ受信し、受信するたびに `NdjsonWriter` で NDJSON の 1 行として書き出す | 件数が多い、HTTP のストリーミング応答、ファイル出力(全件をメモリに溜めない) |
| `OutputSamplesExample` | 出力サンプルを作る: SimLog 全体(result あり / なし)と ObjectLog 1 レコードの JSON を、実際の出力と整形版の両方で `samples/` に書き出す | 出力形式の確認(**結果は `examples/samples/` にコミット済み**) |
| `NdjsonWriter` | NDJSON(1 メッセージ 1 行、行末 `\n`)の書き出し。ライブラリの公開 API `writeTo(message, JsonGenerator)` だけで作っている | NDJSON が必要な場合にコピーして使う(ライブラリ側はこのクラスに依存しない) |

### 出力サンプル(`examples/samples/`)

| ファイル | 内容 |
|---|---|
| [`simlog-with-result.json`](examples/samples/simlog-with-result.json) / [`.pretty.json`](examples/samples/simlog-with-result.pretty.json) | SimLog 全体(result あり) |
| [`simlog-without-result.json`](examples/samples/simlog-without-result.json) / [`.pretty.json`](examples/samples/simlog-without-result.pretty.json) | SimLog 全体(result なし。`result` キー自体が無い) |
| [`objectlog-record.json`](examples/samples/objectlog-record.json) / [`.pretty.json`](examples/samples/objectlog-record.pretty.json) | ObjectLog の 1 レコードだけ |

`.json` は実際の出力そのもの(1 行)、`.pretty.json` は読みやすく整形したもの(値は同じ)。見どころは
[`examples/samples/README.md`](examples/samples/README.md) を参照。例えば ObjectLog 1 レコードの出力は次のとおり:

```json
{"timestamp":1700000000001,"objectId":1,"events":[{"eventId":3,"eventType":9007199254740993,"commEvent":{"fromId":42,"toId":9007199254740993,"body":"a\nb"}},{"eventId":4,"eventType":9223372036854775807,"execEvent":{"execId":-9223372036854775808,"command":"say \"hi\"","result":"日本語テキスト"}},{"eventId":5,"eventType":-9223372036854775808}]}
```

要点だけ抜き出すと次のとおりです。

```java
ProtoJsonPrinter printer = ProtoJsonPrinter.create();   // 1 つ作って使い回す

// 例 1: 受信済みの SimLog から 1 レコードずつ
for (ObjectLog log : simLog.getLogsList()) {
    String json = printer.print(log);                    // 1 レコード → 1 つの JSON(改行なし)
    save(json);
}

// 例 2: server streaming で受信するたびに 1 行
try (NdjsonWriter writer = new NdjsonWriter(printer, out)) {  // examples の NdjsonWriter。close で out も閉じる
    Iterator<ObjectLog> it = stub.streamObjectLogs(request);
    while (it.hasNext()) {
        writer.write(it.next());                         // 1 レコード → 1 行(末尾 \n)
        writer.flush();                                  // 逐次届けたい場合
    }
}
```

実行方法(Go サーバを起動しておく):

```bash
mvn -f src/java install -DskipTests          # ライブラリをローカルリポジトリへ(初回・ライブラリ変更時)
cd src/examples
mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.PerRecordJsonExample \
    -Dexec.args="localhost:50051 3 3"         # 接続先 ObjectLog件数 1件あたりのEvent数
mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.StreamingNdjsonExample \
    -Dexec.args="localhost:50051 3 3 -"       # 最後の引数は出力先ファイル("-" は標準出力)
mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.OutputSamplesExample \
    -Dexec.args="localhost:50051 samples"     # 出力サンプルの作り直し
mvn test                                     # 例の処理・NdjsonWriter・samples/ が最新であることの確認(サーバが無ければ 3 件スキップ)
```

`PerRecordJsonExample` の実際の出力(先頭 2 件):

```
record[0] {"timestamp":1700000000000,"events":[{"eventType":1,"commEvent":{"fromId":-1,"toId":42,"body":"12345"}},{"eventId":1,"eventType":-1,"execEvent":{"execId":9223372036854775807,"command":"a\nb","result":"say \"hi\""}},{"eventId":2,"eventType":42}]}
record[1] {"timestamp":1700000000001,"objectId":1,"events":[{"eventId":3,"eventType":9007199254740993,"commEvent":{"fromId":42,"toId":9007199254740993,"body":"a\nb"}},{"eventId":4,"eventType":9223372036854775807,"execEvent":{"execId":-9223372036854775808,"command":"say \"hi\"","result":"日本語テキスト"}},{"eventId":5,"eventType":-9223372036854775808}]}
```

`record[0]` に `objectId` と最初の Event の `eventId` が無いのは、値が 0 のため(デフォルト値はキーごと省略する仕様)。
`StreamingNdjsonExample` は同じ内容を `record[n]` 無しで 1 行ずつ出力します(テストで 2 つの例の出力が一致することを確認)。

## 出力規則

JsonFormat の既定と同じ(int64 系の数値化を除く)。詳細は `package-info.java` に書いています。

| 項目 | 出力 |
|---|---|
| **int64 / sint64 / sfixed64** | **JSON の数値**(例: `9223372036854775807`) |
| uint64 / fixed64 | 符号なし 10 進の文字列(対象外。JsonFormat と同じ) |
| int32 系 / uint32 系 | 数値(uint32 / fixed32 は符号なし) |
| float / double | 数値。NaN / Infinity / -Infinity は文字列 |
| bool / string | true・false / 文字列 |
| bytes | 標準 Base64(パディングあり) |
| enum | 名前の文字列。proto3 で未知の値は数値 |
| キー名 | json_name(既定は lowerCamelCase、`json_name` 指定があればそれ) |
| キーの順序 | フィールド番号順 |
| デフォルト値 | presence の無いフィールド(proto3 の通常のスカラー)は 0 / 空文字 / false / 先頭の enum ならキーごと省略。空の repeated も省略 |
| optional / oneof / message | 設定されていればデフォルト値でも出力(例: `"result":{}`、`"cInt64":0`)。未設定なら省略 |
| Well-Known Types | JsonFormat と同じ表現(Timestamp は `"2023-11-14T22:13:20.123Z"`、Duration は `"1.500s"` 等)。`Int64Value` は文字列のまま |

**JsonFormat と異なる点**: int64 系が数値であることのほかに、文字列のエスケープが Jackson の規則であること
(JsonFormat は `< > & = '` を Unicode エスケープするが、本ライブラリはそのまま書く。JSON の値としては同一)。
JsonFormat のオプション(整形出力、`preservingProtoFieldNames`、`alwaysPrintFieldsWithNoPresence` 等)はありません。

## 制約・未対応

- **未対応**: map フィールド、group(proto2 / editions の DELIMITED)、extension を持てる message(proto2)。
  該当する型(ネストした先にある場合を含む)を出力しようとすると、**値の有無にかかわらず** `UnsupportedOperationException`。
  特定のデータの時だけ失敗するのではなく、その型を初めて出力する時点で必ず失敗するので、テストで検出できます。
- Well-Known Types の中身は JsonFormat の表現のまま。`Any` に詰めたメッセージ内の int64 も文字列になります。
- proto3 を対象に検証しています。proto2 は「通常のメッセージ(optional / required)が JsonFormat と同じ出力になる」ことだけ確認しています。
- 受け側が JSON の数値を double でパースすると(JavaScript の `JSON.parse` 等)、2^53 を超える値は丸められます。
- Java のコードで作った**単独サロゲート**(壊れた文字列)を含む string は、`print` / Writer では生の文字、
  OutputStream(UTF-8)では `\uD800` の形のエスケープで出力されます。gRPC で受信した文字列には含まれません
  (Protobuf のデコード時に `?` に置き換わるため)。
- 例外が起きた場合、それまでに書いた不完全な JSON が出力先に残ることがあります(閉じ括弧は補わないので、
  正しい JSON には見えません)。例外が起きたら出力を破棄してください(HTTP なら応答をエラーにする等)。
- 入れ子が 1000 段を超えるメッセージは出力できません(`IllegalArgumentException`。Jackson の上限)。
  gRPC で受信したメッセージは Protobuf の制限で 100 段までなので、通常は該当しません。
- 型ごとの書き出し手順は `ProtoJsonPrinter` のインスタンスごとにキャッシュします(上限 1 万型)。
  アプリで 1 つ作って使い回してください(リクエストごとに作ると、毎回キャッシュが空から始まり遅くなります)。

## 製品組み込みの点検結果

脆弱性・メモリリーク・例外処理などの観点で点検し、見つかった問題は修正してテストを追加しました(`RobustnessTest`)。

| 観点 | 点検内容 | 結果 |
|---|---|---|
| 入力の安全性 | 外部から来たデータを解釈するか | 入力は既に Protobuf のメッセージになっているデータだけ。外部の JSON は読まない(Any / Struct 等で JsonFormat が作った JSON を読み直すだけ) |
| 出力の安全性 | 値やキーに引用符・改行・制御文字があっても JSON が壊れないか | Jackson がエスケープするので壊れない(乱数で作った 1 万件超のメッセージで確認済み) |
| 資源の使いすぎ | 入力に対して出力や処理が極端に大きくならないか | 出力は入力にほぼ比例(Base64 で約 4/3 倍、制御文字のエスケープで最大 6 倍)。ループや再帰は入力の大きさの範囲で終わる |
| スタックオーバーフロー | 極端に深い入れ子 | Jackson の上限(1000 段)で止まり、`IllegalArgumentException` になる。**修正前は I/O の失敗と同じ `IOException`(`print` では `UncheckedIOException`)になっていた → 修正** |
| 途中の失敗 | 失敗時に出力先に残るデータ | **修正前は Jackson が閉じ括弧を自動で補い、途中までのデータが正しい JSON に見える場合があった**(例: `{"rTimestamp":["1970-01-01T00:00:00Z"]}`)**→ 補わない設定に修正** |
| データ依存の失敗 | 特定のデータのときだけ失敗しないか | **修正前は Struct / Any 等の中に 2000 万文字超の文字列や 5 万文字超のキーがあると失敗していた**(Jackson の読み込み上限)**→ 自分で作った JSON を読むときは上限を外すよう修正**。map 等の未対応の構造は、データではなく型の時点で必ず例外になる |
| メモリリーク | キャッシュが増え続けないか、解放されるか | **修正前は static なキャッシュで上限も削除も無く、再デプロイ時のクラスローダリークや、実行時に型を作り続ける用途で増え続ける恐れがあった → printer ごとのキャッシュ + 上限 1 万型に修正**。printer を手放すと型の情報も GC で回収されることをテストで確認 |
| 資源の閉じ忘れ | ファイル・スレッド等を持つか、出力先を勝手に閉じないか | 閉じる必要のある資源は持たない。内部の JsonGenerator / JsonParser は try-with-resources で必ず閉じる。利用者の出力先は閉じない(テストで確認) |
| スレッドセーフ | 複数スレッドから同時に使えるか | 不変オブジェクト + ConcurrentHashMap。8 スレッドがそれぞれ 4,000 回ずつ同時に実行しても結果が一致することをテストで確認 |
| 例外の種類 | 原因ごとに区別できるか | null → `NullPointerException`(引数名付き)、未対応の型 → `UnsupportedOperationException`、JSON にできない値 → `IllegalArgumentException`、書き込みの失敗 → `IOException` のみ。すべてテストで確認 |
| 依存ライブラリ | 既知の脆弱性 | 実行時の依存は protobuf-java 4.36.2 / protobuf-java-util 4.36.2(推移的に gson 2.8.9、jsr305、error_prone_annotations)/ jackson-core 2.22.3。把握している範囲では、いずれも既知の脆弱性の修正版以降(protobuf-java の CVE-2024-7254、gson の CVE-2022-25647)。**この環境からは脆弱性データベース(OSV)に接続できず、照合はしていない**ので、組み込み先の CI で OWASP Dependency-Check / Dependabot 等による確認を推奨 |

## 依存ライブラリ

| ライブラリ | バージョン(確認したもの) | 用途 |
|---|---|---|
| com.google.protobuf:protobuf-java | 4.36.2 | リフレクション API |
| com.google.protobuf:protobuf-java-util | 4.36.2 | JsonFormat(Any / Struct 等)、Timestamps / Durations |
| com.fasterxml.jackson.core:jackson-core | 2.22.3(**2.18.0 以上が必要**) | JsonGenerator / JsonParser。databind は不要 |

gRPC と jackson-databind はテストでだけ使います。

## ビルドとテスト

```bash
# 動作テスト用サーバ(任意。起動していないと gRPC 統合テスト 2 件はスキップされる)
cd src/go
./gen.sh                         # proto を変更したときのみ(生成コードはコミット済み)
go build -o bin/server . && ./bin/server          # :50051

# ライブラリのテスト
cd src/java
mvn test                         # 接続先の変更: -Dgrpc.target=host:port

# 簡易ベンチマーク(手動実行)
MAVEN_OPTS="-Dstdout.encoding=UTF-8" mvn -q test-compile exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=io.github.ramsesyok.protojson.PrinterBenchmark -Dexec.args="1000 50"
```

## テスト

| テストクラス | 件数 | 内容 |
|---|---:|---|
| `ProtoJsonPrinterTest` | 34 | 振る舞いの仕様。期待値を JSON 文字列で書いているので、出力規則の具体例として読める。int64 系の境界値、数字だけの string、presence(デフォルト値・optional・oneof・空 message)、キー名と順序、enum(未知の値)・bytes・float / double の特殊値・エスケープ、全 WKT、Any と TypeRegistry、範囲外の Timestamp / Duration、未対応の構造の例外、OutputStream / Writer / JsonGenerator への出力、Builder / DynamicMessage、8 スレッドでの同時使用、出力が常に 1 行であること(乱数データ 500 件)、repeated の各要素を 1 件ずつ JSON にできること |
| `JsonFormatCompatibilityTest` | 7 | **JsonFormat との互換性**。乱数(シード固定)で全型・WKT・oneof・特殊値を埋めた AllTypes 5,000 件と DynamicMessage 版 5,000 件、宣言順≠番号順の型 500 件、Builder 200 件で、出力が「JsonFormat の出力の int64 だけを数値にしたもの」(`JsonFormatOracle`)と**文字列として完全一致**し、OutputStream 出力とも一致することを確認 |
| `TimestampFormatTest` | 2 | 自前の Timestamp 書式が `Timestamps.toString` と一致(0001〜9999 年の 20 万件 + 閏日・1582 年のグレゴリオ暦切り替え前後・最小 / 最大)、範囲外で同じ例外 |
| `RobustnessTest` | 9 | 異常系: 深すぎる入れ子(スタックオーバーフローにならず `IllegalArgumentException`)、途中で失敗したときに正しい JSON に見える出力を残さない、Struct / Any の中の巨大な文字列・長いキー、キャッシュの上限・printer ごとの独立・GC での解放(メモリリークしない)、null の引数、書き込みの失敗 |
| `GrpcEndToEndTest` | 2 | Go サーバから受信した SimLog(result なし / あり)の JSON と、server streaming で受信した ObjectLog を 1 件ずつ JSON にしたもの(利用例を兼ねる) |

テスト専用の proto は `java/src/test/proto/` にあります(`protojson_test.proto`: 全型、`protojson_test_proto2.proto`: proto2 の確認用)。

## 検証結果

**実行して確認したこと**

- `mvn clean test`(`src/java`): **54 件すべて成功**(Go サーバ起動時。停止時は統合テスト 2 件がスキップされ 52 件成功)。
  `src/examples` の `mvn test`: 6 件すべて成功(サーバ停止時は 3 件スキップ)。
  Java 21.0.12.1 と **Java 17.0.20.1** の両方で確認
- 本体コードのコンパイル警告なし(`-Xlint:all`)、Javadoc の文法チェック(`-Xdoclint:all`)も警告なし
- **テストの検出力**: 本体をわざと壊して実行し(10 種)、すべてテストが失敗することを確認して元に戻した

  | 壊し方 | 失敗したテスト数 / 45 |
  |---|---:|
  | int64 を文字列で出力 | 22 |
  | uint32 を符号付きで出力 | 7 |
  | oneof の判定を無視 | 30 |
  | デフォルト値を省略しない | 41 |
  | キー順を宣言順にする | 2 |
  | Timestamp のマイクロ秒精度を 9 桁で出力 | 8 |
  | Timestamp を秒数で出力 | 8 |
  | map を検出しない | 1 |
  | UTF-8 出力で絵文字をエスケープする(下記の不具合の再現) | 8 |

  製品組み込みの点検で直した 4 点も、元の動作に戻すと `RobustnessTest`(9 件)が失敗することを確認した:
  閉じ括弧の自動補完を戻す → 1 件、深すぎる入れ子を `IOException` のままにする → 2 件、
  読み直しを Jackson の既定の上限で行う → 1 件、キャッシュを static に戻す → 4 件、キャッシュの上限を無くす → 1 件

- **開発中にテストで見つけて直した不具合**: Jackson 2.x の既定では、OutputStream(UTF-8)に書く場合だけ絵文字などの
  BMP 外の文字がサロゲートペアの Unicode エスケープになり、`print()` の出力とバイト列が異なっていた。
  既定の JsonFactory で `JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8` を有効にして解消
  (`ProtoJsonPrinter.Builder#defaultJsonFactory`)。独自の JsonFactory を渡す場合はこの Feature を有効にすること。
- 性能(`PrinterBenchmark`、SimLog 1000×50 = Event 5 万件・JSON 約 4.3MB、Java 21、3 回実行):

  | ケース | 最小 [ms] | 割り当て [MB/回] |
  |---|---:|---:|
  | JsonFormat.print(int64 は文字列、参考) | 53–54 | 128–133 |
  | `ProtoJsonPrinter.print`(SimLog 全体 → String) | 24–26 | 33–39 |
  | `ProtoJsonPrinter.writeTo`(SimLog 全体 → OutputStream) | **19–20** | **6–12** |
  | 1 レコードずつ `print`(logs の 1000 件それぞれ → String) | 27–28 | 40–45 |
  | 1 レコードずつ `writeTo`(logs の 1000 件それぞれ → OutputStream) | 20–21 | 7–12 |

  1 レコードずつ JSON にしても、全体を 1 回で JSON にする場合とほぼ同じ時間で済む。

**確認していないこと**

- 本番の proto・実データでの出力と性能
- proto2 の網羅的な確認(extension / group は未対応として例外にしている)
- JsonFormat のオプション相当の出力(整形・proto 名のキー・デフォルト値の出力)は実装していない
- Linux 以外の OS
- Go サーバのテスト(動作テスト用のため作っていない。ビルドと Java 統合テストからの呼び出しのみ確認)
- 依存ライブラリの脆弱性データベースとの照合(この環境から接続できなかった。上の「製品組み込みの点検結果」参照)
- 長時間・高負荷での連続稼働(メモリの増え方は単体テストと設計で確認したが、負荷試験はしていない)

## 移植・転用するとき

1. `java/src/main/java/io/github/ramsesyok/protojson/` の 5 ファイルをコピーし、パッケージ名を変える
   (クラス間の参照は同一パッケージ内だけ)。NDJSON が必要なら `examples/` の `NdjsonWriter.java` も持っていく
   (ライブラリ側からは参照していないので、不要なら持っていかなくてよい)。
2. 依存に protobuf-java / protobuf-java-util / jackson-core(2.18.0 以上)を追加する。
   protobuf-java のバージョンは利用側の生成コードに合わせる。
3. `java/src/test/` のテストも持っていくことを推奨。特に `JsonFormatCompatibilityTest` は、protobuf-java や Jackson を
   更新したときに JsonFormat との互換性が崩れていないかを検出する(本ライブラリは JsonFormat の出力規則を自前で再現しているため)。
   テストには `src/test/proto/` の proto と、統合テスト用の `../proto/simlog.proto` が必要(不要なら `GrpcEndToEndTest` と
   `PrinterBenchmark`、pom の simlog 関連の execution を削除する)。
4. map が必要になった場合は、`MessageLayout.validate` の map の検出を外し、`ProtoJsonPrinter.writeMessage` に
   map の書き出し(キーは常に文字列、値は `writeValue`)を追加する。互換性テストに map を含む型を加えて確認すること。
