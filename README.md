# Hachure

A sketching app for the Onyx BOOX Note Air 2 (e-ink, Wacom EMR stylus), built around low-latency
inking and precise rulers and perspective guides for hatching.

Status: Milestone 4. Fast A2 live ink through the Onyx pen SDK, notebooks shelf, tool rail and
puck, eraser, undo, rulers with snapping and automatic hatch stepping, and 1, 2 and 3-point
perspective guides. A live snap preview comes next (Milestone 6).

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
| `app` `app.hachure.ui` | Activity, canvas view, tool rail and puck. |

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

The Onyx pen SDK is vendored as AARs in `app/libs/` with pinned SHA-256 sums in
`app/libs/SHA256SUMS`. CI checks the sums on every build and fails if `TouchHelper`, `EpdController`
or the native pen reader are missing from the APK.

| AAR | Version |
| --- | --- |
| `onyxsdk-pen` | 1.4.12 |
| `onyxsdk-base` | 1.7.7 |
| `onyxsdk-device` | 1.2.31 |

`hiddenapibypass` 4.3 and RxJava 2 come from Maven Central.

Why vendored: `repo.boox.com` only offers a weak Diffie-Hellman key exchange, which current
OpenSSL and Java refuse ("dh key too small"). Vendoring keeps that exception out of normal builds
and makes builds independent of Boox's server.

The Onyx AARs declare permissions (Wi-Fi, Bluetooth, storage, overlay and more). The app manifest
removes all of them and CI fails if the APK declares any permission.

### Refreshing the AARs

1. Edit the versions in `.github/workflows/vendor-onyx.yml` (and the file names in
   `app/build.gradle.kts` if they change).
2. On GitHub, open **Actions > Vendor Onyx SDK > Run workflow** and pick your branch. The
   workflow downloads the AARs (relaxing TLS for those requests only), regenerates `SHA256SUMS`
   and commits both to that branch.
3. Pull the branch and check the diff of `SHA256SUMS` before merging.

## Notebooks shelf

The app opens on the shelf: one hatch-patterned cover per notebook, with a New notebook tile
first. The Recent, A to Z and Archived chips sort and filter. Long-press a cover to rename it,
change its cover pattern, archive or delete it. The settings button holds the same Show button
labels and Dot grid switches as the drawing screen.

Notebooks live in the app's private storage (`files/notebooks/<id>/`), one binary page file plus a
small properties file each. Every edit is saved 0.8 s after the pen lifts and again when leaving
the page; writes go to a temp file first, so an interrupted save keeps the previous version.
Uninstalling the app deletes them (export arrives in Milestone 7).

## Drawing screen

The tools live on a rail along the left edge. Drag the grip at the top of the rail away from the
edge and it becomes a floating puck; tap the puck to open its ring of tools, and drag it to either
edge to dock it there again. A finger tap outside the ring closes it.

- **Pencil**, **Fountain** and the **Style** button pick a brush. Style lists all seven A2 styles
  (pencil, fountain, marker, brush, charcoal, dash, charcoal 2); brush and charcoal 2 are marked
  experimental because other apps have seen crashes with them.
- **Line** draws a straight stroke from pen-down to pen-up. **Eraser** removes whole strokes it
  touches. Tap either again to go back to drawing. The stylus eraser end always erases.
- **Width** cycles fine, medium and bold. **Undo** and **Redo** cover drawing, erasing and clearing.
- **Shelf** (top of the rail) saves and goes back to the notebooks.
- **More** holds Show button labels (off by default), Dot grid, Eraser trail, Clear page and Debug
  overlay. Clear page removes the ink and keeps the rulers.

Buttons are 9 mm and the rail 10 mm wide, measured from the panel's real dpi.

## Rulers and hatching

**Ruler** on the rail adds a ruler (up to 8 on a page). Rulers are saved with the page.

- Fingers move rulers, the stylus draws. The stylus never moves a ruler and a finger never draws.
- Each ruler has three 9 mm handles: the middle one moves it, the two near the ends turn it about
  its centre. While you drag, a dashed outline follows the finger (with the angle while turning)
  and the ruler itself is redrawn once when you let go.
