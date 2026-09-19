# AGENTS.md - PhotoSync Project Guidelines

This file provides coding conventions and operational instructions for AI agents working in the PhotoSync codebase.

---

## 1. Build Commands

### Gradle Wrapper
```bash
./gradlew [task]
```

### Common Tasks
```bash
./gradlew build                  # Full debug build
./gradlew assembleRelease        # Release build
./gradlew clean                  # Clean build artifacts
./gradlew lint                  # Run Android lint
./gradlew lintDebug              # Lint debug variant
```

### Running Tests
```bash
./gradlew testDebugUnitTest                    # All unit tests
./gradlew testDebugUnitTest --tests "cn.devcxl.photosync.activity.ExampleUnitTest.shouldUseTiledViewer_whenIsJpegAndUsePhotoViewOtherwise"  # Single test
./gradlew connectedAndroidTest                 # Instrumented tests (device/emulator required)
./gradlew testDebugUnitTest --info             # Verbose test output
```

### Build Variants
```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (requires signing config)
```

### Code Quality
```bash
./gradlew lintDebug              # Android lint (runs as part of the quality gate)
```

> `ktlint` and `detekt` are not configured in this repository; do not add them to the
gate without wiring the plugins first.

---

## 2. Project Structure

```
PhotoSync/
├── app/                            # Application module (UI, Room, PTP client)
│   ├── src/
│   │   ├── main/java/cn/devcxl/photosync/
│   │   │   ├── activity/         # UI layer - Activities
│   │   │   ├── adapter/           # RecyclerView adapters, render decisions
│   │   │   ├── data/             # Data layer - Room DB, DAOs, Entities
│   │   │   ├── ptp/               # USB PTP protocol implementation
│   │   │   │   ├── detect/        # CameraDetector (vendor detection)
│   │   │   │   ├── interfaces/    # Transfer/download listener interfaces
│   │   │   │   ├── manager/       # UsbPtpConnectionController, SyncDeviceManager
│   │   │   │   ├── params/        # SyncParams constants
│   │   │   │   └── usbcamera/     # PTP core + per-vendor initiators (eos/nikon/sony)
│   │   │   ├── receiver/         # Broadcast receivers
│   │   │   ├── utils/             # Utility extensions
│   │   │   └── App.kt             # Application class
│   │   ├── test/java/cn/devcxl/photosync/  # Unit tests (JUnit4, JVM only)
│   │   └── androidTest/java/cn/devcxl/photosync/  # Instrumented tests
│   └── build.gradle
├── libraw/                         # Native library module (LibRaw + lcms2 via CMake + JNI)
│   ├── src/main/cpp/               # CMakeLists.txt, libraw_jni.cpp, third_party/ submodules
│   ├── src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt
│   └── build.gradle
├── gradle/
│   ├── libs.versions.toml         # Version catalog for dependencies
│   └── wrapper/                    # Gradle wrapper (pinned via gradle-wrapper.properties)
├── build.gradle                   # Root build config
├── settings.gradle
└── gradle.properties
```

> RAW decoding lives in the `:libraw` module, not in `app`. `RawWrapper` is re-exported
> under the package `cn.devcxl.photosync.wrapper`, so app-side imports are unchanged.
> The `:libraw` sources under `src/main/cpp/third_party/` are git submodules: run
> `git submodule update --init --recursive` after a fresh clone or the native build fails.

### Architecture Pattern
- **Clean Architecture** with MVVM for presentation
- Layers: `ui/activity` → `data/` → (Room DB / USB PTP)
- Coroutines + Flow for async operations

---

## 3. Code Style Guidelines

### Language & Compatibility
- **Kotlin** for all new code; Java allowed for legacy/interop
- Target **API 24+** (Android 7.0)
- JVM target: **Java 17**

### Formatting
- **4 spaces** indentation (no tabs)
- **Max line length: 120 characters**
- Use `ktlint` or Android Studio formatter
- One blank line between declarations (functions, classes)

