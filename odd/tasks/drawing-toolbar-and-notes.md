# DraftBeam drawing toolbar, notes, and selection correction
Feature: toolbar actions, manual wall creation, selectable DXF notes, and short-line hit testing.
Route: inline ODD tasks on `feature/wall-interactions`; update this file and its Engram mirror with every task transition.

## Specs
1. S1. User: "en los botones de la barra inferior, añade el siguiente orden de botones,\n1.- nueva linea\n2.- editar (se habilita si se selecciona un linea)\n3.- eliminar  (se habilita si se selecciona un linea)\n4.- ancho  (se habilita si se selecciona un linea)\n5.- agregar nota" Preserve the existing Deshacer action as well, after these five actions.
2. S2. User: "Debe abrir una tarjeta com la de muro"; "Largo + dirección actual"; "1.- que tambien aparezca el ancho y la dirección del muro"; selected direction meaning: "Lado del ancho". The new-wall card uses current active point and drawing direction, and exposes length, width, and wall-width growth side.
3. S3. User: "Escribir y tocar el plano"; "Sí, guardar y reabrir"; "quiero que se editen y se eliminen tambiem seleccionandolas". Notes are entered as text, placed by tapping the plan, saved/reloaded through DXF, and selectable for editing or deletion.
4. S4. User: "encontré un error, No puedo seleccionar una linea que mide 40 mm". Correct wall selection for short lines while retaining endpoint continuation selection.

## Tasks
- T1 [S4] inline: done; added short-wall tap priority test (helper RED; focused `PlanPreviewHitTestingTest` GREEN); implementation distinguishes short-line body taps from endpoints while retaining endpoint targets; canvas commit `715d0cd`.
- T2 [S1-S2] inline: done; ordered six toolbar actions plus Deshacer; Edit/Delete/Width require a wall selection, Width focuses the editor field, and Nueva línea opens a card for length, width, current angle and growth side. Geometry test passed; Activity commit `5ea309d`.
- T3 [S3] inline: done; notes support typed entry, tap-to-place, selection, edit, delete, undo, DXF TEXT round-trip and note-aware fit-to-content. Core/domain commit `6fa994a`, canvas commit `715d0cd`, Activity commit `5ea309d`. Focused codec, unit, hit-test and fit-to-content tests passed.
- T4 [S3-S4] inline: done; canvas note drawing, hit testing and note-aware fit committed in `715d0cd`; optional callback defaults preserve existing `PlanPreview` callers.
- T5 [S1-S3] inline: done; Activity toolbar, wall/note workflows and user documentation committed in `5ea309d`.
- T6 [S1-S4] inline: done; final source hashes, integrated checks, diff check, and device-test limitation recorded in this file and Engram; evidence commit `e0e185e`.

## Log
- L1. User request: "en los botones de la barra inferior, añade el siguiente orden de botones,\n1.- nueva linea\n2.- editar (se habilita si se selecciona un linea)\n3.- eliminar  (se habilita si se selecciona un linea)\n4.- ancho  (se habilita si se selecciona un linea)\n5.- agregar nota"
- L2. User clarification: "1.- que tambien aparezca el ancho y la dirección del muro\n2.- quiero que se editen y se eliminen tambiem seleccionandolas\nencontré un error, No puedo seleccionar una linea que mide 40 mm"
- L3. User decisions: new line opens a wall-like card, uses the current direction, and includes width plus growth side; note is entered then placed by tapping the plan, saved and reloaded via DXF, and selectable for editing/deletion; preserve Deshacer.
- L4. Verification: focused DxfCodecTest, PlanViewportStateTest, PlanPreviewNotesTest, PlanPreviewHitTestingTest and PlanGeometryTest passed. Final `./gradlew testDebugUnitTest assembleDebug lintDebug` returned `BUILD SUCCESSFUL` (15s) after the optional `PlanPreview` parameters were added; existing `connectGatt` deprecation warning remains. Staged and final `git diff --check` passed. No physical-device/tactile test was run.
- L5. Source work-unit commits on `feature/wall-interactions`: `6fa994a` (`feat(dxf): round-trip note text and wall geometry`), `715d0cd` (`feat(canvas): add note hit testing and short-line selection`), and `5ea309d` (`feat(ui): add drawing toolbar and editable DXF notes`). No push was performed; the four pre-existing `android/Design/` images remain untracked and untouched.
