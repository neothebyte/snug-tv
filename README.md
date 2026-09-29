# Living Room — Android TV

A native Android TV client for [Jellyfin](https://jellyfin.org), built for the remote. Browse the movie library from the couch, pick up where you left off, and play 4K files straight from the server whenever the television can decode them.

Living Room installs beside the official Jellyfin app: its package is `tv.livingroom.watch`, not `org.jellyfin.androidtv`.

## Features

**Browsing**
- Profile picker, with password entry only for profiles that need one. The server address can be changed on the sign-in screen.
- Home with a featured title and shelves: Continue watching, Recently added, and one per genre.
- Full movie grid, genre tiles, and search with an on-screen keyboard.
- Title page with resume and start-over, favourite and watched toggles, format badges, and subtitle and audio pickers.
- A side rail for sections. Back always returns focus to the poster or button you came from.

**Playback** (Media3 / ExoPlayer)
- On start the app checks which decoders the television has and sends Jellyfin a matching device profile.
- Files the television can decode — MKV and MP4, including 10-bit HEVC on TVs that support it — play directly with no work on the server.
- If only the container or audio is unsupported, the server repackages the stream over HLS without re-encoding the picture. A full re-encode happens only when the television cannot decode the video, and is capped at 1080p.
- Resume position, skips, chapters, playback speed, and progress reported to Jellyfin.
- Text subtitles are drawn by the app, so size and timing changes are instant. Image subtitles (PGS, DVD) use the television's decoder when playing directly.
- Online subtitle search through the Jellyfin OpenSubtitles plugin.

**Settings**
- How long player controls stay up, subtitle size and preferred language, automatic subtitle downloads, the OpenSubtitles login, the server address, and sign out.

## Remote controls

| Button | In the player |
| --- | --- |
| Center / Play-Pause | Play or pause |
| Left / Right | Skip 10 s (hold for 30 s) |
| Fast-forward / Rewind | Skip forward / back |
| Up / Down / Menu | Show controls |
| Captions | Subtitle options |
| Back | Close a panel, then hide the controls, then leave the player |

## Install

Download `LivingRoomTV.apk` from the [latest release](https://github.com/neothebyte/living-room-tv/releases/latest), then sideload it:

```bash
adb connect <tv-address>:5555
adb install -r LivingRoomTV.apk
```

On the television, open **Living Room**, confirm the server address (for example `http://192.168.1.10:8096`), and choose a profile.

Requirements: Android TV or Google TV on Android 7.0 (API 24) or newer, and a Jellyfin 10.10+ server on the local network. The app uses plain HTTP, which is intended for a home LAN.

## Build from source

Requires JDK 17 or newer and the Android SDK (platform 35).

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or your SDK path
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

Release builds are currently signed with the Android debug key so they can be sideloaded without extra setup. To publish or share builds more widely, create a release keystore and add a `signingConfigs` block in `app/build.gradle.kts`; note that devices with a debug-signed build installed must uninstall it before installing one signed with a different key.

## Project layout

```
app/src/main/java/tv/livingroom/watch/
  MainActivity.kt      Entry point
  LivingRoomApp.kt     Navigation and app shell
  Screens.kt           Sign-in, Home, grid, search, genres, detail, settings
  Components.kt        Posters, rails, dialogs, focus styling
  PlayerActivity.kt    Media3 player, controls, panels, progress reporting
  DeviceCodecs.kt      Decoder probing and Jellyfin device profile
  JellyfinClient.kt    Jellyfin REST client (OkHttp)
  AppModel.kt          State and actions
  SessionStore.kt      Saved server and session
  Models.kt, Theme.kt
```

## Related

- [Living Room — Web](https://github.com/neothebyte/living-room-web): the browser client for the same server.

## Acknowledgements

- [Jellyfin](https://jellyfin.org) for the server and API.
- [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/jetpack/compose), [OkHttp](https://square.github.io/okhttp/) and [Coil](https://coil-kt.github.io/coil/).
- [Inter](https://rsms.me/inter/) typeface (SIL Open Font License).
