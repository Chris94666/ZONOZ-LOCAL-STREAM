package com.sonoscast.local.ui

import androidx.compose.runtime.Composable
import com.sonoscast.local.core.I18n
import com.sonoscast.local.core.LocalStrings
import com.sonoscast.local.core.Str

/**
 * 取当前语言的文案。
 *
 * 走 CompositionLocal 而不是每次 `I18n.t()`，这样切语言时所有用到它的 Composable
 * 会自动重组；找不到键时回落到英文，再回落到键名，不会出现空白。
 */
@Composable
fun str(key: Str): String = LocalStrings.current[key] ?: I18n.table(com.sonoscast.local.core.Lang.EN)[key] ?: key.name

/** 带占位符的版本 */
@Composable
fun strf(key: Str, vararg args: String): String {
    val template = str(key)
    return runCatching { String.format(template, *args) }.getOrDefault(template)
}
