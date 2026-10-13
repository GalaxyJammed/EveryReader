package com.example.everyreader

import android.content.Context

object AppPrefs {
    private const val FILE = "everyreader_prefs"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_COMBINE = "combine_paths"
    private const val KEY_SEPARATE = "separate_by_ext"
    private const val KEY_CATEGORY = "category"
    private const val KEY_SORT = "sort_order"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun darkMode(context: Context, default: Boolean): Boolean =
        prefs(context).getBoolean(KEY_DARK, default)

    fun setDarkMode(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DARK, value).apply()

    fun combinePaths(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMBINE, false)

    fun setCombinePaths(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_COMBINE, value).apply()

    fun separateByExt(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEPARATE, false)

    fun setSeparateByExt(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SEPARATE, value).apply()

    fun category(context: Context, default: FileType): FileType =
        prefs(context).getString(KEY_CATEGORY, null)
            ?.let { name -> FileType.entries.firstOrNull { it.name == name } }
            ?: default

    fun setCategory(context: Context, value: FileType) =
        prefs(context).edit().putString(KEY_CATEGORY, value.name).apply()

    fun sortOrder(context: Context, default: SortOrder): SortOrder =
        prefs(context).getString(KEY_SORT, null)
            ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
            ?: default

    fun setSortOrder(context: Context, value: SortOrder) =
        prefs(context).edit().putString(KEY_SORT, value.name).apply()
}