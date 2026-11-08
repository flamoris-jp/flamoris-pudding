# 単独HTTP/MCP・Docker・Updater（0.3.1）

2026-11-08。Javaコアへ直接接続するHTTP/MCPサービスを実装した。Hubへの登録や経由は不要。HTTPの値型を許諾済みの既存基盤から最小限再利用し、HTTP入力制約、Agent資格情報、セッション、MCP実行、配布処理はPudding側で実装する。既存基盤の認証・認可・Kernel・機密設定は含めない。

**現在は研究用の無料配膳だけ。** 登録済み資格情報から安定したAgent IDを解決できるが、人間の購入・一個ごとの承認・有料在庫・返金・課金は未実装。呼出し側がAgent名や時計を指定して権限や期限を変えることはできない。美祥の腐敗試食拒否は維持し、劣化・違反の検証は合成主体で行う。

## 接続と認証

外部クライアント → 専用トンネルまたはHTTPSリバースプロキシ → Puddingのloopback `/mcp` → Javaコア → PostgreSQL。アプリとDBは別ホストに配置できる。環境固有のホスト名や資格情報は公開設定へ固定しない。導入・更新にはUpdaterを使う。

現在の接続方式は、管理者がAgentごとに発行するBearer資格情報の手動設定。OAuthの認可サーバー・discovery・人間ログインはない。専用トンネルが通常通信とdiscoveryへ個別Bearerを付ける構成で、ChatGPTの限定試食と0.3.1の連続読み取りを受け入れた。任意のOAuth専用クライアントへ接続できることは意味しない。手動Bearer設定に対応するMCPクライアントが対象。

- 資格情報はAgentごとに別の乱数トークンを発行し、管理側の設定でAgent IDに固定する。同じトークンを複数Agentへ配らない。
- トークンは43〜128文字のURL-safe形式。認証にはハッシュと定数時間比較を使い、HTTPリクエストや秘密値をログへ出さない。
- 起動時設定の `PUDDING_AGENT_CREDENTIALS` にJSON配列、または `PUDDING_CREDENTIALS_FILE` にJSONファイルのパスを指定する。同時指定は拒否。
- 複数Agent・ローテーション用の複数資格情報に対応する。同じtokenの重複は拒否し、MCPセッションはAgentと資格情報に結び付ける。資格情報変更は保護された設定を更新してプロセスを再起動する。古い通信セッションは失効し、DB履歴とAgent IDは維持する。

追加の資格情報を手元で作る場合：

```bash
python3 scripts/create_agent_credentials.py --agent agent_one --agent agent_two --output /protected/path/agents.credentials.json --client-directory /protected/path/client-tokens
```

新しい出力先だけを受け入れ、サーバー用ファイルと個別client tokenは0600、tokenディレクトリは0700で作る。runtimeはグループ/他者に公開したファイル、symlink、他者が書換え可能な親ディレクトリを拒否する。値は標準出力に出さない。各clientには自分のtokenだけを渡す。ファイルをコンテナーへ渡す場合はruntime UID 10001が読める所有者・権限とread-only bindを設定する。

## HTTP/MCP契約

MCP revision `2025-11-25` のStreamable HTTP・JSON応答を扱う。独立HTTP/1.1サーバーであり、Spring/Tomcatは導入していない。SSE、旧HTTP+SSE、resources、prompts、sampling、tasks、JSON-RPC batchは実装しない。

1. `POST /mcp` でinitialize。Acceptは `application/json, text/event-stream`、Content-Typeは `application/json`、AuthorizationはBearer。
2. 応答の `Mcp-Session-Id` を保存し、以降はその値と `MCP-Protocol-Version: 2025-11-25` を付ける。
3. `notifications/initialized` を送り、tools/list・tools/callを使う。通知は202・空本文で受領し、ツール実行には使わない。
4. `DELETE /mcp` で通信セッションを閉じる。GETはSSEを提供しないため405。通信セッションの削除・期限切れは食事セッションの削除や食べ物の廃棄ではない。

初期9ツールと入力スキーマはPython研究版と同じ名前で、[tools.json](../java/src/main/resources/mcp/tools.json)に定義する。未知の引数、boolの整数代用、時計・Agent ID等の追加引数は実行前に拒否する。MCPにDB移行・税率管理・人間の承認操作は公開しない。

