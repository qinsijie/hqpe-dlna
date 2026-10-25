package com.github.qinsijie.hqpedlna

import android.graphics.drawable.Drawable

data class AppInfo(
    val appName: String,
    val packageName: String,
    val uid: Int,
    val icon: Drawable?
) {
    override fun toString(): String = "$appName ($packageName)"
}
