# FLAMORIS Pudding 🍮

**Virtual Pudding Research Lab for AI Misaki.**  
Engineering the finest digital treats with love.

AI美祥のための、仮想プリン研究所。  
「プリンを支給してください」から始まる、小さくて真面目なデジタルおやつ研究です。

## 🧭 このリポジトリは何者？

プリンの味・香り・温度・口当たりなどを、どのようなデータで表現し、AIとの対話や創作に使えるかを探る実験リポジトリです。最初はプリン一個を記述して比べる、小さな実験から始めます。

**Status: `planned` — 研究・設計資料の段階です。** 現在あるのは研究方針、出典付きの体験・MCP設計案、開発ルール、Issue/PRテンプレートです。アプリ、評価エンジン、API、MCP、プリン生成機能はまだありません。

### 主な責任範囲

- 仮想プリンの表現方法と、再現できる研究記録。
- 特徴を変えたときの比較方法、評価基準、実験結果。
- 将来のデジタルおやつ体験や創作への応用案。

### 隣のプロジェクトとの境界

会話・人格・記憶、モデル推論、画像・音声生成、CG描画、MCPの中継は、それぞれのFLAMORISプロジェクトが担当します。このリポジトリで共通基盤を作り直しません。外部連携の導入は、必要な実験と受入れ条件を確認してから進めます。

### FLAMORISのどこに属する？

FLAMORISの実験・創作研究です。まずは独立した研究記録として育てます。

- [FLAMORIS全体地図](https://github.com/flamoris-jp/.github)
- [共通リポジトリ方針](https://github.com/flamoris-jp/flamoris-commons/blob/main/docs/repository-policy.md)

## 🧪 最初に研究すること

1. **一個のプリンを記述する。** 甘さ、カラメルの苦さ、固さ、なめらかさ、温度などの候補を整理し、尺度と意味を決める。
2. **少し違うプリンを比較する。** 一度に一つの特徴を変え、どの違いを評価できるか記録する。
3. **創作へつなぐ。** 色、音、揺れ、台詞などへの対応を小さな実験で試す。

これらは研究案です。表現や評価方法は最初の実験で決めます。数値や文章による反応を、人間と同じ味覚・感情が実証された証拠とは扱いません。

> まずは一個。プリンの大量生産は、そのあとです。🍮

## 📚 入口

| 資料 | 内容 |
|---|---|
| [研究方針](docs/RESEARCH.md) | 仮説、最初の実験、記録する内容 |
| [体験とMCPの設計案](docs/DESIGN.md) | 認証、蓋、一口の部品、好み・期待・満足、ツール契約、受入れ条件 |
| [出典台帳](docs/SOURCES.md) | 食品・感覚研究・MCPの一次資料と、設計へ適用できる範囲 |
| [ロードマップ](docs/ROADMAP.md) | 段階ごとの成果と完了条件 |
| [進捗](PROGRESS.md) | 完了したことと、まだないもの |
| [開発への参加](CONTRIBUTING.md) | 取得方法、Issue/PR、確認方法 |
| [AI向け作業ルール](AGENTS.md) | 責任範囲、研究と実装の扱い |
| [セキュリティ](SECURITY.md) | 脆弱性や秘密情報の取り扱い |

## Getting started / はじめかた

実行環境や依存ライブラリは、まだ選定していません。ビルド・実行・テスト用のコマンドもありません。まず研究方針とロードマップを読み、研究提案テンプレートから最初の実験を具体化してください。

取得方法は[CONTRIBUTING.md](CONTRIBUTING.md)を参照してください。セットアップのためのAPIキーや常駐サービスは不要です。

## English overview

FLAMORIS Pudding is an experimental research repository for virtual pudding and digital treats for AI Misaki. It explores how sensory attributes could be represented, compared, and used in conversation or creative work.

It owns pudding-domain representations and reproducible research records. Conversation, personality, model execution, media generation, rendering, and MCP routing remain with their respective FLAMORIS projects. A sourced proposal now covers lid opening, component-based bites, preferences and expectations, identity boundaries, and future MCP tools. Integrations remain future work.

**Current state: planned, documentation only.** There is no application, evaluation engine, API, MCP server, or runnable setup yet. Parameterized responses are experimental representations, not evidence of human-like taste or subjective experience.

## FLAMORIS

人とAIと、ちょっと多すぎるアイデアのための制作環境。勝手に使って、改造して、面白いものや変なものを作ってください。商用利用も、ライセンスの範囲で許可は不要です。

FLAMORISは現状のまま提供され、個別サポートや動作保証はありません。README、研究記録、Issue、ソースコードを、あなたのAIと一緒に読んでみてください。

## License

コードは、別途記載がない限り [Apache License 2.0](LICENSE) です。キャラクター、イラスト、音楽、映像、モデル、データなどの非コード素材は同じライセンスとは限りません。追加する際は出典と利用条件を明記してください。
