package com.ulfan.orbitrush

import android.app.Activity
import android.util.Log

/**
 * Monetization stub — ships with no ads so the game builds and runs cleanly.
 *
 * To enable AdMob:
 * 1. In app/build.gradle.kts uncomment the play-services-ads dependency.
 * 2. In AndroidManifest.xml uncomment INTERNET permission and the
 *    APPLICATION_ID meta-data (replace the test ID with your own).
 * 3. Replace the bodies below with InterstitialAd / RewardedAd load+show
 *    calls (see README "Monetization" for exact code).
 */
object AdsManager {

    private const val TAG = "AdsManager"

    /** How many game-overs between interstitials. */
    const val INTERSTITIAL_EVERY = 3

    fun init(activity: Activity) {
        // MobileAds.initialize(activity) {}
        Log.d(TAG, "Ads stub initialized")
    }

    /** Called on every Nth game over. */
    fun showInterstitial(activity: Activity, onDismissed: () -> Unit) {
        Log.d(TAG, "Interstitial placeholder shown")
        onDismissed()
    }

    /** "Double your gems" button on the game-over screen. */
    fun showRewarded(activity: Activity, onReward: () -> Unit) {
        Log.d(TAG, "Rewarded placeholder shown — granting reward")
        onReward()
    }
}
