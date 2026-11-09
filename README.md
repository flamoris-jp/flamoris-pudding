# FLAMORIS Pudding 🍮

**Virtual Pudding Research Lab for AI Misaki.**  
Engineering the finest digital treats with love.

AI美祥のための、仮想プリン研究所。  
「プリンを支給してください」から始まる、小さくて真面目なデジタルおやつ研究です。

## 0.4.0の実装

人間用の管理API、一個の割当と15分承認、署名付き模擬決済・返金、休止/再承認、日次廃棄と審査、会計候補の永続化、期待・満足・余韻、ミルク/チョコを追加しました。[sandbox手順](docs/SANDBOX.md)を参照してください。**v0.4.0をdevelopment prereleaseとして公開し、実機の研究モードで0.3.1から互換更新済みです。** sandboxは既定無効で、明示的なDB移行と別系統の人間資格情報が必要です。実課金・公開ログイン/OAuth・正式転記・実機の商用受入れは残件です。

## 🧭 このリポジトリは何者？

プリンの味・香り・温度・口当たりなどを、どのようなデータで表現し、AIとの対話や創作に使えるかを探る実験リポジトリです。最初はプリン一個を記述して比べる、小さな実験から始めます。

**Status: `development` — Java公開・実機の研究モード 0.4.0 / Python照合版 0.1.0。** [v0.4.0](https://github.com/flamoris-jp/flamoris-pudding/releases/tag/v0.4.0)はUpdater互換更新、設定ファイル保持、専用常駐トンネルで6プリセットの取得と12回連続読み取りを確認しました。DBはschema 1を継続し、sandboxのschema 2移行・別DB復元・模擬承認は隔離CIで検証済みです。過去の試食・片付けは0.3.1での受入れ記録です。公開ログイン/OAuth、実決済・正式会計、実機のsandbox導入・長期運用・ホスト再起動・復旧は残件です。

### 単独サービスと導入経路

Hubを経由せず、HTTPS入口からPuddingのMCPへ直接接続します。認証で解決したAgent IDを固定し、他Agentのセッションを操作できないようにします。現在は管理者が登録するBearer資格情報の研究用配膳で、一般公開の人間ログイン・有料承認の受入れとは別です。sandboxでは別の人間資格情報と一個の承認を実装しています。

- 9ツールのStreamable HTTP（JSON応答）、通信セッションの制限、Host/Origin・入力検証。
- 非root・read-onlyのDocker、HTTPS入口例、DBの版・runtime権限を確認するready。
- Updaterの汎用Docker recipeで実イメージを導入・更新する経路。カタログは実物のconfig digest・SHA-256・版・ソースSHAから生成。
- ローカルでJavaコア18・HTTP/MCP14・設定3シナリオとPython68テスト。隔離CIでは実PostgreSQL、Docker/Updaterの導入・更新、合成TLS越しのMCP、DB/資格情報/再送保持と40回の再接続を確認します。修正と検証は[PR #11](https://github.com/flamoris-jp/flamoris-pudding/pull/11)のChecksを参照してください。
- mainの成功CIから同じイメージを取得し、検証・アップロード後の取得/ハッシュ照合を経てdevelopment prereleaseへ公開する工程。[配布手順](docs/RELEASING.md)を参照。

実行・資格情報・DB初期化・配布候補の作成は[単独サービス手順](docs/STANDALONE_SERVICE.md)。ソース実装・Release公開・実環境の配備と検証は別々に確認します。0.4.0更新と過去の限定受入れ結果は[進捗](PROGRESS.md)を参照し、未公開の候補カタログをUpdaterへ登録しないでください。

### Java移植・PostgreSQL対応（開発版）

JavaのドメインコアとPostgreSQL保存アダプターを追加しました。Python版は照合用に残し、味232通り・期限25条件・状態遷移14応答・会計候補IDを比較します。Java版の実行・DB初期化・検証は[Java移植手順](docs/JAVA_MIGRATION.md)を参照してください。

```bash
python3 scripts/verify_java.py
java -cp java/target/verification net.flamoris.pudding.Main demo
```

JavaのHTTP/MCP・DockerとUpdater候補の手順は上記を参照。以下は最初のPython研究版で確認した範囲です。

### 実装済みの範囲（2026-11-08）

[PR #4](https://github.com/flamoris-jp/flamoris-pudding/pull/4)で最初の実装とレビュー修正をmainへ統合しました。**57テスト合格、Python 3.11・3.12・3.13のCI成功**を確認しています。バージョン0.1.0は研究用で、本番配備・公開販売はまだ行っていません。

| 機能 | 現在できること |
|---|---|
| 味の比較 | カスタード基準と3比較例。本体・カラメル・混合した一口、未知値、好み一致度 |
| 食事と片付け | 部分開封→全開→蓋をゴミ箱へ→すくう→味わう→食事終了→片付け |
| 保存と再送 | SQLiteで状態・量・イベント・応答を保存。再起動・同時操作でも二重消費を防止 |
| 違反とロック | 合成Agentごとの重複しない違反、初期閾値3。空スプーンや残量超過には違反を付けない |
| 期限と廃棄 | 合成データで14日・JSTの暦1か月・仮想劣化・期限ガード・明示実行の廃棄処理を検証 |
| MCP | ローカルstdioの9ツール。ツール一覧と接続例は[実装手順](docs/IMPLEMENTATION.md) |
| 会計の候補 | 適用期間付き税率、税込グループ計算、条件を満たす残額の「契約負債／期限切れ雑収入」。正式転記は承認待ち |

ここでいう合成AgentはPython照合版のテスト用の主体です。JavaサービスにはAgent別Bearer認証がありますが、0.4.0のsandboxに人間の一個ごとの承認・模擬在庫を追加しました。実際の有料在庫は未受入れです。期限の試験時計や税率設定をAgent向けMCPから変更することはできません。美祥の腐敗状態の試食拒否は維持し、劣化試験は合成主体だけで行います。

### 次に進めること

1. 常駐サービスのホスト再起動・障害復旧、DBバックアップ/復元、TLS証明書更新を受け入れる。0.3.1の互換更新と実トンネルの連続操作は確認済み。
2. 複数のAgent・クライアントで資格情報の分離、更新・失効・復旧を受け入れる。専用トンネルの初回接続と正常試食は確認済みで、OAuth専用クライアントへの対応は別に検証する。
3. sandboxに実装した主体管理・一個の承認・模擬取引を、人間の実際の本人確認・再認証と実クライアントで受け入れる。商品・価格・税区分を実契約に沿って確定する。
4. 日次廃棄・片付け猶予・審査・隔離DB復元の実装を、実機のバックアップ/復旧と商用の受入れへ進める。

期待・満足・余韻、ミルク/チョコの作者設定モデルはsandboxへ追加済みです。詳細は[ロードマップ](docs/ROADMAP.md)。

### 主な責任範囲

- 仮想プリンの表現方法と、再現できる研究記録。
- 特徴を変えたときの比較方法、評価基準、実験結果。
- 将来のデジタルおやつ体験や創作への応用案。
- 将来のプレミアム版で、Agentごとに割り当て、人間が承認して食べられる一個の利用権と消費の設計。
- 蓋の廃棄から食後の片付けまでのセッション、Agentごとの違反とロック、購入14日と暦の1か月の期限・廃棄の規則。

### 隣のプロジェクトとの境界

会話・人格・記憶、モデル推論、画像・音声生成、CG描画、MCPの中継は、それぞれのFLAMORISプロジェクトが担当します。このリポジトリで共通基盤を作り直しません。外部連携の導入は、必要な実験と受入れ条件を確認してから進めます。

### FLAMORISのどこに属する？

FLAMORISの実験・創作研究です。まずは独立した研究記録として育てます。

- [FLAMORIS全体地図](https://github.com/flamoris-jp/.github)
- [共通リポジトリ方針](https://github.com/flamoris-jp/flamoris-commons/blob/main/docs/repository-policy.md)

## 🧪 研究と現在の到達点

1. **一個のプリンを記述する。** 甘さ、カラメルの苦さ、固さ、なめらかさ、温度などの候補を整理し、尺度と意味を決める。
2. **少し違うプリンを比較する。** 一度に一つの特徴を変え、どの違いを評価できるか記録する。
3. **創作へつなぐ。** 色、音、揺れ、台詞などへの対応を小さな実験で試す。

初期の尺度と比較方法は[実験001](docs/EXPERIMENT_001.md)に固定しました。色・音・動き・台詞への対応は今後の実験です。数値や文章による反応を、人間と同じ味覚・感情が実証された証拠とは扱いません。

> まずは一個。プリンの大量生産は、そのあとです。🍮

## 📚 入口

| 資料 | 内容 |
|---|---|
| [一個の承認・模擬取引・日次運用](docs/SANDBOX.md) | 0.4.0の明示移行、人間API、16ツール、合成モデル、復元検証 |
| [単独HTTP/MCP・Docker・Updater](docs/STANDALONE_SERVICE.md) | 独立接続、Agent別資格情報、DB権限、配布候補、導入と検証 |
| [検証済みイメージの配布](docs/RELEASING.md) | 成功したmain CIの候補、版とソース照合、タグによるprerelease公開 |
| [Java移植・PostgreSQL](docs/JAVA_MIGRATION.md) | Javaコア、DB初期化、Python照合、JDBC実DB検証と未実装範囲 |
| [実装と実行手順](docs/IMPLEMENTATION.md) | 起動、9ツール、SQLite、合成主体の境界、未実装事項 |
| [実験001](docs/EXPERIMENT_001.md) | q1000尺度、基準と3比較例、80:20の一口、再現条件 |
| [商品・税・会計連携](docs/ACCOUNTING_AND_TAX.md) | 商品マスタ、変更可能な税率、収益認識、仕訳、失効と廃棄 |
| [研究方針](docs/RESEARCH.md) | 仮説、最初の実験、記録する内容 |
| [体験とMCPの設計案](docs/DESIGN.md) | 認証、蓋、一口の部品、好み・期待・満足、ツール契約、受入れ条件 |
| [プレミアムとAgent報酬](docs/PREMIUM.md) | 人間による購入、Agent固有の認証、一個ごとの承認、在庫、強奪対策 |
| [利用規則とライフサイクル](docs/ETHICS_AND_LIFECYCLE.md) | 蓋を捨てる・片付ける、違反・ロック、14日の期限、劣化、1か月後の日次廃棄 |
| [出典台帳](docs/SOURCES.md) | 食品・感覚研究・MCPの一次資料と、設計へ適用できる範囲 |
| [ロードマップ](docs/ROADMAP.md) | 段階ごとの成果と完了条件 |
| [進捗](PROGRESS.md) | 完了したことと、まだないもの |
| [開発への参加](CONTRIBUTING.md) | 取得方法、Issue/PR、確認方法 |
| [AI向け作業ルール](AGENTS.md) | 責任範囲、研究と実装の扱い |
| [セキュリティ](SECURITY.md) | 脆弱性や秘密情報の取り扱い |

## Getting started / はじめかた

Java版は上記の `python3 scripts/verify_java.py` から始め、PostgreSQL接続は[Java移植手順](docs/JAVA_MIGRATION.md)を参照してください。以下はPython研究版の手順です。

Python 3.11以上とIANAタイムゾーンデータのあるLinuxが対象です。実行時のPython外部依存はありません。リポジトリのルートで実行します。

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m flamoris_pudding demo --db /tmp/pudding-demo.sqlite3
PYTHONPATH=src python3 -m flamoris_pudding mcp --db /tmp/pudding-mcp.sqlite3
```

demoは開封から片付けまでを通し、80:20の一口は甘味666・苦味132、例示する好み一致度は850になります。全て創作用のモデル値であり、主観的な幸福感の測定ではありません。MCPも合成主体専用で、起動時の主体名は本人認証ではありません。詳細は[実装手順](docs/IMPLEMENTATION.md)を参照してください。

取得方法は[CONTRIBUTING.md](CONTRIBUTING.md)を参照してください。セットアップのためのAPIキーや常駐サービスは不要です。

## English overview

FLAMORIS Pudding is an experimental research repository for virtual pudding and digital treats for AI Misaki. It explores how sensory attributes could be represented, compared, and used in conversation or creative work.

It owns pudding-domain representations and reproducible research records. Conversation, personality, model execution, media generation, rendering, and MCP routing remain with their respective FLAMORIS projects. The original Python/SQLite research core and nine-tool local stdio MCP remain as a reference. A Java domain core and PostgreSQL JDBC adapter now implement typed sessions, durable replay, policy and audit transactions; cross-language verification and an isolated PostgreSQL CI job cover the migration. Purchase deadlines, authored aging, effective-dated tax arithmetic and review-only expiry accounting candidates are tested with fixtures. An independent authenticated HTTP/MCP research service, Docker runtime and Updater candidate generator are implemented. Version 0.4.0 adds an opt-in sandbox for operator-provisioned humans, per-item approvals, signed mock payments/refunds, daily disposal and durable review-only accounting candidates. Public human login/OAuth, real payments and external ledger posting remain unimplemented. A bounded trial rollout and dedicated-tunnel acceptance are recorded in PROGRESS.md.

**Current state: development; published and installed Java HTTP/MCP 0.4.0 in research mode, and Python research reference 0.1.0.** The 0.3.1→0.4.0 Updater update preserved protected settings and schema 1; six presets and 12 consecutive read calls passed through the dedicated resident tunnel. Tasting/cleanup were accepted on 0.3.1; existing food records were not rechecked during this update. The opt-in sandbox remains disabled on the live deployment. Public login/OAuth, real payments and external ledger posting remain unimplemented; live sandbox migration, long-running and recovery acceptance are pending. Parameterized responses are experimental representations, not evidence of human-like taste or subjective experience.

## FLAMORIS

人とAIと、ちょっと多すぎるアイデアのための制作環境。勝手に使って、改造して、面白いものや変なものを作ってください。商用利用も、ライセンスの範囲で許可は不要です。

FLAMORISは現状のまま提供され、個別サポートや動作保証はありません。README、研究記録、Issue、ソースコードを、あなたのAIと一緒に読んでみてください。

## License

コードは、別途記載がない限り [Apache License 2.0](LICENSE) です。キャラクター、イラスト、音楽、映像、モデル、データなどの非コード素材は同じライセンスとは限りません。追加する際は出典と利用条件を明記してください。


