# Sudoku Overlay (Android)

A small Android companion app for **Sudoku - Classic Sudoku Puzzle by Oakever Games**.

- Target game package: `easy.sudoku.puzzle.solver.free`
- Companion app label: **Sudoku Overlay**
- Android minimum: Android 8.0 (API 26)
- Puzzle format in this first build: classic 9×9 Sudoku

## What this MVP does

1. Requests Android screen-capture permission.
2. Captures one screen frame. On older Android versions, the app waits about 3 seconds so you can switch to the Oakever Sudoku game after granting capture permission.
3. Detects the Sudoku grid lines and uses on-device ML Kit text recognition to read the clue digits.
4. Shows an editable 9×9 grid so you can correct any OCR mistakes.
5. Solves the puzzle locally and checks whether it has zero, one, or multiple solutions.
6. Displays the complete solution in a draggable floating panel above the game. Original clues are white; solved cells are blue. The panel has Copy and Close controls.

The solution engine runs on the phone. Puzzle screenshots are not uploaded by this app.

## DokuSolver status

The app includes an **Open DokuSolver website** shortcut. DokuSolver's public website supports screenshot uploads and manual entry, but this project does not automate website submission or scrape its result. I could not verify a documented public developer API, so the first build uses a local Sudoku solver for a dependable overlay rather than relying on undocumented website internals. The website can still be used separately to cross-check a puzzle.

Website: <https://www.dokusolver.com/>

## How to use

1. Install and open **Sudoku Overlay**.
2. Open a standard 9×9 puzzle in Oakever's Sudoku game.
3. Return to Sudoku Overlay and tap **Scan Sudoku screen**.
4. Approve Android's screen-capture prompt. On Android 14+, choose the Oakever Sudoku app window when Android offers the app/window selector. On older Android versions, switch to the Sudoku game during the 3-second delay.
5. Review all detected clues in the 9×9 editor. Tap any cell to correct it. The OCR is a helper, not a guarantee.
6. Tap **Solve puzzle**.
7. Tap **Show floating solution panel** and grant **Display over other apps** the first time.
8. Return to the game. Drag the panel by its title bar; use **COPY** to copy the 9 solution rows or **×** to close it.

If automatic grid detection fails, import a clear screenshot from the gallery or enter/correct the cells manually.

## Build the APK on GitHub Actions

This repository includes `.github/workflows/build-apk.yml`.

1. Create a new GitHub repository, for example `sudoku-overlay`.
2. Upload the **contents** of this folder to the repository root (the `.github` folder must be included).
3. Push to `main`/`master`, or open **Actions → Build Sudoku Overlay APK → Run workflow**.
4. When the workflow succeeds, open that workflow run and download the artifact named **Sudoku-Overlay-debug-apk**.
5. Inside the artifact is `app-debug.apk`. Transfer it to your Android phone and install it. Android may ask you to allow installation from the browser or file manager you used.

The debug APK is suitable for personal testing. A release APK for Play Store distribution should be built with your own signing key.

## Build locally

Open the project root in Android Studio, or run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

JDK 17 and Android SDK platform 35 are configured for the project.

## Current limitations

- The first version supports standard 9×9 Sudoku, not Oakever's larger 16×16 or variant modes.
- Grid-line recognition is tuned for the normal light Sudoku board. Dark themes, unusual scaling, or game UI changes may need manual clue correction or manual entry.
- It shows a complete floating answer panel; it does **not** tap the game keypad or automatically enter answers. That requires a separate, carefully calibrated accessibility/gesture feature and should be added only after the panel is tested on the actual device.
- Android requires the user to approve screen capture, and the floating panel requires the user to allow display-over-other-apps permission.

## Project layout

```text
app/src/main/java/com/oakever/sudokuoverlay/
  MainActivity.kt       Main screen, capture flow, OCR, manual review, solving
  CaptureService.kt     One-shot MediaProjection screen capture
  GridDetector.kt      Sudoku grid-line detection and OCR-to-cell mapping
  SudokuSolver.kt       Local 9×9 solver with solution-count checking
  OverlayService.kt     Draggable system floating solution panel
.github/workflows/
  build-apk.yml         GitHub Actions debug APK build
```
