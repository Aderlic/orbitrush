package com.ulfan.orbitrush

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class SoundManager(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val tap = pool.load(context, R.raw.tap, 1)
    private val gem = pool.load(context, R.raw.gem, 1)
    private val crash = pool.load(context, R.raw.crash, 1)
    private val reward = pool.load(context, R.raw.reward, 1)

    fun tap() = play(tap, 0.6f)
    fun gem() = play(gem, 0.9f)
    fun crash() = play(crash, 1.0f)
    fun reward() = play(reward, 1.0f)

    private fun play(id: Int, vol: Float) {
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun release() = pool.release()
}
