# ProPaint — Procreate 風ペイントアプリ (Jetpack Compose + OpenGL ES 2.0)

Android ネイティブの Jetpack Compose 製ペイントアプリケーション。
描画エンジンは OpenGL ES 2.0 FBO パイプラインで実装。

---

## 主な機能

### ブラシエンジン (7種)
| ブラシ | 特徴 |
|--------|------|
| **鉛筆** | ハードエッジ円形スタンプ、筆圧→サイズ/不透明度 |
| **筆 (Fude)** | SrcOver 混色スタンプ、水分量パラメーター |
| **水彩筆** | SrcOver 混色スタンプ、水分量・滲み効果 |
| **エアブラシ** | ソフトエッジ (hardness=0) 大型スタンプ |
| **マーカー** | GL_MAX α ブレンドで重ね塗り上限を制御 |
| **消しゴム** | Destination-out ブレンドでαチャンネルを削除 |
| **ぼかし** | キャンバスピクセルを lerp で置換 |

全ブラシ共通:
- Catmull-Rom スプライン補間でなめらかなパス
- 入り/抜きテーパー (smoothStep)
- スタンプ間隔補正 (SrcOver coverage 一定化)
- スタンプ半径に依らない dFdx/dFdy AA

### 筆圧対応
- `MotionEvent.getPressure()` / `getHistoricalPressure()` でフレーム間全ポイントを補間
- `TOOL_TYPE_ERASER` による消しゴム端の自動検出
- 筆圧 → サイズ / 筆圧 → 不透明度 の個別 ON/OFF と感度調整 (intensity 1–200)

### レイヤーシステム
- 複数レイヤー (追加・削除・複製・下に結合)
- 不透明度・ブレンドモード (通常 / 乗算 / スクリーン / オーバーレイ / 暗く / 明るく)
- 表示/非表示・ロック
- クリッピングマスク
- レイヤーフィルター (HSL / ガウスブラー / コントラスト＆明るさ)

### カラー
- HSV スライダー + カラーパレット (基本色 / スキン色)
- カラー履歴
- スポイトツール

### ギャラリー
- Procreate 風 2 列グリッド (1画面に約 8 件表示)
- サムネイル + タイトル + 更新日オーバーレイ

### インポート / エクスポート
| 形式 | 入力 | 出力 |
|------|------|------|
| PNG | ✅ | ✅ |
| JPEG | ✅ | ✅ (95%) |
| WEBP | ✅ | ✅ (lossless) |
| HEIC / HEIF | ✅ | — |
| PSD | ✅ (フラット化) | ✅ (レイヤー分割) |
| .ppaint | ✅ | ✅ (独自形式) |

- Storage Access Framework (SAF) によるファイル選択 (権限不要)
- 画像インポート後はドキュメントサイズ自動設定、レターボックス表示

### Undo / Redo
- ハイブリッド方式: 差分 (ストローク) + スナップショット (ピクセル) の二段階
- 最大 50 ステップ (ディスクキャッシュあり)

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
├── MainActivity.kt                 # エントリーポイント (イマーシブモード)
├── ProPaintApp.kt                  # Application クラス
├── model/
│   └── PaintModels.kt              # BrushType, BrushSettings, Stroke, PaintLayer, CanvasAction
├── viewmodel/
│   ├── PaintViewModel.kt           # 状態管理 + インポート/エクスポート + Undo/Redo
│   ├── HybridHistoryStack.kt       # undo/redo スタック
│   └── HistoryDiskCache.kt         # スナップショットのディスクキャッシュ
├── gl/
│   ├── CanvasGlRenderer.kt         # GLSurfaceView.Renderer: 3段階FBOパイプライン
│   ├── GlBrushRenderer.kt          # スタンプ配置 + 頂点バッファ生成 + GPU描画
│   ├── GlCanvasView.kt             # GLSurfaceView Compose ラッパー
│   ├── GlProgram.kt                # シェーダープログラム管理
│   ├── LayerFbo.kt                 # レイヤー FBO ラッパー
│   ├── Shaders.kt                  # GLSL ソース定数
│   └── PsdExporter.kt              # PSD 書き出し (レイヤー分割)
├── io/
│   ├── CanvasFileManager.kt        # PNG/JPEG/WEBP 保存, .ppaint 読み込み
│   ├── CanvasSerializer.kt         # .ppaint シリアライズ/デシリアライズ
│   └── PsdImporter.kt              # PSD デコード (v1, 8bit RGB/RGBA, PackBits)
└── ui/
    ├── theme/Theme.kt              # ダークテーマ
    ├── screens/
    │   ├── GalleryScreen.kt        # ギャラリー + インポートUI
    │   └── PaintScreen.kt          # メイン描画画面レイアウト
    └── components/
        ├── DrawingCanvas.kt        # GLSurfaceView + タッチ/スタイラス入力
        ├── Toolbar.kt              # TopBar (2行)
        ├── SideQuickBar.kt         # サイズ/不透明度クイックバー
        ├── BrushPanel.kt           # ブラシ設定パネル
        ├── ColorPickerPanel.kt     # カラーピッカー
        ├── LayerPanel.kt           # レイヤー管理パネル
        └── FilterPanel.kt          # レイヤーフィルターパネル
