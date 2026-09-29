# Living Room — Android TV

A native Android TV client for [Jellyfin](https://jellyfin.org), built for the remote. Browse the movie library from the couch, pick up where you left off, and play 4K files straight from the server whenever the television can decode them.

Living Room installs beside the official Jellyfin app: its package is `tv.livingroom.watch`, not `org.jellyfin.androidtv`.

## Features

**Browsing**
- Finds Jellyfin servers on the home network by itself, or takes a typed address (`http://` and `:8096` are filled in when left out).
- Profile picker, with password entry only for profiles that need one, plus Quick Connect: the TV shows a code you approve from the Jellyfin app on your phone.
- Home with a featured title and shelves: Continue watching, Unwatched, Favorites, the four biggest genres, and Recently added.
- Full movie grid, genre tiles, and search with an on-screen keyboard.
- Title page with resume and start-over, favourite and watched toggles, format badges, and subtitle and audio pickers.
- A side rail for sections. Back always returns focus to the poster or button you came from.

**Playback** (Media3 / ExoPlayer)
- On start the app checks which hardware decoders the television has, and the largest picture each handles smoothly, and sends Jellyfin a matching device profile. A TV that can't play 4K HEVC or AV1 smoothly gets a converted stream instead of a stutter.
- Files the television can decode — MKV and MP4, including 10-bit HEVC on TVs that support it — play directly with no work on the server.
- If only the container or audio is unsupported, the server repackages the stream over HLS without re-encoding the picture. A full re-encode happens only when the television cannot decode the video, and is capped at 1080p.
- If a file still fails, the player steps down one level at a time: repackage, then convert only the sound, and re-encode the picture only when the video decoder is the problem. A dropped connection is retried once first.
- Dolby Vision files without an HDR10 fallback (profile 5) are converted on TVs without a Dolby Vision decoder, so they don't show purple and green.
- The TV switches to a refresh rate matching the film (for example 23.976 Hz), so slow pans don't judder, and switches back when the player closes.
- Up to about two minutes of video is buffered, and a Wi-Fi lock is held while playing, so high-bitrate files ride out Wi-Fi dips.
- Resume position, skips, chapters, playback speed, and progress reported to Jellyfin.
- Text subtitles are drawn by the app, so size and timing changes are instant. Image subtitles (PGS, DVD) use the television's decoder when playing directly.
- Online subtitle search through the Jellyfin OpenSubtitles plugin.

**Settings**
- How long player controls stay up, frame-rate matching, subtitle size and preferred language, automatic subtitle downloads, the OpenSubtitles login, changing server, and sign out.

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

To try a build before it is released, open a pull request's run under **Actions → Build** and download the `LivingRoomTV` artifact (a zip with the APK inside). They are signed with the same release key as local builds, so they install over the app already on the television.

On the television, open **Living Room**, pick your server (or enter its address, for example `192.168.1.10`), and choose a profile or use Quick Connect.

Requirements: Android TV or Google TV on Android 7.0 (API 24) or newer, and a Jellyfin 10.10+ server on the local network. The app uses plain HTTP, which is intended for a home LAN.

Current limits: only movie libraries are shown (no TV shows, music or Live TV yet), and the app loads up to 1,000 movies.

## Build from source

Requires JDK 17 or newer and the Android SDK (platform 35).

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or your SDK path
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

GitHub also builds the APK for every push and pull request: open the run under **Actions → Build** and download the `LivingRoomTV` artifact.

Release builds are signed with the release key when these properties are set, in `~/.gradle/gradle.properties` locally or as environment variables:

```properties
LIVINGROOM_KEYSTORE=/path/to/livingroom-release.jks
LIVINGROOM_KEYSTORE_PASSWORD=...
LIVINGROOM_KEY_ALIAS=livingroom
```

GitHub Actions reads the key from the `LIVINGROOM_KEYSTORE_BASE64` and `LIVINGROOM_KEYSTORE_PASSWORD` repository secrets. Without them, release builds fall back to the debug key, which will not install over a release-signed app. Keep a backup of the keystore: if it is lost, every television has to uninstall the app before installing a build signed with a new key.

## Project layout

```
app/src/main/java/tv/livingroom/watch/
  MainActivity.kt      Entry point
  LivingRoomApp.kt     Navigation and app shell
  Screens.kt           Sign-in, Home, grid, search, genres, detail, settings
  Components.kt        Posters, rails, dialogs, focus styling
  PlayerActivity.kt    Media3 player, controls, panels, progress reporting
  DeviceCodecs.kt      Decoder probing and Jellyfin device profile
  DisplayRate.kt       Refresh-rate matching for the player
  JellyfinClient.kt    Jellyfin REST client (OkHttp)
  AppModel.kt          State and actions
  ServerDiscovery.kt   Finding servers on the network
  SessionStore.kt      Saved server and session
  Models.kt, Theme.kt
```

## Related

- [Living Room — Web](https://github.com/neothebyte/living-room-web): the browser client for the same server.

## Acknowledgements

- [Jellyfin](https://jellyfin.org) for the server and API.
- [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/jetpack/compose), [OkHttp](https://square.github.io/okhttp/) and [Coil](https://coil-kt.github.io/coil/).
- [Inter](https://rsms.me/inter/) typeface (SIL Open Font License).
