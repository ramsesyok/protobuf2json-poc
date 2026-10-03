# protobuf2json-poc

gRPC(Protobuf)で受け取ったメッセージを JSON に変換するとき、**int64 を文字列ではなく JSON の数値で出力する**ための
検証(PoC)と、その結果を製品に組み込める形にした Java ライブラリのリポジトリです。

## 背景

Protobuf 公式の JSON 変換(Java の `JsonFormat`)は、int64 / sint64 / sfixed64 を `"123"` のような**文字列**で出力します
(JavaScript の数値では 2^53 を超える整数を正確に表せないため)。一方、連携先との契約である OpenAPI の定義は `type: integer`(数値)で、
変更できません。そこで、int64 だけを `123` のような数値にし、それ以外は JsonFormat と同じ規則で出力する変換を作りました。

```
JsonFormat     : {"jobId":"9007199254740993","result":{"exitCode":"-1","output":"12345"}}
このライブラリ : {"jobId":9007199254740993,"result":{"exitCode":-1,"output":"12345"}}
```

## 構成

| ディレクトリ | 内容 | 読むべき人 |
|---|---|---|
| [`src/`](src/README.md) | **製品版**。Java ライブラリ(`src/java`)、利用例と出力サンプル(`src/examples`)、動作テスト用の Go の gRPC サーバ(`src/go`)、共有の proto(`src/proto`) | **ライブラリを使う・組み込む人はここから** |
| [`proto-int64-demo/`](proto-int64-demo/README.md) | **PoC の記録**。変換方式 3 つ(JsonNode ツリー方式 / ストリーミング方式 / 1 パス方式)の実装と、正しさ・性能の比較、複雑な proto での検証 | 方式を選んだ理由や、検証の経緯を知りたい人 |

## はじめに読むもの

1. [`src/README.md`](src/README.md): ライブラリの使い方・出力規則・例外・制約・テストと検証結果・製品組み込みの点検結果・移植の手順
2. [`src/examples/samples/`](src/examples/samples/README.md): 実際の出力 JSON(SimLog 全体の result あり / なし、ObjectLog 1 レコード)
3. ソースコード: ライブラリ・テスト・利用例のすべてに、Java 初学者でも読めるよう説明コメントを書いています。
   仕様のまとめは `src/java/src/main/java/io/github/ramsesyok/protojson/package-info.java`

## 前提ツール

Java 17 以上(17 / 21 で確認)、Maven 3.9 系。Go の動作テスト用サーバを動かす場合は Go 1.25 以上
(1.21 以上なら toolchain 機能で自動取得)。詳しくは `src/README.md` の「クイックスタート」を参照してください。

## 経緯(プルリクエスト)

| PR | 内容 |
|---|---|
| [#1](https://github.com/ramsesyok/protobuf2json-poc/pull/1) | PoC: 3 方式の実装・比較、複雑な proto での検証(`proto-int64-demo/`) |
| [#2](https://github.com/ramsesyok/protobuf2json-poc/pull/2) | 製品版ライブラリ(`src/`)を追加 |
| [#3](https://github.com/ramsesyok/protobuf2json-poc/pull/3) | 利用例(log を 1 レコードずつ JSON にする)と出力サンプル |
| [#4](https://github.com/ramsesyok/protobuf2json-poc/pull/4) | NDJSON の書き出しをライブラリから利用例へ移動、利用例・利用例のテスト・ライブラリのテストに説明コメント |
| [#5](https://github.com/ramsesyok/protobuf2json-poc/pull/5) | 製品組み込みの点検(メモリリーク・例外処理などの修正)、ライブラリ本体に説明コメント |
