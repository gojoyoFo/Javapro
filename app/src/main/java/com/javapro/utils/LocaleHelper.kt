package com.javapro.utils

import android.content.Context
import android.os.Build
import java.util.Locale

object LocaleHelper {

    private const val PREFS_NAME = "javapro_settings"
    private const val KEY_LANG   = "lang"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, "en") ?: "en"
    }

    fun saveLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, lang)
            .commit()
    }

    fun applyLocale(context: Context, lang: String): Context {
        val locale = buildLocale(lang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun buildLocale(lang: String): Locale {
        val androidLang = if (lang == "id") "in" else lang
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Locale.forLanguageTag(androidLang)
        } else {
            Locale(androidLang)
        }
    }
}
