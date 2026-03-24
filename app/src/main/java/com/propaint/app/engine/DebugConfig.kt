package com.propaint.app.engine

/** 診断ログの有効/無効フラグ。 */
object DebugConfig {
    @Volatile
    var enableDiagnosticLog: Boolean = false
}