```

---

## アーキテクチャ

### 描画パイプライン (OpenGL ES 2.0)

```
タッチ/スタイラス入力
  → DrawingCanvas (pointerInteropFilter)
  → PaintViewModel (StrokePoint 追加, 混色サンプリング)
  → RenderSnapshot (AtomicReference でGLスレッドへ渡す)
  → CanvasGlRenderer.onDrawFrame()

onDrawFrame:
  1. updateLayerFbos()       — 差分ストロークを各レイヤーFBOへベイク
  2. buildCompositeCache()   — 全レイヤーを compositeCache FBOへ合成
  3. drawCacheToScreen()     — zoom/pan/rotation 付きレターボックス表示
```

### ドキュメントサイズとスクリーンサイズの分離

新規キャンバス: `docWidth = 0` (スクリーンサイズを使用)
インポート時: `docWidth/Height` に元画像サイズをセット

表示変換:
```
baseScale = min(screenW / docW, screenH / docH)   // レターボックスfit
finalScale = baseScale × userZoom
```

### ライブストローク α 管理

スタンプを直接レイヤーFBOに書くと1ストローク内でαが蓄積しopacity上限を超える問題がある。
これを避けるため3つのFBOを使う:

```
strokeSnapshotFbo  — ストローク開始時のレイヤーFBOのコピー
strokeMarksFbo     — 現在ストロークのスタンプ蓄積 (density×pressure alphaのみ)
liveStrokeFbo      — snapshot + marks を brush.opacity でキャップした合成結果
```

各フレームで `marks` に新スタンプを追加 → `rebuildLiveFromMarks()` で `liveStrokeFbo` を再合成。
→ 同一ストローク内でスタンプが何重に重なっても `brush.opacity` を超えてαが上がらない。

---

## 既知の問題 / 今後のリファクタリング

現在のブラシ実装には以下の設計上の問題がある。

### 🔴 優先度: 高

**1. 水彩・筆の混色が CPU-GPU 同期に依存している**

Fude/Watercolor ブラシの `StrokePoint.color` は ViewModel が `glReadPixels` → CPU でボックスブラー → StrokePoint に書き込みして生成している。
`glReadPixels` は GPU 完了を待つためフレームをブロックし、パフォーマンスが低下する。

**解決策:** 混色計算をフラグメントシェーダー内で完結させる。スタンプ描画時に現在の FBO を `uDst` としてバインドし、シェーダー内で `texture2D(uDst, vUV)` を読んで水彩混色を行う。`glReadPixels` を排除できる。

**2. `renderCspStamps` が肥大化し責任過多**

1メソッドが累積距離計算 / Catmull-Rom 補間 / テーパー計算 / 筆圧変換 / 色決定 / 頂点バッファ構築 / GPU 呼び出しをすべて担っている（130行）。ブラシ種別ごとの `if/when` 分岐が内部に散在し、新ブラシ追加時に既存コードを変更しなければならない。

**解決策:** `StampWalker`（パス上の座標・サイズ・圧力を計算） / `StampColorizer`（ブラシタイプごとの色・α決定 interface） / `StampBatcher`（頂点バッファ） / `StampRenderer`（blend 設定 + GPU 呼び出し）に分割する。

### 🟡 優先度: 中

**3. ストロークデータをメモリ上のポイントリストとして永続保持している**

`PaintLayer.strokes: List<Stroke>` はベイク後も削除されない。長時間描画すると RAM が増え続ける。

**解決策:** ベイク後は `strokes` を空にし、アンドゥ用に FBO のピクセルスナップショット（PNG 圧縮）を HistoryStack に積む方式に統一する。

**4. `renderBlurStamps` と `renderCspStamps` でコードが重複している**

スタンプ配置ロジック（Catmull-Rom、テーパー、筆圧計算）が2メソッドにコピーされている。

**解決策:** `StampWalker` として共通化し、コールバック内の処理のみ異なる設計にする。

**5. ライブ描画でも累積距離を全ポイント分計算している**

`renderCspStamps(fromSegIdx = N)` と呼ばれても `cumDist` は全点分 `FloatArray` を毎フレーム確保・計算している。

**解決策:** `applyExitTaper = false`（ライブパス）では `fromSegIdx` 以降の差分計算のみに縮小する。

### 🟢 優先度: 低

**6. `spacingCompensate` の数学的前提が一部崩れている**

SrcOver coverage 一定化の式を全ブラシに適用しているが、Marker は GL_MAX ブレンドであり前提が異なる。密度感が設定値と微妙にずれる。

**解決策:** ブラシタイプごとに blendMode と spacing 補正方式を明示的に宣言する。

**7. スタンプ形状が円形のみ**

`STAMP_FRAG` は `length(vUV - 0.5)` で円を描くだけ。筆らしい楕円スタンプやテクスチャスタンプがない。

**解決策:** シェーダーに `uAspect`（楕円比）と `uAngle`（進行方向傾き）を追加。将来的に `sampler2D uStampTex` でテクスチャスタンプも対応可能にする。

**8. `BrushSettings` に未使用フィールドがある**

`blurPressureThreshold` / `pressureMixEnabled` / `pressureMixIntensity` がモデルに定義されているが描画ロジックで参照されていない。削除または実装が必要。