### Naming Conventions
| Entity | Convention | Example |
|--------|-----------|---------|
| Class | PascalCase | `MainActivity`, `PhotoPagerAdapter` |
| Function/Variable | lowerCamelCase | `loadPhoto()`, `thumbnailCache` |
| Constant | UPPER_CASE | `MAX_RETRY_COUNT` |
| Package | lowercase | `cn.devcxl.photosync` |
| Resource (layout/id) | snake_case | `activity_main`, `btn_export` |
| Enum | PascalCase | `PhotoRenderStage.FULL` |

### Import Order
```kotlin
// 1. Kotlin standard library
import kotlin.math.*

// 2. Android framework
import android.view.*
import android.hardware.usb.*

// 3. Third-party libraries
import androidx.lifecycle.*
import androidx.room.*
import timber.log.Timber

// 4. Project-specific imports
import cn.devcxl.photosync.adapter.*
import cn.devcxl.photosync.data.entity.*
```

### Code Structure Rules
- **Functions**: ≤ 50 effective lines (excluding imports/comments)
- **Classes**: ≤ 300 lines; prefer composition over inheritance
- **One level of abstraction** per function
- Use meaningful names; avoid abbreviations (except standard ones like `Bitmap`, `URL`)

### Kotlin Idioms
- Use `val` over `var` (immutability first)
- Use `lateinit` only when necessary; prefer `by lazy`
- Use sealed classes for state modeling
- Avoid `!!` operator; use safe calls (`?.`) or elvis (`?:)
- Use `when` over `if-else` chains for multiple conditions

### Error Handling
- Use `try-catch` for expected recoverable errors
- Catch specific exceptions (`OutOfMemoryError` not bare `Exception`)
- Return `null` or `Result<T>` for failure cases; never throw silently
- Log errors with Timber: `Timber.e(t, "message")` - include context

### Logging Policy
- **Use Timber** (`timber.log.Timber`), never `android.util.Log`
- Never log sensitive data (tokens, passwords, paths)
- Log levels: `Timber.d` (debug), `Timber.i` (info), `Timber.w` (warning), `Timber.e` (error)

---

## 4. Documentation Standards

### KDoc for Public APIs
```kotlin
/**
 * Fetches thumbnail bitmap for the specified photo path.
 *
 * @param path Absolute file path to the photo.
 * @return Decoded [Bitmap] or null if decoding fails.
 * @throws IllegalArgumentException if path is blank.
 */
fun decodeThumbnailBitmap(path: String): Bitmap?
```

### Inline Comments
- Explain **why**, not what
- Non-trivial logic only
- No commented-out code

---

## 5. Testing Standards

### Test Naming
```
should<ExpectedBehavior>_when<Condition>
Example: shouldReturnUser_whenRepositoryHasCachedData()
```

### Unit Test Rules
- Use **JUnit4** + **MockK** for mocking
- Tests must not depend on network/file I/O; use fakes or mocks
- One assertion concept per test (multiple `assertEquals` for same concept OK)
- Located in `app/src/test/java/`

### Instrumented Tests
- Use **Espresso** for UI testing
- Located in `app/src/androidTest/java/`
- Require device/emulator: run via `connectedAndroidTest`

### Test Utilities
```kotlin
@VisibleForTesting
internal fun calculateInSampleSize(...) { ... }
```

---

## 6. Dependency Management

### Version Catalog
All dependency versions are centralized in `gradle/libs.versions.toml`.
**Do not** inline version numbers in `build.gradle` files.

### Adding Dependencies
1. Add version to `libs.versions.toml` under `[versions]` and `[libraries]`
2. Reference via `libs.<name>` in `build.gradle`

### Build Config
- Access build-time constants via `BuildConfig` or `local.properties`
- Never hardcode secrets in source code

---

## 7. Git Commit Convention (Conventional Commits)

```
feat(ui): add swipe-to-delete functionality
fix(data): correct null response handling in PhotoDao
refactor(core): move extension utils to separate file
test: add repository mocking tests for UsbPtpConnectionController
docs: update API usage instructions
```

---

## 8. Directories AI Must Not Modify

```
/build/
/.gradle/
/.idea/
/captures/
/outputs/
/generated/
/libs/
/release/
/keystore/
/local.properties
```

---

## 9. Threading & Coroutines

### Dispatcher Usage
- `Dispatchers.IO` — file I/O, USB operations, Room queries
- `Dispatchers.Default` — CPU-bound work (image processing)
- `Dispatchers.Main` — UI updates

### Lifecycle Awareness
- Use `lifecycleScope.launch` with `repeatOnLifecycle` for collectors
- Cancel coroutines in `onDestroy()` or `onCleared()`

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        dao.getAllFlow().collect { list -> ... }
    }
}
```

---

## 10. Room Database

### Entity Conventions
- Primary key: `id: Long = 0` (auto-generate)
- Use `@ColumnInfo(name = "path")` for custom column names
- DAO methods: `suspend` for write operations, `Flow` for queries

### Example
```kotlin
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val name: String?,
    @ColumnInfo(name = "uri_string") val uriString: String?
)
```

---

## 11. UI Framework

- **XML with ViewBinding** for all screens (`activity_main.xml` is the only one).
- There is **no Jetpack Compose** in this project: the Compose dependencies and the
  Compose compiler plugin were removed. Do not reintroduce them without also adding
  actual Compose screens; a declared-but-unused Compose toolchain is not a valid setup.
- Use `ViewPager2` + `RecyclerView` for the photo pager, `PhotoView` for zoom/pan on
  bitmaps, and `SubsamplingScaleImageView` for tiled JPEG rendering.

---

## 12. SOLID Principles

1. **Single Responsibility**: One class = one well-defined purpose
2. **Open/Closed**: Extend via composition, not modification
3. **Liskov Substitution**: Subclasses honor parent contracts
4. **Interface Segregation**: Small, specific interfaces over large ones
5. **Dependency Inversion**: Depend on abstractions (interfaces), not concretions

---

## 13. Code Review Checklist

Before completing any change:
- [ ] Code compiles: `./gradlew assembleDebug`
- [ ] All unit tests pass: `./gradlew testDebugUnitTest`
- [ ] Lint passes: `./gradlew lintDebug`
- [ ] No hardcoded strings (use `strings.xml`)
- [ ] No sensitive data in logs
- [ ] Public APIs have KDoc
- [ ] Coroutines properly scoped/cancelled
- [ ] Error cases handled (no silent failures)

---

## 14. Special Notes for PhotoSync

### USB PTP Connection
- `UsbPtpConnectionController` manages USB device lifecycle
- States: `Idle`, `Connecting`, `Connected`, `Disconnected`, `Error`, `PermissionRequested`
- File download path: `externalCacheDir`

### Image Loading Priority
1. **Thumbnail** (512px max edge) - loaded first for fast scrolling
2. **Full Preview** (scaled to screen) - loaded for current page only
3. **RAW decoding** via `RawWrapper` (LibRaw wrapper) for `.arw`, `.dng`, etc.

### Export Behavior
- **JPEG**: Direct file copy to gallery (no re-encoding)
- **RAW**: Decode to JPEG via `RawWrapper`, then save
- Both paths funnel through `MainActivity.saveToGallery`, which handles the
  `MediaStore` + `IS_PENDING` dance on API 29+ and the legacy direct-write path below it.

### Logging
- Use **Timber** everywhere. `android.util.Log` and `printStackTrace()` are not used in
  this codebase; pass throwables as Timber's first argument so stack traces survive.

### Ignore Files
- `.secrets` - local secrets (never commit)
- `local.properties` - SDK paths, signing config
