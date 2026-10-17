package com.myagent.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** One choice in Settings > Background. [image] is null for the two drawn (built-in) looks. */
data class BackgroundChoice(val key: String, val label: String, @DrawableRes val image: Int?)

val Backgrounds = listOf(
    BackgroundChoice("auto", "梦幻蓝 Dreamy", null),          // start page: dreamy waves, chat: sky grid
    BackgroundChoice("scifi", "科幻 Sky grid", null),
    BackgroundChoice("winter", "冬雪 Winter", R.drawable.bg_winter),
    BackgroundChoice("sakura", "樱花 Sakura", R.drawable.bg_sakura),
    BackgroundChoice("ocean", "海洋 Ocean", R.drawable.bg_ocean),
    BackgroundChoice("autumn", "秋叶 Autumn", R.drawable.bg_autumn),
    BackgroundChoice("halloween", "万圣节 Halloween", R.drawable.bg_halloween),
    BackgroundChoice("christmas_green", "圣诞绿 Christmas", R.drawable.bg_christmas_green),
    BackgroundChoice("christmas_pink", "圣诞粉 Candy cane", R.drawable.bg_christmas_pink),
    BackgroundChoice("pink_silk", "粉色丝带 Pink silk", R.drawable.bg_pink_silk),
    BackgroundChoice("blue_silk", "蓝色丝带 Blue silk", R.drawable.bg_blue_silk),
    BackgroundChoice("momo", "Momo", R.drawable.bg_momo),
)

/** Draws the chosen background behind the whole screen. */
@Composable
fun AppBackground(key: String, startPage: Boolean) {
    val choice = Backgrounds.firstOrNull { it.key == key } ?: Backgrounds.first()
    when {
        choice.image != null -> Image(
            painter = painterResource(choice.image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        choice.key == "scifi" -> SciFiBackground()
        startPage -> DreamyBackground()
        else -> SciFiBackground()
    }
}
