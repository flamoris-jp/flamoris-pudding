# 検証済みイメージの配布

2026-11-09更新。単独サービスのdevelopment prereleaseを、成功したmain CIの候補から公開する工程。現在の対象は `linux/amd64`。アプリの実機導入・DB作成・HTTPS設定・Updaterへのカタログ登録は別の操作である。

## 公開済みの版と次の公開

| 版 | 確認した到達点 |
|---|---|
| 0.3.0 | 初回のdevelopment prerelease、Updater導入、専用トンネルで試食と片付け |
| 0.3.1 | 通信セッション429対策、0.3.0からのUpdater互換更新、常駐トンネル22回の連続読み取り |
| 0.4.0 | sandbox追加を含むdevelopment prerelease公開、schema 1で0.3.1から互換更新、6プリセット・12回の連続読み取り。実機sandboxは未有効 |

0.4.0は公開済み0.3.1の実イメージを取得/ハッシュ照合し、隔離CIと実機でschema 1のままUpdater更新を確認した。sandboxのschema 2移行は互換更新へ含めない。[公開run](https://github.com/flamoris-jp/flamoris-pudding/actions/runs/42597131103)はverify/publishとも成功した。

0.3.0・0.3.1・0.4.0のタグ/Releaseは公開済み。以下の公開操作を同じ版へ繰り返さない。次の公開ではアプリ版と候補生成の設定を更新し、互換性を検証した旧版だけを指定してmain CIを通す。文書だけの更新はアプリの再公開・再配備を必要としない。現在の配布結果は[進捗](../PROGRESS.md)を参照。

## ソースと候補の対応

`.github/workflows/release.yml` は同じ40桁ソースSHAについて、mainへのpushで実行された次の3本の最新runがすべて成功していることを確認する。

- `ci.yml`：Pythonの単体・プロセス検証。
- `java.yml`：Javaコア、数値照合、実PostgreSQL。
- `standalone.yml`：実Docker/Updater導入・更新・HTTPS MCP。

他リポジトリ・PR・別ブランチのrunは採用しない。新しい失敗や再実行中のrunがあれば、古い成功で代用しない。候補は選択したstandalone runの `pudding-standalone-candidate-SOURCE_SHA` から取得する。公開時にイメージを再ビルドしない。

`prepare_pudding_release.py` はarchive内の実イメージID、版、ソース、runtime設定とカタログを照合する。カタログを同じ入力から再生成し、URL・ハッシュ・設定の変更も検出する。受入れる候補はイメージ、カタログ、候補の由来だけで、余分な設定ファイル、symlink、重複JSONキーを拒否する。

出力は同一バイト列の `linux-amd64--image.tar`、`catalog.json`、`candidate.json` と、それらの `SHA256SUMS`。Release本文も生成する。`candidate.json` の `published: false` は元のビルド候補の記録であり、公開や稼働の状態を表すフラグではない。

## 公開せずに確認する

mainの3本のCIが完了してから実行する。GitHub CLIは認証済みの通常ユーザーで使う。

```bash
gh auth status
gh workflow run release.yml --repo flamoris-jp/flamoris-pudding --ref main
```

既定の手動実行は検証済みartifactを作るだけで、Releaseやタグを作成しない。PRでもmain側の実際の候補を取得して同じ読み取り検証を行う。書込み権限を使うpublish jobはタグpush、またはmainの手動実行で明示的に `publish=true` を選んだ場合だけ動く。

mainの検証済み候補からタグも作成して公開する場合：

```bash
gh workflow run release.yml --repo flamoris-jp/flamoris-pudding --ref main -F publish=true
```

この場合も3本のmain CI成功と実物照合を先に行い、そのコミットへ新規タグを作る。既存タグを移動・上書きしない。

## development prereleaseを公開する

アプリの `java/src/main/resources/pudding-version.txt` と同じ未公開版のタグを、mainのCIが成功したコミットへ付けてpushする。例の `NEXT_VERSION` は検証済みの未公開版、`FULL_MAIN_COMMIT_SHA` は選択した実際の40桁SHAへ置き換える。

```bash
git tag vNEXT_VERSION FULL_MAIN_COMMIT_SHA
git push origin vNEXT_VERSION
```

タグpushで次の順に実行する。

1. タグの版、ソースSHA、main CI、元の候補を確認する。
2. 検証済みの4ファイルをdraft prereleaseへアップロードする。
3. GitHubから4ファイルを取得し直し、手元のmanifestとの一致と全ファイルのSHA-256を確認する。
4. タグが同じコミットを指していることを再確認して公開する。Latestには設定しない。

同じ版のReleaseを上書きする処理はない。アップロード後の検証に失敗した場合はdraftが残り、公開へ進まない。失敗原因を確認し、必要に応じてその未公開draftを整理して再実行する。

Actions artifactは7日保持。候補が失効・削除されている場合は公開を止める。選択したmainコミットのCIを再検証し、実際に存在する候補を使う。候補を手元で差し替えたり、成功していない別runを採用したりしない。

## 公開後のUpdater登録

公開済み0.4.0のversion付きカタログURL：

`https://github.com/flamoris-jp/flamoris-pudding/releases/download/v0.4.0/catalog.json`

0.4.0のカタログは `compatible_from=["0.3.1"]` を明示する。実機では設定ファイル保持・readiness・連続読み取りを確認し、既存食事の再照合は残件としている。DB状態/再送保持は隔離CIで検証済み。新規導入と互換更新は別操作であり、既存DBへ初回DDLを再実行しない。別の版を公開した場合は、その版の実ファイル取得とハッシュを確認してから登録する。

prereleaseをLatestのURLで代用しない。DB/runtimeロールとHTTPS入口は[単独サービス手順](STANDALONE_SERVICE.md)に従って用意する。この公開工程はサーバーの設定やDBを変更しない。

参照：[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)、[run download](https://cli.github.com/manual/gh_run_download)、[workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)。

