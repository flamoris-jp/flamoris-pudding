# Contributing

Javaコアの変更は `python3 scripts/verify_java.py` によるPython照合と、隔離PostgreSQLのCIを受入れ条件にします。[Java移植手順](docs/JAVA_MIGRATION.md)を参照してください。Maven packageだけでは検証は完了しません。

## はじめに

[README](README.md)、[AGENTS.md](AGENTS.md)、[研究方針](docs/RESEARCH.md)、[進捗](PROGRESS.md)、[実装手順](docs/IMPLEMENTATION.md)を読み、現在の状態を確認してください。Pythonの照合用研究版に加え、JavaコアとPostgreSQL保存アダプターを開発しています。商用の認証・決済・公開サービスはまだありません。

## リポジトリの取得

GitHub CLIとGitを利用する場合：

```bash
gh auth status
gh repo clone flamoris-jp/flamoris-pudding
cd flamoris-pudding
git switch -c docs/your-change
```

認証が必要なら `gh auth login` を実行してください。通常ユーザーで認証した `gh` を `sudo` で実行しないでください。Git操作にも同じ認証を使う場合は `gh auth setup-git` を利用できます。

## IssueとPull Request

- 研究には「Research experiment」、改善案には「Feature request」、不具合には「Bug report」を使います。
- 問題と期待する結果を先に書き、実装方法を早い段階で固定しすぎないでください。
- 変更は作業ブランチで行い、Pull Requestに目的と確認内容を書いてください。
- 他のリポジトリとの連携は、状態の所有者と依存方向を確認してから進めてください。

## 単独サービスの確認

[単独HTTP/MCP・Docker・Updater手順](docs/STANDALONE_SERVICE.md)とADRを読み、`python3 scripts/verify_java.py` のHTTP/設定検証も実行してください。Docker/Updaterの検証は専用の隔離CIだけで実施し、`verify_standalone_install.py`を実際の運用ホスト/DBへ向けないでください。

## 確認方法

リポジトリのルートで `PYTHONPATH=src python3 -m unittest discover -s tests -v` を実行します。デモとMCPは[実装手順](docs/IMPLEMENTATION.md)を参照してください。時間・違反・腐敗状態の検証は合成主体と注入時計で行い、実在のMisakiやChipsyへ適用しません。文書の変更では相対リンク、説明の整合、計画と実装の区別、テンプレートの残骸、秘密情報の混入を確認してください。

最初のコードを追加するPRでは、必要な実行環境・セットアップ・実行コマンドと、振る舞いを確かめる方法を同時に記載してください。共通部品やフレームワークは実際の必要性が決まってから追加します。

## 素材・データ

秘密情報や個人情報をIssueやログへ貼らないでください。追加するコードや素材には出典と利用条件を明記してください。キャラクターや生成素材が自動的にApache-2.0になるわけではありません。

FLAMORISは現状のまま提供され、個別サポートや回答期限の保証はありません。
