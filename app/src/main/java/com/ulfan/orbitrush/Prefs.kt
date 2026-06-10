package com.ulfan.orbitrush

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Persistence: best score, gem wallet, owned/selected skins, daily streak. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("orbit_rush", Context.MODE_PRIVATE)

    var highScore: Int
        get() = sp.getInt("high_score", 0)
        set(v) = sp.edit().putInt("high_score", v).apply()

    var gems: Int
        get() = sp.getInt("gems", 0)
        set(v) = sp.edit().putInt("gems", v).apply()

    var selectedSkin: String
        get() = sp.getString("skin", Skins.DEFAULT_ID) ?: Skins.DEFAULT_ID
        set(v) = sp.edit().putString("skin", v).apply()

    var gamesPlayed: Int
        get() = sp.getInt("games_played", 0)
        set(v) = sp.edit().putInt("games_played", v).apply()

    var streak: Int
        get() = sp.getInt("streak", 0)
        private set(v) = sp.edit().putInt("streak", v).apply()

    fun isOwned(id: String): Boolean =
        id == Skins.DEFAULT_ID || sp.getBoolean("owned_$id", false)

    fun setOwned(id: String) = sp.edit().putBoolean("owned_$id", true).apply()

    /**
     * Call once per launch. Updates the daily streak and returns the gem
     * reward granted today, or 0 if today was already claimed.
     */
    fun claimDailyReward(): Int {
        val today = LocalDate.now().toEpochDay()
        val last = sp.getLong("last_day", -1L)
        if (last == today) return 0
        val newStreak = if (last == today - 1) streak + 1 else 1
        streak = newStreak
        sp.edit().putLong("last_day", today).apply()
        val reward = 5 * minOf(newStreak, 7)
        gems += reward
        return reward
    }
}
