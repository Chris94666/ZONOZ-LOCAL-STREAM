package com.sonoscast.local.core

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 支持的语言。
 *
 * 用自建 i18n 而不是 Android 资源系统，原因很实际：
 * 这工程的文案大量写在 Compose 里，改成 `stringResource(R.string.x)` 要动几十个文件，
 * 而错误提示是**运行时生成**的（带 URL、MIME 这类参数），资源系统处理起来别扭。
 * 集中一张表反而更好维护、也方便运行时切换。
 */
enum class Lang(val tag: String, val nativeName: String) {
    ZH("zh", "简体中文"),

    /** 繁體中文（台灣用語），排在简体中文下面 */
    ZH_TW("zh-TW", "繁體中文"),

    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    RU("ru", "Русский"),
    ES("es", "Español"),
    FR("fr", "Français");

    companion object {
        fun fromTag(tag: String?): Lang? =
            values().firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /**
         * 跟随系统语言。
         *
         * 注意不能只看 `Locale.language`：简繁都是 "zh"，必须再看 region / script。
         * 台湾、香港、澳门，或显式 `Hant` 脚本 → 繁體；其余中文 → 简体。
         */
        fun systemDefault(): Lang {
            val locale = Locale.getDefault()

            // 先按完整 tag 精确匹配（例如系统里就是 zh-TW 时能直接命中）
            fromTag(locale.toLanguageTag())?.let { return it }

            if (locale.language.equals("zh", ignoreCase = true)) {
                val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                    locale.country.uppercase() in TRADITIONAL_REGIONS
                return if (traditional) ZH_TW else ZH
            }
            return fromTag(locale.language) ?: EN
        }

        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
    }
}

/** 所有可翻译文案的键。命名尽量短，但保持可读。 */
enum class Str {
    // 播放页
    NOT_PLAYING, PICK_SONG, LIBRARY, TRACKS_N, NOT_IMPORTED, PLAYING_ON_SPEAKER,
    OUTPUT_DEVICES, SELECT_OUTPUT_HINT, NO_DEVICE, MANUAL_IP, ADD, RESCAN, SCANNING,
    PLAY, PAUSE, STOP, PREV, NEXT,

    // 曲库
    NO_SONGS, TOTAL_N, PENDING_N, LONG_PRESS_HINT, SEARCH_HINT, NO_MATCH,
    SCAN_LIBRARY, SCAN_FAILED, LIB_EMPTY, LIB_EMPTY_HINT,
    NEED_PERMISSION, PERMISSION_HINT, GRANT,
    SORT_TITLE, SORT_ADDED, SORT_SIZE, SORT_DURATION,
    PLAY_NEXT, PLAY_NOW, FILE_INFO, FILE_NAME, LOCATION, SIZE, DURATION, FORMAT,
    BITRATE, ARTIST, ALBUM, MODIFIED, COPY_PATH, CLOSE, NEXT_BADGE, UNKNOWN,

    // 设置 / 调试
    SETTINGS, MUSIC_FOLDER, ALL_MUSIC, CHOOSE, CLEAR, SERVICE_ADDR, STREAM_URL,
    SCAN_SPEAKERS, LOGS_N, CLEAR_LOGS, NO_LOGS, ABOUT, AUTHOR, VERSION, LANGUAGE,
    STOP_SERVICE,

    // 状态
    PHASE_STOPPED, PHASE_STARTING, PHASE_STANDBY, PHASE_READY, PHASE_ERROR,
    DEV_IDLE, DEV_PUSHING, DEV_PLAYING, DEV_PAUSED, DEV_FAILED, DEV_FOLLOWING,
    ROLE_MAIN, ROLE_SUB, ROLE_SUB_DEVICE,

    // 错误提示（说人话，不出现数字码）
    ERR_REACH, ERR_FORMAT, ERR_MISSING, ERR_BADREQ, ERR_OFFLINE, ERR_SUB, ERR_FOLDER, ERR_UNKNOWN,

    // 播放模式（通知栏/播放键的 contentDescription）
    MODE_SEQUENTIAL, MODE_REPEAT_ALL, MODE_REPEAT_ONE, MODE_SHUFFLE,

    // 通用标签（1.7.2 把残留的硬编码中文补进表里）
    COPY, EXPAND, COLLAPSE, SAMPLE_RATE, UNKNOWN_ALBUM, UNTITLED_TRACK,
    ROOT_DIR, FOLDER_PICKED, NO_AUDIO_PERMISSION, ABOUT_DESC,

    // 通知栏
    EXIT, PORT_N, SPEAKERS_N,

    // 1.8.0 新增：后台续播引导 / S1-S2 混选 / 设备诊断
    HINT_MIXED_GEN, HINT_BATTERY, HINT_BATTERY_ACTION, HINT_EXACT_ALARM, DEV_DIAGNOSTICS,

    // 1.8.2 新增：播放清单 / 调试子页
    QUEUE, QUEUE_EMPTY, DEBUG,

    // 1.8.6 新增：整体文字大小
    FONT_SIZE, FONT_SMALL, FONT_NORMAL, FONT_LARGE, FONT_XLARGE,

    /** 设置页里"调试"条目的副标题 */
    DEBUG_HINT,

    // 1.8.9 新增：收藏夹
    FAVORITE, UNFAVORITE, FAVORITES,

    // 2.0.4 新增：设置页「后台保活」一行
    KEEPALIVE, KEEPALIVE_OK, KEEPALIVE_BATTERY, KEEPALIVE_ALARM,

    // 1.1.6 新增：其他来源占用提示 + 开源许可
    EXTERNAL_SOURCE, LICENSE, LICENSE_INTRO,

    // 1.1.9：断连提示
    CONNECTION_LOST,

    // 1.1.9：超出音箱规格的文件
    UNSUPPORTED_FORMAT,

    // 1.2：VPN / 代理提示
    VPN_ACTIVE,

    // 1.1.9：隐私声明
    PRIVACY, PRIVACY_INTRO
}

object I18n {

    private const val PREFS = "sonoscast.prefs"
    private const val KEY_LANG = "ui_lang"

    private val _current = MutableStateFlow(Lang.EN)
    val current: MutableStateFlow<Lang> = _current

    private val cache = ConcurrentHashMap<Lang, Map<Str, String>>()

    /** 启动时调用：优先用户手动选择，其次跟随系统 */
    fun init(context: Context) {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null)
        _current.value = Lang.fromTag(saved) ?: Lang.systemDefault()
    }

    fun set(context: Context, lang: Lang) {
        _current.value = lang
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, lang.tag)
            .apply()
    }

    fun table(lang: Lang): Map<Str, String> = cache.getOrPut(lang) {
        when (lang) {
            Lang.ZH -> ZH
            Lang.ZH_TW -> ZH_TW
            Lang.EN -> EN
            Lang.JA -> JA
            Lang.KO -> KO
            Lang.RU -> RU
            Lang.ES -> ES
            Lang.FR -> FR
        }
    }

    /** 给非 Composable 代码用 */
    fun t(key: Str): String = table(_current.value)[key] ?: table(Lang.EN)[key] ?: key.name

    /** 带占位符（统一用 %s，参数按顺序传字符串） */
    fun f(key: Str, vararg args: String): String =
        runCatching { String.format(t(key), *args) }.getOrDefault(t(key))

    // ------------------------------------------------------------- 各语言表
}

/** Compose 侧通过它取文案，切换语言时会自动重组 */
val LocalStrings = staticCompositionLocalOf<Map<Str, String>> { emptyMap() }
