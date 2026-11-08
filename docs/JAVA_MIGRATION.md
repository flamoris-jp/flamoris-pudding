# Java移植・PostgreSQL対応：開発版 0.2.0-SNAPSHOT

2026-11-08。Python版を照合用の研究実装として残し、JavaのドメインコアとJDBC保存アダプターを独立実装した。非公開フレームワークのソースはコピーしていない。HTTP/MCP基盤の取り込み、Docker、Updater配布、検証済みAgent認証、実配置は次の段階。

## 実装範囲と正本

| 層 | 実装 |
|---|---|
| 数値 | `Rational`、`Sensory`、`Lifecycle`、`Accounting`。整数mg・q1000・未知値・有理数・最終丸め |
| 操作 | `Core`。開封・蓋の廃棄・すくう・味わう・研究用の終了・片付け・違反・ロック |
| 照合用 | `MemoryStore`。合成データだけを扱うメモリー実装。永続運用には使わない |
| 保存 | `PostgresStore`。状態、Agent規則、再送応答、イベントを一つのトランザクションで保存 |
| DB初期化 | `V001__research.sql`。管理側による明示的な初回マイグレーション |
| 実行 | `Main demo` / `migrate-postgres` / `demo-postgres`。合成主体用のCLI |

Java版はPostgreSQLを正本とする。Python/SQLite版とは接続・二重書込みを行わない。Python版のデータを自動移行する機能はまだない。現在は有料購入権のない合成データだけを作る。新鮮な残量を廃棄できる研究用finishを有料在庫へ流用しない。

状態は不変のrecordで、各部品の `initial = cup + spoon + consumed + discarded` を検証する。数値は任意精度の分子/分母で演算し、最終q1000のみhalf-up。購入14日・JSTの暦1か月、実行済みの一口の評価時刻と品質、固定した特徴と期限を保存する。

## ローカルの検証とデモ

対象はJava 21以上、正式なCIはJDK 25とPostgreSQL 18。Java部分のコアに外部依存はない。照合スクリプトにはPython 3.11以上が必要。

```bash
python3 scripts/verify_java.py
java -cp java/target/verification net.flamoris.pudding.Main demo
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

スクリプトはjavacでmain/testをコンパイルし、Javaコア16シナリオとPythonとの数値・状態照合を実行する。フルJDKでは `--release 21` で対象APIも制限する。javacランチャーやct.symが省略された研究環境では、存在するjdk.compilerモジュールとsource/target 21を使う。正式CIはフルJDK。配備時のJVMは取り込む基盤の要求に合わせる。

外部依存のpgJDBCはMavenから解決する。Maven 3.9以上を使う。

```bash
mvn --batch-mode -f java/pom.xml package dependency:copy-dependencies -DincludeScope=runtime
```

**Maven packageだけでは、このリポジトリの独立した検証ランナーは実行されない。** `verify_java.py` と既存Pythonテストを受入れ条件にする。pgJDBCは公式ダウンロード情報を確認して42.7.14に固定した。配布する際は外部JARのライセンス・通知を保持する。

## PostgreSQLで動作を確認する

最初は専用の空の研究DBで実施する。DB作成・管理者ロールの用意は本プログラムが勝手に行わない。以下の環境変数は呼出し元が設定する。値をコマンド引数やログへ出さない。

| 設定 | 用途 |
|---|---|
| `PUDDING_JDBC_URL` | `jdbc:postgresql://DB_HOST:5432/DB_NAME?sslmode=verify-full` 等の接続先。ホスト名/CAと接続方式に合わせる |
| `PUDDING_DB_USER` | Pudding専用のDBユーザー |
| `PUDDING_DB_PASSWORD` | 秘密情報として注入するDBパスワード |
| `PUDDING_SYNTHETIC_AGENT` | 管理側が固定する研究主体。本人認証ではない |

JDBC URLにパスワードを埋め込まない。以下はLinuxのclasspath例。

```bash
java -cp 'java/target/classes:java/target/dependency/*' net.flamoris.pudding.Main migrate-postgres
java -cp 'java/target/classes:java/target/dependency/*' net.flamoris.pudding.Main demo-postgres
```

