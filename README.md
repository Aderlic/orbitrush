# Orbit Rush

One-tap hyper-casual Android game. Your ball orbits two rings — **tap to switch rings**, dodge spikes, collect gems. Speed ramps up the longer you survive.

## Features

- 60 fps SurfaceView game loop, pure Kotlin, zero engine dependencies
- Difficulty ramp + forced ring-switch patterns for the "one more run" hook
- Persistent high score, gem currency, 6 unlockable ball skins
- Daily streak rewards (up to +35 gems/day at a 7-day streak)
- Sound effects (SoundPool) + haptic feedback
- AdMob-ready stubs: interstitial every 3rd game over, rewarded "2x gems" button
- Screen shake, particle bursts, glow trail — juicy game feel
- Release build configured with R8 minify + resource shrinking

## Build & run

1. Open the `OrbitRush` folder in **Android Studio** (Ladybug or newer).
2. When prompted about the missing Gradle wrapper, accept Android Studio's fix
   (or run `gradle wrapper --gradle-version 8.10.2` once if you have Gradle locally).
3. Let Gradle sync, then **Run ▶** on a device/emulator (Android 8.0+, API 26+).

Release APK/AAB: `Build → Generate Signed App Bundle/APK` (add your own signing config first).

## Project layout

```
app/src/main/java/com/ulfan/orbitrush/
  MainActivity.kt   — fullscreen host, lifecycle
  GameView.kt       — game loop, physics, states (menu/play/shop/game-over), rendering
  Prefs.kt          — high score, gems, skins, daily streak persistence
  Skins.kt          — skin catalog
  SoundManager.kt   — SoundPool sfx
  Haptics.kt        — vibration
  AdsManager.kt     — monetization stub (see below)
```

## Monetization (AdMob)

Ships with no-op stubs so it builds cleanly. To enable:

1. `app/build.gradle.kts` — uncomment `play-services-ads`.
2. `AndroidManifest.xml` — uncomment `INTERNET` permission and the
   `APPLICATION_ID` meta-data; replace the test ID with your AdMob App ID.
3. In `AdsManager.kt` replace the stub bodies:

```kotlin
// init:
MobileAds.initialize(activity) {}

// interstitial (load in init / after each show, then):
interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
    override fun onAdDismissedFullScreenContent() { loadInterstitial(activity); onDismissed() }
}
interstitialAd?.show(activity) ?: onDismissed()

// rewarded:
rewardedAd?.show(activity) { onReward() } ?: onReward()
```

Test ad unit IDs: interstitial `ca-app-pub-3940256099942544/1033173712`,
rewarded `ca-app-pub-3940256099942544/5224354917`.

## Tuning the difficulty

In `GameView.kt`: `OMEGA_BASE` / `OMEGA_MAX` (orbit speed), `gap()` (obstacle density),
`score * 0.030` (ramp rate), `INTERSTITIAL_EVERY` in `AdsManager.kt`.

## Play Store checklist

- Set your own `applicationId`, bump `versionCode` per release
- Add a signing config and keystore
- Privacy policy URL (required once ads are enabled)
- Store listing: screenshots, feature graphic, content rating questionnaire