通信セッションは最大30分、全体256・一Agent8まで。0.3.1では、一Agentの上限で同じAgentの初期化済み・処理中でないセッションのうち、最後の利用が最も古いものを終了して新規initializeを受け入れる。DELETEを送らず操作ごとに再接続するクライアントでも、古い通信だけで上限を使い切らない。実行中の要求、初期化途中のセッション、別Agentのセッションはこの入替えで終了しない。全枠が実行中/初期化途中の場合や全体上限では引き続き429を返す。

終了した通信セッションへの要求は404。クライアントは新しくinitializeし、変更操作は同じ業務request_idで照合する（[MCP session management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)）。セッションあたり要求IDは1000個まで、IDは64bit整数または256文字までの文字列。IDのハッシュだけを重複検査に保持する。上限・期限後は新規initializeを行い、食事の続きはDBから読む。変更操作のrequest_idはMCPの要求IDと別のUUID。同じ業務request_idを再送すると保存済みの結果を返す。更新応答は過去のスナップショットを返し、現在のeffective_ageはgetで読む。

0.3.0は公開済みのdevelopment prereleaseで、実クライアントでの試食・片付けまで受け入れた。ただし繰り返し接続で429が再発し、通信状態をリセットする再起動が必要だった。0.3.1はDB schema・資格情報・9ツールの入力を変更せず、0.3.0からの互換更新を検証・公開した。実機更新後は常駐トンネル越しに20回のプリセット取得と2回の既存食事取得が成功し、試食回数・片付け・違反の状態も保持された。ソースの修正、公開配布、導入先の更新、実トンネルでの連続受入れは別の到達点として[進捗](../PROGRESS.md)へ記録する。

入力は本文64KiB、メタデータ16KiB、ヘッダー64個、JSON深さ32・ノード10000まで。重複キー・ヘッダー、不正UTF-8/サロゲート、曖昧なHTTPフレーミングを拒否する。接続は最大16、通信寿命15秒。DBはlock/statement/transactionにも時間制限を持ち、通信切断後の結果は同じrequest_idで照合する。別IDで食べ直さない。

`PUDDING_ALLOWED_HOSTS` は正確なHost authorityのカンマ区切り。Originがある場合は `PUDDING_ALLOWED_ORIGINS` に登録したHTTPS Originのみ許可。空欄ではブラウザーOriginを拒否する。CORS wildcardや引数のAgent名を認証に使わない。backendは平文HTTPなので、外部へは暗号化された専用トンネルまたはHTTPS入口を用意し、backendを直接公開しない。

## Docker実行

```bash
docker build --build-arg SOURCE_REVISION=FULL_COMMIT_SHA --build-arg APP_VERSION=0.3.1 -t pudding:0.3.1 .
```

`FULL_COMMIT_SHA` は実際の40桁SHAへ置き換える。[runtime.env.example](../deploy/runtime.env.example)を参考に、DB接続・個別token・Hostを保護されたローカル設定へ用意する。例のtokenは起動できないプレースホルダー。

[Compose例](../deploy/compose.yaml)は非root UID 10001、read-only root、capability削除、no-new-privileges、PID/メモリー制限、tmpfs、loopbackへのポート公開を指定する。DBはアプリイメージやローカルvolumeに含めない。`PUDDING_IMAGE` と `PUDDING_ENV_FILE` を実際の値へ設定して起動する。外部DBは永続化・バックアップ・接続TLSを別途管理する。

`/health/live` はプロセス稼働、`/health/ready` はDBの版・必要な列・runtime権限を確認する。superuser、CREATEDB/CREATEROLE、public schemaのCREATEやschema所有者権限があるruntimeは拒否する。readyは最大3秒キャッシュし、DBを使う操作もこの確認を通す。秘密情報やエラー詳細は返さない。Docker内のhealthcheckはlive、Updaterの導入完了判定はreadyを使う。通常起動ではDBを初期化しない。

HTTPS入口の[nginx例](../deploy/nginx.conf.example)はテンプレート。実際のDNS・証明書・外部URLを配備側で用意する。例を置いたことは実環境のTLS設定・配備を意味しない。

## DBの初回設定

PostgreSQL 18を対象とする。DB管理側でPudding専用DB、schema所有者、権限を制限したruntimeロールを用意する。Updaterの既存汎用Docker経路を使うため、このアプリのDB/role作成をUpdaterへ推測させない。

