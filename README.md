# ProPaint v2 — Procreate 風ペイントアプリ (Jetpack Compose + OpenGL ES 2.0)

Android ネイティブの Jetpack Compose 製ペイントアプリケーション。
v2 では描画エンジンをタイルベース CPU レンダリングに全面刷新し、UI を Procreate 風に再設計。

---

## v1 → v2 主な変更点

### 描画エンジンの全面刷新

| 項目 | v1 | v2 |
|------|----|----|
| **レンダリング方式** | GPU (FBO) 直接描画 | CPU タイルベース + GPU は表示のみ |
| **タイル構造** | なし (全画面 FBO) | 64×64 Premultiplied ARGB タイル / Drawpile 互換 |
| **メモリ管理** | FBO 固定確保 | COW (Copy-on-Write) 参照カウント + null タイル (透明領域ゼロコスト) |
| **ブレンド演算** | GLSL シェーダー | CPU IntArray 演算 (SrcOver / Erase / 12 ブレンドモード) |
| **混色ブラシ** | `glReadPixels` → CPU → GPU 往復 | CPU タイル直接サンプリング (GPU 同期不要) |
| **ダブマスク** | GPU 円形スタンプのみ | LUT ベース DabMaskGenerator (hardness 0–1 可変) |
| **ダーティ追跡** | フレームごと全レイヤー再合成 | DirtyTileTracker (タイル単位差分更新) |
| **レイヤー合成キャッシュ** | なし | タイル単位キャッシュ (変更タイルのみ再合成) |

### 選択範囲システム (新規)

- ピクセルレベル選択マスク (0–255 グラデーション対応)
- 4 種の選択ツール: 矩形 / 自動選択 / ペン / 選択消し
- 全選択 / 反転 / 解除
- 選択範囲の可視化: 青オーバーレイ + マーチングアンツ
- 描画時は選択範囲外を自動マスク

### ブラシエンジン

- 9 種類: 鉛筆 / バイナリペン / 筆 / 水彩筆 / エアブラシ / マーカー / 消しゴム / ぼかし / 塗りつぶし
- Direct / Indirect (Wash) レンダリングパスの適切な分離
- Catmull-Rom スプライン補間 + 入り抜きテーパー
- HistoricalEvent 完全対応による高速ストローク精度向上

### ブレンドモード (12 種)

通常 / 乗算 / スクリーン / オーバーレイ / 暗く / 明るく / 加算 / 減算 / ソフトライト / ハードライト / 差の絶対値 / マーカー

### レイヤー機能強化

- クリッピングマスク / アルファロック
- レイヤー左右・上下反転
- ストローク中のレイヤー切替によるデータ破損を `strokeLayerId` で防止 (v1 バグ修正)
- Indirect サブレイヤーの強制マージ (v1 リーク修正)

### オートセーブ & クラッシュ復旧

- Procreate 方式: 生タイルデータをストレージに定期保存
- 起動時に復旧ダイアログを表示
- PNG 圧縮なしで高速保存 (数百 ms)

### プロジェクト管理 & ギャラリー

- ZIP ベースプロジェクト保存 (`.propaint` 形式)
- ギャラリー画面: サムネイル付きグリッド表示
- プロジェクトの新規作成 / 読込 / 名前変更 / 削除

### メモリ管理

- MemoryWatcher: 使用率 70% 超で Undo スタック縮小、85% 超で GC + キャッシュクリア
- COW タイルにより大キャンバスでも効率的なメモリ使用

### UI 刷新 (Procreate 風)

- Flutter プロトタイプからコンバートした Procreate 風デザイン
- 薄い上部バー (左: 設定・Undo・Redo / 右: ツールアイコン群 + カラーサークル)
- 左サイドミニマルスライダー (ブラシサイズ / 不透明度)
- AnimatedVisibility スライドインパネル
- 2 本指タップ → Undo / 3 本指タップ → Redo / 長押し → スポイト
- UiScale システムによる 150% タブレット向け拡大対応
- 縦置き・横置き両対応 (`fullSensor`)

### v1 重大バグ修正

