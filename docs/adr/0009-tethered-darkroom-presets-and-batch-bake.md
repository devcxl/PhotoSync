# ADR 0009: Tethered darkroom presets, cross-photo paste and the batch bake queue

Status: accepted
Date: 2026-11-23
Scope: `:editor/domain`, `:editor/contract`, `:app/data`, `:app/activity`, `:app/export`

## Context

The editor could grade one photo at a time with no way to reuse the result. A tethered shoot needs
four things the single-photo editor could not express:

1. a saved `Preset` that stays a *parameter set* (never a composition),
2. a `LiveTetherPreset` that every newly synced photo picks up automatically,
3. `CopyMask`-scoped copy/paste between frames of one burst,
4. a background batch queue with a progress card and fixed RAW concurrency.

## Decisions

### 1. A `Preset` stores axes, never composition

`CopyMask().extract(graph)` is the only writer of `user_presets.graph_subset_json`, and
`PresetGraphMerger.applyPreset` is the only reader. Both go through the same mask whose defaults
include `{baseLook, tone, color, detail, effects, portrait.skin}` and exclude
`{geometry.*, portrait.reshape, portrait.body}`.

*Why:* a preset that carried a crop would silently letterbox the next frame, and the failure would only
show after the fact. Keeping one mask means "save as preset", "apply preset" and "paste parameters"
cannot drift apart in what they consider a look.

`PresetGraphMerger.resetPresetAxes` is the inverse: it neutralises exactly the mask's include set and
keeps `geometry`, `portrait.reshape` and `portrait.body`, so `无预设` never destroys the frame's crop or
warp work.

### 2. Built-in presets are seeded rows, not code paths

`BuiltinPresets.all()` returns five `UserPresetRecord`s with deterministic ids
(`builtin_clean_portrait`, `builtin_film_chrome`, `builtin_warm_sunset`, `builtin_cool_nordic`,
`builtin_mono_silver`) and a fixed `createdAt`. `RoomEditStore.seedBuiltinPresets()` inserts them with
`OnConflictStrategy.IGNORE` on every app start.

*Why deterministic ids:* the seed must be idempotent, and an auto-increment key would create five new
rows per launch. *Why rows rather than a hard-coded list:* the preset panel, the quick strip and the
`LiveTetherPreset` all read one `observePresets()` stream, so a built-in and a user preset reach the UI
through the same code path and can be badged or selected identically.

Built-ins are read-only **at the SQL level**: `UserPresetDao.rename` and `deleteById` both carry
`AND is_builtin = 0`. A future refactor that forgets the Kotlin guard therefore cannot mutate the
curated library.

### 3. `LiveTetherPreset` lives in `SharedPreferences`, and the ingest transaction is the API

The active preset id is one process-wide choice, read synchronously from
`pref_live_tether_preset_id` because the USB download callback decides *before* committing whether to
render a preview.

`TetheredPhotoIngestor` encodes the ordering the rest of the app depends on:

1. read the active preset, decode its graph (a missing, corrupt or identity preset degrades to
   "import ungraded" — never to a half-bound edit),
2. render the preview JPEG into a **staging** directory while no photo id exists yet,
3. commit the `PhotoEntity` *and* the `PhotoEditEntity` *and* the preview move in one
   `withTransaction` block,
4. only then publish the photo id.

*Why staging first:* `cacheDir/edits/<photoId>/` is named after the auto-generated id. Rendering after
the insert would leave a window where `MainActivity`'s `AutoFollow` observes the photo with no edit row
and shows the ungraded original on its very first emission.

*Why `BakeConcurrencyGate.rawBakeMutex`:* a RAW bake holds a decoded `RGB16` source plus band FBOs.
Two at once can push a 50MP export past the 512MB budget, so RAW bakes serialise at process scope while
JPEG bakes stay independent.

### 4. The download sink belongs to `App`, not `MainActivity`

`App.onCreate` installs `setFileDownloadPath` and the `FileDownloadedListener`. `MainActivity` no longer
touches either; it subscribes to `App.syncedPhotoEvents` instead of inserting rows itself.

*Why:* a sync that finishes while `EditorActivity` is in the foreground must still be committed with its
preset. A listener owned by the gallery would be `null` (or a second writer) exactly then. This also
means the editor and the gallery observe the same `EditorHostRegistry.tetheredPhotoEvents` stream, so
neither can diverge on what arrived.

### 5. Batch bake is one unique worker, not N jobs

`BatchBakeWorker` processes the whole selection sequentially under the unique name
`photosync_batch_bake` with `ExistingWorkPolicy.APPEND_OR_REPLACE`.

*Why one worker:* progress is a single `正在导出 X/Y…` row; N parallel jobs would need a fan-in reducer
and would also break the RAW concurrency rule from the outside. `APPEND_OR_REPLACE` lets a second
`批量导出…` extend a draining queue instead of replacing it.

`BatchBakeProgress` is a pure value object so the caption (`正在导出`, `已导出`, `已取消`) is
unit-tested without WorkManager.

### 6. Before/After is a gesture state machine plus a pager override

`HoldGestureState` holds the rules (arm on `DOWN`, cancel on slop or a second pointer, fire after
260ms, release on `UP`/`CANCEL`) and is unit-tested. `PressAndHoldDetector` only adds the `Handler`.
`PhotoPagerAdapter.showImageOverride` swaps the page to the cached original; the original is decoded
once into `OriginalPreviewCache`, because decoding on hold would show `Before` a frame late.

## Consequences

- Adding a look axis requires updating `CopyMask` **and** `PresetGraphMerger.resetPresetAxes`; both are
  covered by `CopyMaskTest` and `PresetToolCatalogTest`.
- `user_presets` rows are presets, not history: deleting one never touches the photos that already
  applied it.
- A `LiveTetherPreset` pointing at a deleted preset keeps importing ungraded until the user picks
  another one; `RoomEditStore.deletePreset` clears the preference when the active preset is removed.
- The RAW concurrency gate is process-wide. Two `BakeExporter` instances cannot overlap, by design.

## Verification

- `editor/src/test/.../domain/CopyMaskTest.kt` — default exclusions, custom inclusions, preset axes
  reset, sanitisation of hostile rows.
- `editor/src/test/.../domain/BuiltinPresetsTest.kt` — JSON round-trip, non-identity, no composition.
- `editor/src/test/.../ui/panels/PresetToolCatalogTest.kt` — the `预设` group is a real tool; checklist
  mapping.
- `app/src/test/.../data/RoomEditStoreTest.kt` — preset save/rename/delete, built-in protection,
  `LiveTetherPreset` persistence.
- `app/src/test/.../data/TetheredPhotoIngestorTest.kt` — auto-apply, duplicate `-1L`, no preset,
  camera handle.
- `app/src/test/.../adapter/PhotoPagerCompareDecisionTest.kt` — hold/cancel rules.
- `app/src/test/.../export/BatchBakeProgressTest.kt` — captions and ratio clamping.
