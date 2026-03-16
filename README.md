# ProPaint - Procreate風ペイントアプリ (Jetpack Compose)

Android ネイティブの Jetpack Compose 製ペイントアプリケーション。

---

## 主な機能

### ブラシエンジン (8種)
- **鉛筆** / **ペン** / **マーカー** — 筆圧対応の基本ブラシ
- **エアブラシ** — ソフトなスプレー効果
- **水彩** — にじみと透明感
- **クレヨン** — テクスチャ感のある描画
- **書道** — 角度で太さが変わるペン
- **消しゴム** — BlendMode.Clear で消去

### 筆圧対応
- Android `MotionEvent.getPressure()` から直接取得
- S Pen・スタイラスペンの筆圧をネイティブに反映
- `MotionEvent.getHistoricalPressure()` でフレーム間の全ポイントを補間
- `TOOL_TYPE_ERASER` による消しゴム端の自動検出
- 筆圧 → サイズ / 筆圧 → 不透明度 の個別ON/OFF

### レイヤーシステム
- 複数レイヤー（追加・削除・複製・結合）
- レイヤー不透明度・ブレンドモード（通常/乗算/スクリーン/オーバーレイ/暗く/明るく）
- 表示/非表示・ロック

### カラー
- HSV スライダー
- パレット（基本色・スキン色）
- カラー履歴

### Undo/Redo
- 最大50ステップの履歴管理

---

## ビルド手順

### 前提条件
- Android Studio Hedgehog (2023.1.1) 以上
- JDK 17
- Android SDK 34

### ビルド＆実行

```bash
cd propaint_compose

# Android Studio で開く
# または コマンドラインでビルド:
./gradlew assembleDebug

# デバイスに直接インストール:
./gradlew installDebug

# adb 経由:
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## プロジェクト構成

```
app/src/main/java/com/propaint/app/
├── MainActivity.kt              # エントリーポイント（イマーシブモード）
├── ProPaintApp.kt               # Application クラス
├── model/
│   └── PaintModels.kt           # BrushType, Stroke, Layer, CanvasAction
├── viewmodel/
│   └── PaintViewModel.kt        # 状態管理 + Undo/Redo
├── engine/
│   └── StrokeRenderer.kt        # Canvas描画エンジン（全ブラシ筆圧対応）
└── ui/
    ├── theme/Theme.kt            # ダークテーマ
    ├── screens/PaintScreen.kt    # メイン画面レイアウト
    └── components/
        ├── DrawingCanvas.kt      # Canvas + MotionEvent筆圧入力
        ├── Toolbar.kt            # トップツールバー
        ├── BrushPanel.kt         # ブラシ設定パネル
        ├── ColorPickerPanel.kt   # カラーピッカー
        ├── LayerPanel.kt         # レイヤー管理パネル
        └── SideQuickBar.kt       # サイズ/不透明度クイックバー
```

---

## アーキテクチャ

### 状態管理
`ViewModel` + Compose `mutableStateOf` / `mutableStateListOf`。
リアクティブに UI を更新。

### 筆圧パイプライン
```
Android タッチ/スタイラス入力
  → MotionEvent (pointerInteropFilter)
  → event.pressure (0.0〜1.0)
  → event.getHistoricalPressure() (全中間ポイント)
  → PaintViewModel.addStrokePoint(position, pressure)
  → StrokeRenderer (筆圧→サイズ/不透明度計算)
  → Compose Canvas 描画
```

### ブラシ描画
- 通常ブラシ: 筆圧ONならセグメント単位で太さ/不透明度変動、OFFなら`Path`で滑らかに描画
- エアブラシ: ランダム散布
- 水彩: 半透明 + エッジ効果
- 書道: `atan2` による角度依存幅
- 消しゴム: `BlendMode.Clear`
