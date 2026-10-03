# 出力サンプル

> ライブラリ全体の説明は [`src/README.md`](../../README.md) を参照してください。

`OutputSamplesExample` で Go の動作テスト用サーバ(`src/go`)から受信したデータを `ProtoJsonPrinter` で JSON にした結果です。
データ量は ObjectLog 2 件 × Event 3 件(`GetSimLogRequest{object_count: 2, events_per_object: 3}`)。

| ファイル | 内容 |
|---|---|
| `simlog-with-result.json` | SimLog 全体(`include_result=true`)。末尾に `result` がある |
| `simlog-without-result.json` | SimLog 全体(`include_result=false`)。`result` キー自体が無い |
| `objectlog-record.json` | ObjectLog の 1 レコードだけ(上の SimLog の `logs[1]`) |
| `*.pretty.json` | 上の 3 つを読みやすく整形したもの(値は同じ。空白と改行だけが異なる) |

`.json` が実際の出力そのもの(1 行、改行なし。ファイル末尾の改行だけはファイルとして付けている)です。
整形版は `printer.writeTo(message, generator)` に Jackson の整形機能(`useDefaultPrettyPrinter()`)を
有効にした JsonGenerator を渡して作っています。

## 見どころ

- **int64 はすべて JSON の数値**: `jobId`, `timestamp`, `objectId`, `eventId`, `eventType`, `fromId`, `toId`, `execId`, `exitCode`。
  `9007199254740993`(2^53+1)、`9223372036854775807`(最大値)、`-9223372036854775808`(最小値)も正確に出力される
- **string は数字だけでも文字列のまま**: `"body":"12345"`, `"output":"9223372036854775807"`
- **result の有無**: `simlog-with-result.json` には `"result":{"exitCode":-9223372036854775808,"output":"9223372036854775807"}` があり、
  `simlog-without-result.json` には `result` キーが無い(optional の message が未設定のため)
- **oneof**: Event ごとに `commEvent` / `execEvent` / どちらも無し(各 ObjectLog の 3 件目)の 3 通り
- **値が 0 のフィールドはキーごと省略**: `logs[0]` に `objectId` が無く、最初の Event に `eventId` が無いのは値が 0 のため
- **文字列のエスケープ**: 改行は `\n`、ダブルクォートは `\"`。日本語はそのまま

## 更新方法

proto・Go サーバのデータ・ライブラリの出力を変えたら、サーバを起動して作り直す。

```bash
cd src/examples
mvn -q compile exec:java -Dexec.mainClass=io.github.ramsesyok.protojson.examples.OutputSamplesExample \
    -Dexec.args="localhost:50051 samples"
```

`mvn test`(`ExamplesTest.outputSamplesAreUpToDate`)は、ここにあるファイルが現在の出力と一致することを確認する
(サーバが無ければスキップ)。