| バグ | 原因 | v2 での対策 |
|------|------|-------------|
| 混色ブラシの色がくすむ | premultiplied alpha 二重適用 | PixelOps で統一検証 |
| ぼかしツールがブラシ色を混入 | smudge < 1.0 で描画色が混入 | smudge=1.0 固定 (キャンバスピクセルのみ参照) |
| レイヤー切替でストロークが別レイヤーに書かれる | 描画中のレイヤー参照未固定 | strokeLayerId で開始レイヤーに強制マージ |
| Indirect サブレイヤーがリーク | endStroke 未呼び出しケース | レイヤー変更時に強制 endStroke + クリーンアップ |
| ぼかしが選択範囲外に漏れる | 選択マスク未考慮 | ブラシエンジンで選択マスク適用 |

---

## 主な機能

### ブラシエンジン (9 種)
| ブラシ | 特徴 |
|--------|------|
| **鉛筆** | ハードエッジ円形スタンプ、筆圧→サイズ/不透明度 |
| **バイナリペン** | 二値 (1bit) エッジ、アンチエイリアスなし |
| **筆 (Fude)** | CPU 混色、色伸びパラメーター |
| **水彩筆** | CPU 混色、水分量・滲み効果 |
| **エアブラシ** | ソフトエッジ (hardness=0) 大型スタンプ |
| **マーカー** | GL_MAX α ブレンドで重ね塗り上限を制御 |
| **消しゴム** | Destination-out ブレンドで α チャンネルを削除 |
| **ぼかし** | キャンバスピクセルをガウスブラーで置換 |
| **塗りつぶし** | 許容値指定のフラッドフィル |

### 筆圧対応
- `MotionEvent.getPressure()` / `getHistoricalPressure()` でフレーム間全ポイントを補間
- `TOOL_TYPE_ERASER` による消しゴム端の自動検出
- 筆圧 → サイズ / 不透明度 / 混色 の個別 ON/OFF

### レイヤーシステム
- 複数レイヤー (追加・削除・複製・下に結合)
- 不透明度 + 12 ブレンドモード
- 表示/非表示・ロック・クリッピングマスク
- レイヤー左右・上下反転
- レイヤーフィルター (HSL / ガウスブラー / 明るさ＆コントラスト)

### 選択範囲
- 矩形選択 / 自動選択 (許容値指定) / ペン選択 / 選択消し
- 全選択 / 反転 / 解除
- 追加モード (選択範囲の合成)
- 選択範囲内のみ描画 (マスク機能)

### カラー
- HSV カラーホイール (色相リング + SV スクエア)
- カラー履歴
- スポイトツール (長押しジェスチャー)

### インポート / エクスポート
| 形式 | 入力 | 出力 |
|------|------|------|
| PNG | ✅ | ✅ |
| JPEG | ✅ | ✅ (95%) |
| WEBP | ✅ | ✅ |
| PSD | — | ✅ (レイヤー分割) |
| .propaint | ✅ | ✅ (独自形式) |

### Undo / Redo
- タイルベーススナップショット方式
- 2 本指タップ → Undo / 3 本指タップ → Redo

---

## ビルド手順

### 前提条件
- Android Studio Hedgehog (2023.1.1) 以上
- JDK 17
- Android SDK 34 / minSdk 26

### ビルド＆実行

```bash
./gradlew assembleDebug
./gradlew installDebug
```

---

## プロジェクト構成