- Tap a ruler to select it. A strip appears along the bottom with its angle (tap to type one),
  15 degree snap for turning, hatch direction, hatch spacing, lock, delete and done. Long-press a
  handle to type an angle directly. Angles read 0 for horizontal, 45 rising to the right, 90 for
  vertical.
- A stroke that starts within 4 mm of a ruler edge is straightened onto that edge when the pen
  lifts. Its start and end are projected onto the edge and the pressure along it is kept. **Snap**
  on the rail turns this on and off.
- Hatching: set a hatch direction on the ruler (the arrow shows which way) and a spacing in mm.
  After every stroke drawn along the ruler it steps sideways by that spacing, so you only draw.
  Undo takes the stroke and the step back together.
- A locked ruler shows a padlock, has no handles and cannot be moved by a finger. It still snaps
  and still steps when hatching. **Lock all** on the rail locks every ruler at once. Lock a ruler
  you rest your hand on.

## Perspective guides

**Perspective** on the rail opens the perspective strip along the bottom. **Points** steps
through off, 1, 2 and 3-point perspective. Perspective is saved with the page.

- Each vanishing point (VP) has a numbered 9 mm finger handle. A VP can sit off the page: it then
  shows as an arrow marker at the page edge, and every drag of the marker moves the VP by as much
  as the finger moved, so repeated drags push it further out.
- The horizon runs through VP1 and VP2 (horizontal through VP1 in 1-point). Its handle, with the
  up and down arrow, moves it and both points up or down.
- Thin dotted guide lines radiate from each VP. **Guide lines** shows or hides them and the
  minus and plus buttons step their number per point (12, 24, 36, 72).
- **Selected** steps through VP1 to VP3 and the horizon; **Lock** locks that one. A locked VP or
  horizon has no handle. A locked horizon also keeps VP1 and VP2 on it: they still slide along
  the line. **Lock all** on the rail covers these too.
- Snapping: the first 5 mm of a stroke decide where it is heading. If that is within 8 degrees of
  the line to a VP, the stroke becomes a straight segment toward (or away from) that VP when the
  pen lifts. In 1 and 2-point perspective true vertical and horizontal snap the same way. The
  closest match wins; with no match the stroke stays freehand.
- Rulers and perspective work together. Order: a stroke that starts within 4 mm of a ruler edge
  goes to the ruler, otherwise to a matching VP, otherwise it stays freehand. **Snap** on the
  rail switches all of it off and on.
- Perspective hatching: select a ruler and tap **Aim** to point it at VP1, VP2 or VP3. The edge
  with the two solid wedges then always passes through that VP, wherever you move the ruler, and
  it turns a little with every hatch step, so stepped hatching fans out from the VP. An aimed
  ruler has only its move handle. Typing an angle sets it free again.

## Snap preview (experiment)

**More > Snap preview** chooses how a stroke that will snap to a ruler edge looks while the pen
is still down. It is there to compare the three on the device; one will be kept.

- **A: straighten at pen-up.** The fast live ink follows the pen freehand and the stroke jumps
  onto the edge when the pen lifts.
- **B: live line, touch path.** The snap bands are taken away from the ink SDK (one rectangle
  for a level or upright edge, a row of small squares along a slanted one). A stroke that starts
  there arrives as ordinary touch events and the app draws the straight line on the edge as you go.
- **C: live line, render switch.** The SDK keeps the stroke but its ink is switched off for
  that one stroke, and the app draws the straight line on the edge as you go.

In B and C the line is drawn by the app with normal partial refreshes, so it trails the pen more
than the SDK's ink. Strokes away from rulers, and perspective snapping, are the same in all three.

A finger is ignored for half a second after the pen last touched the page, and the pen touching
down cancels a ruler drag, so a resting hand does not shift things mid-stroke.

## Debug overlay

Open **More > Debug overlay**. It shows the active backend (and why it fell back, if it did), the
device, the hidden API bypass result, panel dpi, app version, the SDK limit and exclude rects, any
brush whose SDK renderer failed, and per stroke the time from pen-up to the stroke being committed.
While it is open, More also offers the pen release and point coordinate switches.

**Clean** does a single full refresh to clear e-ink ghosting. It never runs automatically.