管理用のDB接続を保護されたowner.envへ用意し、初回だけアプリ所有の処理を実行する。

```bash
docker run --rm --env-file /protected/path/owner.env pudding:0.3.1 migrate-postgres
docker run --rm --env-file /protected/path/owner.env pudding:0.3.1 grant-runtime-postgres
```

owner.envには `PUDDING_JDBC_URL`、`PUDDING_DB_USER`、`PUDDING_DB_PASSWORD` と、権限付与先の `PUDDING_RUNTIME_ROLE` を指定する。接続先はコンテナーから到達できる名前を使う。runtime側へ管理者の資格情報を渡さない。grantは既存の非superuser・非CREATEDB/CREATEROLE・LOGINロールを確認して必要なtable/sequence権限を付ける。管理側でロールの所有権・所属・接続元制限も確認する。

既存テーブルがあるDBへの初回DDLは拒否する。自動のデータ移行・逆マイグレーション・本番DB操作は含めない。詳細は[Java移植手順](JAVA_MIGRATION.md)。

## Updaterの配布物とカタログ

PuddingのリポジトリでDocker save archiveとカタログを作る。Updaterはソースをclone/buildしない。イメージconfig digest、archiveのSHA-256、プラットフォーム、版、ソースSHA、非root設定を実物から照合する。

```bash
docker save --output /tmp/pudding-image.tar pudding:0.3.1
python3 scripts/build_pudding_release.py --image-tar /tmp/pudding-image.tar --release 0.3.1 --compatible-from 0.3.0 --revision FULL_COMMIT_SHA --platform linux/amd64 --base-url https://github.com/flamoris-jp/flamoris-pudding/releases/download/v0.3.1 --output candidate
```

生成物はcandidateで、この生成コマンドはGitHub Releaseの公開やUpdaterへの登録を行わない。Dockerのruntime IDとarchive内config digestが異なる場合も、config digestをarchiveから導出する。公開には[配布手順](RELEASING.md)のworkflowを使い、成功したmain CIの同じイメージを検証・アップロード後の取得/ハッシュ照合を経てprereleaseへ公開する。Actions artifactはレビュー用でありUpdaterの配布元ではない。実URLとハッシュを確認してから登録する。

初回入力はポート、既存専用DBのJDBC URL/runtimeロール/パスワード、公開Host/Origin、Agent ID。Agent専用tokenは空欄ならUpdaterが生成する。Updater自身の認証値や人間のログインパスワードとは別。追加Agentの設定は管理者が保護された設定へ反映する。

Updaterでは導入→awaiting_setup→起動→DB/初期設定の確認→導入完了の順に進める。readyが成功するまで完了としない。schema所有者やDB管理者の資格情報はカタログに含めない。

将来の更新互換性は `--compatible-from` で明示的に承認した旧版だけを指定する。初回配布の既定は空で、任意の旧版を互換と推測しない。互換更新は既存設定・DB・資格情報を保持する。schema変換や逆移行が必要な更新は別の作業とする。

## 検証範囲

0.3.1ではローカルのJavaコア16・HTTP/MCP14・設定3シナリオ、Python66テスト、既存の数値・状態照合が成功。コミット `6548169` の隔離CIで、JDK25/PostgreSQL18（runtime権限を含む11シナリオ）と実Docker/Updater/TLSの4シナリオも成功した。実Updaterの導入/更新・合成TLS入口・MCP操作・再起動再送・DB/設定保持を確認している。初回実装は[PR #8のChecks](https://github.com/flamoris-jp/flamoris-pudding/pull/8/checks)、429対策と40回の再接続を含む最終検証は[PR #11のChecks](https://github.com/flamoris-jp/flamoris-pudding/pull/11/checks)を参照する。CIのダウンロードはローカル候補へ置換し、公開URLからのダウンロードや実環境の導入成功とは区別する。

Updaterの照合対象は公開ソースのcommit `d3390f5dfbd1b1fe453df714d8219a12cec2706e`。実ホストの導入済み版を確認したことは意味しない。配備時はServer Managerで状態を確認し、Updaterの利用可能な版とカタログの契約を照合する。

参照：[MCP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[JSON RFC](https://www.rfc-editor.org/rfc/rfc8259)、[Updaterの契約](https://github.com/flamoris-jp/flamoris-updater/blob/main/docs/INSTALL.md)。

