# Auto Speaker (Android)

Turns the phone's loudspeaker on automatically when a call connects — incoming,
outgoing, or both. Android 8.0 and newer.

## How it works
1. Listens for the phone's call state (needs the **Phone** permission).
2. When a call connects (after an optional 0–5 s delay) it asks Android's audio
   system to switch to the speaker.
3. If the phone blocks that (common on Android 12+), it presses the **Speaker**
   button on the call screen for you, using an Accessibility service.
4. It acts once per call: if you switch the speaker off, it stays off.
5. Skips calls when earphones or Bluetooth (e.g. car) are connected — can be
   turned off in the app.

No internet permission, no data collected.

## Get the APK
**Option A – GitHub (no computer needed):** push this folder to a GitHub repo.
The included workflow (`.github/workflows/build.yml`) builds the APK and
publishes it as a release. Download it on your phone from
`https://github.com/<you>/<repo>/releases/download/autospeaker-latest/AutoSpeaker.apk`.

**Option B – Android Studio:** open this folder, wait for Gradle sync, then
*Build → Build APK(s)*. Or from a terminal: `./gradlew assembleDebug`
(output: `app/build/outputs/apk/debug/app-debug.apk`).

## Install & set up on the phone
1. Open the APK; allow "Install unknown apps" for your browser/file manager
   when asked.
2. Open **Auto Speaker** and do the three setup steps:
   1. *Allow call detection* → Allow.
   2. *Turn on Auto Speaker in Accessibility* → Installed/Downloaded apps →
      Auto Speaker → On.
      On Android 13+ you may see **"Restricted setting"**: go to
      *App info → ⋮ (top right) → Allow restricted settings*, then retry.
   3. *Stop battery saver from closing it* → Allow.
      On Xiaomi/Redmi/POCO, Oppo, Realme, Vivo also enable **Autostart** for the
      app in its App info page.
3. Make a test call.

## If it doesn't turn on for your phone
The call-screen button is found by its label ("Speaker", "स्पीकर", etc.). If your
dialer uses a different word, add it to `SPEAKER_WORDS` in
`app/src/main/java/com/autospeaker/app/SpeakerService.java`.
Logs: `adb logcat -s AutoSpeaker`.

Note: each GitHub build is signed with a fresh debug key, so to install a newer
build you may need to uninstall the old one first.
