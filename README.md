# Parent-Android-Remote-Control

離れて暮らす親のスマートフォン操作を、家族がリモートでサポートするためのアプリです。

## 概要

ITに不慣れな親のAndroid端末の画面を、家族（子）の端末からリアルタイムに閲覧し、
タップ・スワイプ・文字入力を代わりに操作できます。電話で操作方法を説明する代わりに、
実際に画面を見ながらリモートで操作できることを目指しています。
（監視・ペアレンタルコントロール用途ではなく、あくまで「リモートサポート」ツールです）

## 主な機能

- 親端末の画面をリアルタイムに子端末へミラーリング（Android MediaProjection使用）
- 子端末から親端末へのタップ・スワイプ・文字入力の送信
- 親端末の通信状態（Wi-Fi等）を子端末側に表示
- 操作フィードバック用のタップ位置マーカー表示
- Windows向けPCビューワークライアント（C#/WPF）にも対応

## 動作環境

- 親側: Android（Accessibility Service権限が必要）
- 子側: Android または Windows 10以降（PCクライアント）
- 通信: Tailscale経由のP2P接続

## セットアップ

1. リポジトリをclone
2. Android Studioで `app/` を開いてビルド
3. PCクライアントを使う場合は `pc-client/FamilyRemote.PcClient.sln` を Visual Studio / `dotnet` でビルド

## 注意事項

- `FLAG_SECURE` が設定されたアプリ（銀行アプリ等）は仕様上、子端末側で黒画面表示となります
