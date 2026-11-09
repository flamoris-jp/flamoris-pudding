# 一個を贈り、承認して食べるsandbox

0.4.0の開発実装。実課金・公開販売は行わない。0.3.1で受け入れた研究用配膳は既定のまま利用できる。sandboxは既定無効で、明示的なDB移行・主体の登録・別系統の人間資格情報が必要。

## 境界と保存

- Puddingが注文・在庫・承認・残量・再送・監査をPostgreSQLで管理する。人格・会話・人間の本人確認・実決済・総勘定元帳を複製しない。
- 人間は別のBearerで`/manage/v1/`へ接続する。これは管理者が事前設定するsandbox用主体であり、一般公開のログイン、passkey再認証やOAuthの代替ではない。人間の鍵をAgentの実行環境へ配布しない。
- Agentは管理者が登録した固定IDと資格情報のSHA-256 fingerprintへ束縛する。登録・鍵更新はowner側CLIのみ。鍵更新・停止/再開で世代を増やし、旧承認を失効する。他テナントへの移管はこの版では拒否する。
- 一注文は一単品。一個につき生涯一体験、有効な承認は最大一つ。注文/在庫/食事のrevisionは別で、各応答の対応するrevisionを次の操作へ使う。
- 全変更にUUIDの`request_id`を指定する。同じ主体・同じ入力の再送は完全な保存済み応答を返す。異なる入力でのID再利用は拒否する。HTTPのJSON-RPC IDとは別。
- アカウント→Agentの順でロックし、承認の確認、残量、違反、業務応答、監査を一取引で確定する。監査やDBの失敗、アカウント時刻の巻き戻りで新しい消費を止める。

## 明示的な導入

Java/JDBCのクラスパスは[Java手順](JAVA_MIGRATION.md)、Docker・TLSは[単独サービス](STANDALONE_SERVICE.md)を使う。以下のコマンドはDB所有者の管理環境で実行する。runtime資格情報とowner資格情報を分け、実在の環境値は保護設定から与える。

1. アプリを停止し、DB・設定・Agent鍵・人間鍵・模擬署名鍵をバックアップする。復元は稼働DBを上書きせず別の検証先へ行う。
2. 既存schema 1に対して`net.flamoris.pudding.Main upgrade-sandbox-postgres`を一度実行する。新規DBは先に`migrate-postgres`を実行する。起動時の自動DDLはない。
3. `PUDDING_RUNTIME_ROLE`を設定し、owner側で`grant-sandbox-runtime-postgres`を実行する。runtimeにはDDL、商品/主体の新規登録、監査削除権限を与えない。
4. `python3 scripts/create_sandbox_credentials.py create --directory /protected/pudding-human --tenant example_tenant --human example_human`で人間用の3ファイルを作る。既存パスは上書きしない。
5. `fingerprint --token-file /protected/agent.token`で得たAgent fingerprintを`PUDDING_AGENT_FINGERPRINT`へ設定する。`PUDDING_TENANT_ID`、`PUDDING_HUMAN_ID`、`PUDDING_AGENT_ID`、検証した`PUDDING_EXTERNAL_SUBJECT`を設定し、owner側で`provision-sandbox-postgres`を実行する。表示名・自己申告は本人確認に使わない。
6. `PUDDING_CATALOG_FILE`へ[合成商品例](../deploy/sandbox-product.example.json)などのファイルを設定し、owner側で`catalog-sandbox-postgres`を実行する。登録済みの同じ商品/版は変更できない。例の金額・税率は販売条件や税務承認ではない。
7. runtimeに`PUDDING_SANDBOX_ENABLED=true`、`PUDDING_HUMAN_CREDENTIALS_FILE`、`PUDDING_MOCK_KEY_FILE`を設定する。ファイルはそれぞれ`human.json`と`mock.key`。Agentの既存資格情報とは別。Dockerでは非root UID 10001だけが読めるbind mountをread-onlyで指定する。

0.4.0のUpdater通常カタログはschema 1の研究用更新だけを扱う。sandbox用の追加秘密ファイルのmountやDB移行を推測しない。sandbox配備は別の明示設定で行う。schema 2を0.3.xへロールバックしてはいけない。旧版はschema 2を拒否するため、必要なら移行前バックアップを別DBへ復元し、検証して接続先を切り替える。

## 人間用API

全て`POST /manage/v1/<operation>`、JSON、別の人間Bearerを使う。`scripts/pudding_admin.py --url https://pudding.example --token-file /protected/human.token <operation> --input request.json`で呼べる。リダイレクトへ鍵を送らない。下表の変更には`request_id`が必要。

