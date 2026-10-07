package com.example.systemhealth

import android.content.Context
import android.util.TypedValue

/** Light and dark themes resolve differently, so painted surfaces are taken from the active theme. */
internal fun Context.themeColor(attribute: Int, fallback: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attribute, value, true) && value.type in
            TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data else fallback
}