```
app/src/main/java/com/propaint/app/
├── MainActivity.kt                 # ペイント画面エントリーポイント
├── GalleryActivity.kt              # ギャラリー画面
├── engine/
│   ├── Tile.kt                     # 64×64 COW タイル (Drawpile 互換)
│   ├── CanvasDocument.kt           # タイルベースレイヤーモデル
│   ├── BrushEngine.kt              # ダブ配置・Catmull-Rom 補間・混色
│   ├── DabMask.kt                  # LUT ベースダブマスク生成
│   ├── PixelOps.kt                 # CPU ブレンド演算 (12 モード)
│   ├── SelectionMask.kt            # ピクセルレベル選択マスク
│   ├── FillTool.kt                 # フラッドフィル
│   ├── GaussianBlur.kt             # ガウスブラーフィルター
│   ├── DirtyTileTracker.kt         # タイル差分追跡
│   ├── FileManager.kt              # PNG/JPEG/WEBP/PSD 入出力
│   ├── PsdWriter.kt                # PSD エクスポート
│   ├── AutoSaveManager.kt          # オートセーブ & クラッシュ復旧
│   ├── CanvasProjectManager.kt     # ZIP ベースプロジェクト管理
│   ├── MemoryWatcher.kt            # メモリ監視 & 自動キャッシュ縮小
│   └── DebugConfig.kt              # 構造化診断ログ設定
├── viewmodel/
│   └── PaintViewModel.kt           # 状態管理 + タッチ入力 + ジェスチャー
├── gl/
│   ├── CanvasRenderer.kt           # GLSurfaceView.Renderer (表示専用)
│   ├── GlCanvasView.kt             # GLSurfaceView Compose ラッパー
│   ├── GlProgram.kt                # シェーダープログラム管理
│   └── Shaders.kt                  # GLSL ソース定数
└── ui/
    ├── UiScale.kt                  # 150% タブレットスケーリング
    ├── theme/Theme.kt              # ダークテーマ
    ├── screens/
    │   └── PaintScreen.kt          # メイン描画画面レイアウト
    └── components/
        ├── Toolbar.kt              # Procreate 風上部バー
        ├── SideQuickBar.kt         # サイズ/不透明度縦スライダー
        ├── BrushPanel.kt           # ブラシ設定パネル
        ├── ColorPickerPanel.kt     # HSV カラーホイール
        ├── LayerPanel.kt           # レイヤー管理パネル
        ├── SelectionPanel.kt       # 選択範囲パネル
        ├── FilterPanel.kt          # レイヤーフィルターパネル
        ├── NewCanvasDialog.kt      # 新規キャンバスダイアログ
        └── ResizeCanvasDialog.kt   # キャンバスリサイズダイアログ
```

---

## アーキテクチャ

### 描画パイプライン (CPU タイルベース + GPU 表示)

```
タッチ/スタイラス入力
  → PaintViewModel (座標変換 + ジェスチャー判定)
  → BrushEngine (Catmull-Rom 補間 → ダブ配置 → タイル書込)
  → DirtyTileTracker (変更タイル座標を記録)
  → CanvasRenderer.onDrawFrame()
      1. drainDirtyTiles()    — 変更タイルのみ GPU テクスチャ更新
      2. compositeToScreen()  — 全レイヤーを zoom/pan/rotation 付き表示
      3. drawSelectionOverlay() — 選択範囲マーチングアンツ描画
```

### タイルベースメモリモデル

```
Layer
  └── tiles: HashMap<Long, Tile>     // key = packXY(tx, ty)
        └── Tile
              ├── pixels: IntArray(4096)  // 64×64 ARGB premultiplied
              └── refCount: AtomicInteger  // COW 参照カウント

null エントリ = 完全透明タイル (メモリゼロ)
refCount > 1 のタイルは書込前にクローン
```

### Indirect (Wash) ストローク

```
ストローク開始:
  snapshotTiles = activeLayer.tiles.deepCopyRefs()  // COW で軽量コピー
  sublayer = 新規空タイルセット

ダブ配置:
  sublayer にスタンプを蓄積

フレームごと:
  activeLayer.tiles = snapshot + sublayer を opacity キャップ合成

ストローク終了:
  snapshotTiles 解放, sublayer を正式マージ
```

---

## 既知の問題 / 今後の改善

### 優先度: 高
1. **スタンプ形状が円形のみ** — 楕円・テクスチャスタンプ未対応
2. **手ぶれ補正** — スタビライザーパラメーターは存在するが補間精度に改善の余地あり

### 優先度: 中
3. **PSD インポート未対応** — エクスポートのみ対応
4. **レイヤーサムネイル未対応** — レイヤーパネルにプレビュー画像がない
5. **テキストツール未対応**

### 優先度: 低
6. **Marker ブレンドの spacing 補正** — GL_MAX 前提と SrcOver 前提の式が混在
7. **4K 超キャンバスの性能最適化** — タイル数増加時のイテレーション最適化
