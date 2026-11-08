# 最初の実装：ローカル研究コア 0.1.0

2026-11-08。実装範囲は合成Agentの研究用プリン、決定的な味の表現、SQLite保存、狭いstdio MCP。設計書の全機能を提供したものではない。

## 実行環境と選定理由

Python 3.11以上、SQLiteのJSON関数、OSのIANAタイムゾーンデータがあるLinuxを対象にする。実行時のPython外部依存はない。今回のローカル検証はPython 3.12。CIは3.11/3.12/3.13で確認する設定であり、実際の結果はPRのChecksを参照する。

整数mg・有理数・トランザクションを小さく検証できるため、この研究コアをPythonで独立実装した。MCPアダプターは同じコアを呼ぶ。HTTPサーバーはまだない。非公開の第三者フレームワークやそのセキュリティ実装をコピーしていない。今後のHTTP基盤導入は依存方向とライセンスを別途確認する。

リポジトリのルートで実行する。APIキー、モデル、GPU、決済資格情報は不要。

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m flamoris_pudding demo --db /tmp/pudding-demo.sqlite3
PYTHONPATH=src python3 -m flamoris_pudding mcp --db /tmp/pudding-mcp.sqlite3
```

demoは部分開封250→750、蓋の廃棄、本体8000mg＋カラメル2000mg、一口、残量廃棄、片付けまで実行する。最終revisionは7、消費10000mg・廃棄90000mg、セッションはclosed。独立した仕訳候補fixtureも表示する。食べ物の廃棄から会計候補を自動生成したものではない。demoを再実行する場合、同じ合成主体に未解決セッションがないことが必要。

インストールして使う場合は `python3 -m pip install -e .`。ビルドにはsetuptoolsが必要で、未導入の環境では取得のためネット接続を使う。インストール後のコマンドは `flamoris-pudding demo` / `flamoris-pudding mcp`。開発時は上記PYTHONPATH方式で外部取得なく実行できる。

## 状態の所有と再送

`core.Store` がSQLite内のセッション、部品別のカップ・スプーン・消費・廃棄量、イベント、再送応答、Agentごとの違反状態を所有する。人格・会話・記憶・推論・描画・決済・総勘定元帳は所有しない。

- 各部品について `initial = cup + spoon + consumed + discarded` を維持する。
- 一つのAgentに、未片付けを含む未解決セッションは一つだけ。
- 全開後の蓋を捨てずに一口へ進もうとすると拒否し、同じ蓋の義務につき一度だけ違反を記録する。
- 空スプーン・残量超過などの不成立の食べる操作は、蓋の規則より先に検証し、違反にはしない。
- 完食は片付け待ち。片付けで閉じる。未片付けのまま次の配膳を要求した場合も、義務につき一件だけ違反。
- 閾値はプロセス管理者の設定値（初期3）としてDBへ固定する。Agent引数では変更できない。閾値以上では新規配膳・開封・すくう・味わうを拒否し、状態確認・蓋の廃棄・研究用残量廃棄・片付けを残す。
- 更新にはUUID `request_id` と最新の `expected_revision` を使う。主体＋request_idをDBで一意にし、操作・対象・正規化入力が同じなら保存済みの応答をそのまま返す。異なる入力でIDを再利用すると拒否する。過去応答は最新状態ではないので、必要ならgetで読む。
- SQLiteの `BEGIN IMMEDIATE` で状態・違反・イベント・再送応答を一緒に確定する。同revisionへの異なる同時操作は一つだけ成功する。DB失敗では全てロールバックする。
- 食べる操作が拒否されても違反だけ確定する場合がある。`food_effect=none` と `policy_effect=recorded` を区別する。

## MCP契約

stdioのMCP revision `2025-11-25`。initialize→notifications/initialized→tools/list / tools/call、pingを扱う。HTTP、resources、prompts、sampling、tasks、キャンセルは実装していない。標準入力はUTF-8の改行区切りJSON、標準出力はプロトコルだけ。入力は一行65536byteまで、構造の深さ64、ノード10000まで。一接続の要求IDは10000個までで終了するため、長期クライアントは再起動・再接続を扱う。

| ツール | 固有の引数 | 動作 |
|---|---|---|
| `pudding.presets.list` | なし | authoredな基準と3比較例 |
| `pudding.sessions.create` | request_id、任意preset_id | 一個の研究用配膳を作る |
| `pudding.sessions.get` | session_id | 状態を変更せず読む |
| `pudding.lid.peel` | 共通＋amount_q（1〜1000） | 累計1000で全開 |
| `pudding.lid.dispose` | 共通 | 外れた蓋をゴミ箱へ |
| `pudding.spoon.scoop` | 共通＋body_mg、sauce_mg | 正確な量をスプーンへ |
| `pudding.spoon.taste` | 共通 | スプーンの中身を一度消費 |
| `pudding.sessions.finish` | 共通 | 研究用の残量を捨てて食事終了 |
| `pudding.sessions.cleanup` | 共通 | 空の容器等を片付けて閉じる |

共通引数はsession_id、request_id、expected_revision。UUIDは小文字の標準形式、revisionは0〜2147483647、mgは0〜100000の整数で合計は正。boolや未知の引数は拒否する。Agent ID、任意の時刻、labフラグ、税率・会計方針の変更はツール引数にない。ドメインの拒否は `isError=true` とstructuredContent内のerrorで返す。JSON-RPC自体の不正・未知のツールはプロトコルエラーで返す。

接続設定例（パスを取得先へ置き換える）：

```json
{
  "command": "python3",
  "args": ["-m", "flamoris_pudding", "mcp", "--db", "/path/to/local-research.sqlite3"],
  "env": {"PYTHONPATH": "/path/to/flamoris-pudding/src"}
}
```

実際のFLAMORIS HubやChatGPTへの登録・配備・実機受入れはまだ行っていない。参考：[公式stdio仕様](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[接続ライフサイクル](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[ツール仕様](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)。

## 期限・会計の試験境界

通常のcreateは無料の合成fixtureであり、購入時刻を捏造せず期限は未適用。Pythonの信頼するテスト設定 `fixture_purchased_at` と注入時計だけで、購入14日・JSTの暦1か月・仮想劣化を検証する。MCPにはこの設定を公開しない。

期限到達時の読み取りはeffective_ageを返すだけ。14日以降の有効な一口要求は一度ずつ違反を記録し、閾値に達する要求では消費しない。1か月以降は、処理前でも開封・すくう・味わうを拒否する。`Store.sweep_expired(limit=...)` は管理側が明示実行する合成fixtureの廃棄処理で、残量とスプーンを廃棄へ移し片付け義務を残す。一度の取得・処理件数を制限するが、毎日自動起動するスケジューラーはない。失効予定時刻と実際の廃棄処理時刻を区別する。

`accounting.expiry_candidate()` は、信頼する管理側が権利失効・返金等の義務消滅を別途確認した残高だけを、借方「契約負債」／貸方「期限切れ雑収入」の候補へ変換する純粋関数。既認識の対価・返金を控除する。すべて `REVIEW_REQUIRED`・外部転記不可。IDは失効対象・帳簿・種別から固定するが、永続的な候補重複排除・残高台帳・outbox・外部元帳との照合は未実装。廃棄イベントだけから義務消滅を断定しない。

`TaxRate` は適用期間付きの不変なfixture値、率を有理数で持ち、重複と対象時点の欠落を拒否する。`calculate_inclusive_grouped_tax` は一請求書・一率グループの税込合計から最後に一度切捨てる実験関数。商品マスタ、承認済み税区分、正式な税率台帳、課税時点判定、請求書発行は未実装。8%/10%の判断をこの関数が行うことはない。

## セキュリティと未実装

これは信頼するローカル所有者が起動する**合成Agent専用の研究ハーネス**。起動設定のsynthetic-agentはテスト用名前空間であり、本人確認ではない。DBや起動設定を読める人を攻撃者から隔離する仕組みではない。DBファイルとバックアップは所有者だけがアクセスできる場所へ置く。他Agentの引数偽装は拒否できるが、同じプロセス資格情報を共有する実在のMisakiとChipsyの分離を証明したものではない。

まだないもの：有料商品の発行・購入・決済、人間とAgentの検証済み主体、割当と一個ごとの承認・失効・返金、新鮮な有料残量の保護、公開HTTP、Hub接続、片付け猶予とサービス障害を考慮した定期違反確定、人間の審査・訂正・解除、保存TTL・アーカイブ、商用のバージョン移行、正式会計転記。現在のDBスキーマを商用DBとして継続利用する保証はない。

味・好みの数値は[実験001](EXPERIMENT_001.md)の仮定。期待・満足・余韻・台詞、ミルク/チョコの個別presetは次の実験。美祥の腐敗状態の試食拒否は維持し、全ての劣化・違反テストは合成主体だけで行う。