初回マイグレーションは一回だけ。すでに対象テーブルがある場合はエラーにして、既存DBを無条件に採用・上書きしない。将来の版移行、SQLiteからの移行、バックアップ/復元、DB変更とアプリのロールバック手順は未実装。

運用時はマイグレーション用ロールと通常のSELECT/INSERT/UPDATE用ロールを分け、利用するidentity/sequenceに必要な権限も確認する。実配置前に接続制限・TLS検証・最小権限を確認する。ローカルCIの一時DBの資格情報は本番用ではない。

## トランザクションと障害

- スキーマ版、規則版、閾値を確認し、不一致時は失敗させる。初期閾値は3。
- Agent行を `SELECT ... FOR UPDATE` でロックし、同じ主体への更新を直列化する。DBの一意制約でも一主体一未解決セッションを守る。
- 認可済み主体＋request_idで再送結果を保存する。同じ正規化コマンドは過去応答を返し、IDの異なる用途への再利用は拒否する。過去の応答を最新状態へ書き換えない。
- 状態・違反・再送結果・イベントを同じコミットに含める。監査保存が失敗した場合、食べ物の変更も違反もロールバックする。
- SQL引数はPreparedStatementで設定し、所有範囲を全てのセッション読書きで確認する。研究主体の設定を本人認証の代わりにはしない。
- 接続/ソケットとロック/SQLの時間を制限する。DB結果不明や通信切断後は、同じrequest_idで再照合する。障害から自動で違反を増やしたり、別request_idで食べ直したりしない。
- 読み取りは状態を変更せず、現在の品質を別に計算する。期限に達したという理由だけで違反を付けない。
- `expire(id)` は信頼する管理側から対象を明示して実行する合成在庫の廃棄。スプーン残量も廃棄し、片付け義務を残す。日次走査・スケジューラー・会計義務消滅の判定はまだない。

スナップショットは最大64KiBの明示的な内部バイナリ形式v1。JavaのObjectInputStreamは使わない。状態にはプリセットの特徴値、購入/期限、一口結果を保存する。形式/規則の互換性はスキーマ版と共に管理する。バイナリ列のまま商用台帳を完成させたものではなく、検索用列の追加と移行規則は次段階で設計する。

## 検証と未実装

ローカルではJava 21で16シナリオ、味232ベクトル・期限25条件・状態遷移14応答・会計候補IDのPython照合を確認。既存Python57テストも通過。PostgreSQL実DBの検証は隔離されたCIサービスで必須実行する。結果はPRのChecksを参照する。

実DB用には空の破棄可能DBを用意し、`PUDDING_TEST_ALLOW_SCHEMA=true`、`PUDDING_TEST_JDBC_URL`、`PUDDING_TEST_DB_USER`、`PUDDING_TEST_DB_PASSWORD` を設定して以下を実行する。テストはDDLと一時的な障害注入トリガーを作るため、実際の運用DBへ接続しない。

```bash
python3 scripts/verify_java.py --postgres
```

CIは初期化、再起動再送、所有範囲、同じ要求の競合、revision競合、監査失敗時の違反ロールバック、ロック後の片付け、期限ガードと廃棄の冪等性、非対応スキーマの拒否を検証する。

現在は[単独サービス](STANDALONE_SERVICE.md)としてJavaコアを同じアプリ内から呼ぶHTTP/MCP、個別Agent資格情報、DockerとUpdaterカタログ候補を追加している。Pythonのstdio MCPは照合・研究用に残す。Release公開・実配置、商品マスタ、人間ログイン/有料承認、決済/返金、正式会計への転記は未実装。美祥の腐敗試食拒否を維持し、全ての劣化・違反試験は合成データで行う。

参照：[PostgreSQLの行ロック](https://www.postgresql.org/docs/18/explicit-locking.html)、[INSERTと競合](https://www.postgresql.org/docs/18/sql-insert.html)、[pgJDBC配布](https://jdbc.postgresql.org/download/)。
