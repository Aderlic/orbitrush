package com.ulfan.orbitrush

import android.graphics.Color

data class Skin(val id: String, val name: String, val color: Int, val cost: Int)

object Skins {
    const val DEFAULT_ID = "comet"

    val ALL = listOf(
        Skin("comet", "Comet", Color.WHITE, 0),
        Skin("ember", "Ember", Color.parseColor("#FF7A1A"), 25),
        Skin("toxin", "Toxin", Color.parseColor("#5CFF5C"), 50),
        Skin("neon", "Neon", Color.parseColor("#00E5FF"), 100),
        Skin("violet", "Violet", Color.parseColor("#B388FF"), 150),
        Skin("aurum", "Aurum", Color.parseColor("#FFD24D"), 250)
    )

    fun byId(id: String): Skin = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
