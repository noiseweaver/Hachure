# Hachure

A sketching app for the Onyx BOOX Note Air 2 (e-ink, Wacom EMR stylus), built around low-latency
inking and precise rulers and perspective guides for hatching.

Status: Milestone 1. Blank canvas, fineliner pen with plain `MotionEvent` input, CI that builds a
signed release APK. The Onyx pen SDK backend lands in Milestone 2.

## Install on the Boox with Obtainium (no cable, no adb)

[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates apps straight from GitHub
Releases.

1. On the Boox, install Obtainium from its GitHub Releases page (download the APK in the browser
   and open it; allow installs from the browser when asked).
2. In Obtainium tap **Add App** and paste this repo's URL, for example
   `https://github.com/<owner>/hachure`.
3. Leave the source as **GitHub**. Leave "Include prereleases" off.
4. Tap **Add**, then **Install**. Obtainium checks for new Releases in the background and offers
   updates.

Boox-specific settings worth changing once installed:

- In the Boox **App Optimization** (long-press the app icon), leave refresh mode on the default.
  The app sets its own e-ink modes; a forced global mode fights it.
- Allow Obtainium to run in the background (Boox app freezing can stop update checks).

Every Release APK is signed with the same key, so updates install over the previous version and
keep your drawings. A build signed with a different key (see below) needs an uninstall first.

## Builds

| Trigger | What happens |
| --- | --- |
| Push to `main`, any pull request | Unit tests, lint, release APK uploaded as a workflow artifact |
| Tag `v*` (for example `v0.1.0`) | Same, then a GitHub Release with the APK and generated notes |

- `versionCode` is the workflow run number, `versionName` is the tag without the `v` (branch builds
  get `0.0.<run>-<sha>`). Branch builds and releases share one workflow, so the run number only
  goes up and a newer build always installs over an older one.
- The build summary prints the signing certificate SHA-256 so you can check every APK has the same
  signer.
- Pull requests from forks have no access to secrets and are signed with a throwaway debug key.

To cut a release:

```sh
git tag v0.1.0
git push origin v0.1.0
```

### Signing key setup (once)

Create the keystore on your computer. Keep the file and passwords somewhere safe: losing them
means users must uninstall to get later versions.

```sh
keytool -genkeypair -v \
  -keystore hachure-release.jks \
  -storetype PKCS12 \
  -alias hachure \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -dname "CN=Hachure"
```

With PKCS12 the key password is the same as the keystore password; `keytool` asks for it once.

Encode it as one line of base64:

```sh
# Linux
base64 -w0 hachure-release.jks > hachure-release.jks.b64
# macOS
base64 -i hachure-release.jks | tr -d '\n' > hachure-release.jks.b64
```

Add four repository secrets in GitHub under **Settings > Secrets and variables > Actions > New
repository secret**:

| Name | Value |
| --- | --- |
| `KEYSTORE_BASE64` | contents of `hachure-release.jks.b64` |
| `KEYSTORE_PASSWORD` | the password you typed |
| `KEY_ALIAS` | `hachure` |
| `KEY_PASSWORD` | the same password |

Or with the GitHub CLI from the repo folder:

```sh
gh secret set KEYSTORE_BASE64 < hachure-release.jks.b64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body hachure
gh secret set KEY_PASSWORD
```

Delete the `.b64` file afterwards. Never commit the keystore (`*.jks` is in `.gitignore`).

## Project layout

| Module / package | Contents |
| --- | --- |
| `core` `app.hachure.model` | Strokes, pages, guides. Pure Kotlin, no Android. |
| `core` `app.hachure.geometry` | Vectors, unit conversion, projection and snapping math. Pure Kotlin, no Android. |
| `app` `app.hachure.ink` | `InkBackend` interface, `MotionEventInkBackend`, Onyx backend (Milestone 2). |
| `app` `app.hachure.render` | Tool renderers and layers. |
| `app` `app.hachure.ui` | Activity, canvas view, toolbars. |

`core` is a separate JVM module, so the compiler enforces that model and geometry have no Android
dependencies. Its tests run without the Android SDK installed:

```sh
./gradlew -Pcore.only=true :core:test
```

All geometry is stored in millimetres on the page and converted to pixels at render time
(227 dpi on the Note Air 2), so a later zoom feature does not change the file format.

Toolbars use plain Android Views rather than Compose: no Compose runtime in the APK, faster cold
start on the Boox, no recomposition-driven redraws (each costs an e-ink refresh), and the exclude
rects the Onyx SDK needs come directly from `getGlobalVisibleRect` on real views.

## Onyx SDK dependencies

Added in Milestone 2 from `https://repo.boox.com/repository/maven-public/`. CI already probes that
repository on every build and writes the result to the job summary. If GitHub runners cannot
reach it, the fallback is vendored AARs in `app/libs/`; the refresh steps will be documented here
when that fallback is needed.