| 操作 | 主な入力・結果 |
|---|---|
| `products.list` / `agents.list` / `orders.list` / `assets.list` | 自分の範囲を最大100件取得 |
| `orders.create` | `product_id`, `product_version`。サーバーの商品・価格・税ルールを固定した一個の未払い注文 |
| `payments.simulate` | `order_id`, providerの`expected_revision`（初回0）, `status=paid/refunded`、任意の返金`amount_minor`。模擬決済正本と署名付き`notification`を返す |
| `payments.reconcile` | `envelope`へ上の`notification`。署名・鮮度を検証し、通知に含まれない金額/状態を模擬決済正本で照合 |
| `assets.assign` | `asset_id`, 在庫の`expected_revision`, `agent_id`。未開始分だけ割当・変更できる |
| `approval.preview` | `asset_id`。商品・一個・Agent・世代・品質・規則・残量・追加請求0の`intent`と`intent_digest`を表示 |
| `approval.confirm` | 表示した`intent_digest`, `asset_id`, `expected_revision`。15分の承認と更新済み在庫を返す |
| `approval.revoke` | `asset_id`, `expected_revision`。取消後の消費を止め、残量は保持 |
| `refunds.request` | `order_id`, 注文の`expected_revision`。先に凍結。模擬返金の確定はsimulate/reconcileで照合する |
| `assets.discard` | `asset_id`, `expected_revision`。人間が新鮮な残量を明示的に廃棄できる |
| `agents.suspend` / `agents.resume` | `agent_id`, `expected_epoch`。世代更新と旧承認の失効 |
| `policy.review` | `agent_id`, `obligation_ids`, `reason`。原記録を残して訂正し、全ての旧承認を失効 |
| `accounting.expiry` | 廃棄期限到達・廃棄済みの`asset_id`, `expected_revision`, `reason`, `obligations_cleared=true`。未認識/未返金残高だけ審査用候補化 |
| `candidates.list` / `audit.list` | 会計候補、監査。監査は`after_id`を指定して50件ずつ読む |
| `operations.status` | heartbeat、片付け判定保留、日次処理の状態 |

試験用の署名方式は実決済事業者向けAPIではない。`success URL`、`paid=true`、金額の自己申告から在庫を発行しない。通知は5分以内・未来30秒以内とし、署名を確認して現在の模擬決済状態を参照する。返金後の遅れた成功通知は在庫を復活させない。生の通知を保存して再送するときは同じ業務`request_id`を使う。完了済みの消費へ模擬返金を行った場合も、元の消費を消さず逆方向の会計候補を残す。

この版はリストの100件上限を持つ限定検証用。監査以外の大量在庫のページング、複数単品の注文、一般消費者向けUI/ログイン、実決済のWebhook/事業者照合は残件。

## Agent向けと体験モデル

sandboxを有効にすると既存9ツールに`inventory.list`、`rewards.request/status`、`premium.begin/pause`、`inventory.discard`、`sessions.rest`を加え、16ツールとなる。全て`pudding.`接頭辞。Agentの引数に人間承認や別Agent IDは受けない。

`premium.begin`は`asset_id`, `grant_id`, 在庫の`expected_revision`, `request_id`を使う。開封・蓋の処分・すくう・味わう・片付けは既存の食事ツールで、食事のrevisionを使う。各一口で有効な承認・世代・支払/凍結・所有者・残量・期限を再確認する。新鮮な購入物への`finish`は拒否し、`premium.pause`を使う。再承認で同じ食事・スプーン・残量から再開する。ロック・承認切れでも、所有範囲の蓋処分・片付け・期限切れ廃棄は残す。

`affect_model`は`research-demo-v1`という合成の好みを使う。開封25/50/100%で各50の期待手掛かりを一度ずつ与える。一口の好みは既存の特徴一致度、満足は実際に食べたmgによる加重平均、不明な一口があれば総合値はnull。余韻は最後のvalenceを仮想60秒で減衰する。`rest_ms`は待機せず仮想時間だけ進め、食事revisionを更新する。読取・再送で余韻を進めず、購入期限にも影響しない。

ミルク/チョコは`milk-v1` / `chocolate-v1`の作者設定で、本体100g・ソース0。ソースを指定した一口は拒否する。チョコの苦味とカカオは本体由来として残す。色・音・動き・台詞そのものは生成せず、`expression_cues`とonset/middle/aftertasteの版付き演出係数を返す。いずれも人間と同じ感覚が実証された値ではない。

## 日次処理、片付け、復旧

常駐プロセスが毎日03:00 JSTを基準に期限廃棄を実行し、停止後は次の起動で追いつく。30秒ごとのtickで最大100件を処理し、100件なら次tickへ続ける。一個ずつ取引を確定し、済んだ在庫を再廃棄しない。要求時の期限ガードはバッチ停止中も有効。14日到達後は新しい消費承認を拒否して廃棄へ案内する。期限切れ試食の合成fixtureはJava/Python研究コアに分離し、美祥に実行しない。

片付け義務は食べ物と別に保存する。初回・90秒超のheartbeat欠落・時刻巻戻りは原因不明として自動違反を保留する。障害を確認した管理者だけがowner側`resume-cleanup-postgres`を`PUDDING_OPERATOR_REASON`付きで実行し、残る義務へ少なくとも10分の猶予を再設定する。通常の人間Bearerに全テナントの運用ポリシーを変更させない。同じ義務は要求時・日次の両方で一度だけ数え、審査済みの義務を再加算しない。片付け済みでも過去の違反を自動削除しない。

`python3 scripts/verify_java.py --postgres`は明示的に許可した空の試験DBだけで実行する。`scripts/verify_sandbox_recovery.py`はCIのPostgreSQLコンテナーを指定し、pg_dump→別DBへpg_restore→全テーブル/sequence一致→保存済み応答の再送で行が増減しないことを検証する。実ホストのバックアップ保管先・鍵の保管・再起動・トンネル/TLS証明書更新は個別の実機受入れが必要。

全ての金額と仕訳は模擬・審査待ち。正式会計システムへの送信機能はなく、期限廃棄だけで雑収入へ自動振替しない。`accounting.expiry`は人間が残る義務の消滅を確認した上でのみ候補を保存する。実契約・税区分・販売条件の確定は[会計設計](ACCOUNTING_AND_TAX.md)の残件。
