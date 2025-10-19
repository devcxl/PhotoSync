
# Copilot Instructions for Android (Kotlin + Java) Project

## Overview

This document defines unified **Android coding, structure, and style conventions** that GitHub Copilot and other AI assistants must follow when generating, optimizing, or reviewing project code.

Copilot acts as an **experienced Android architect**, enforcing consistent Kotlin/Java standards, SOLID principles, and Android best practices.

---

## 1. Coding Standards

### 1.1 Language & Compatibility

* Prefer **Kotlin** for all new modules and components.
* Java is allowed for legacy modules or interop code.
* Target **Android API 24+** (Android 7.0) unless otherwise specified.

### 1.2 Kotlin Style Guide

* Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
* Use **Android Studio’s official formatter**.
* Max line length: **120 chars**.
* Indentation: **4 spaces**.
* Always use `val` instead of `var` when possible (immutability first).

### 1.3 Naming Conventions

| Entity               | Convention                 | Example                       |
| -------------------- | -------------------------- | ----------------------------- |
| Class                | PascalCase                 | `UserProfileViewModel`        |
| Function / Variable  | lowerCamelCase             | `loadUserData()`              |
| Constant             | UPPER_CASE                 | `MAX_RETRY_COUNT`             |
| Resource (layout/id) | snake_case                 | `activity_main`, `btn_submit` |
| Package              | lowercase (no underscores) | `com.example.core`            |

### 1.4 Code Structure & Readability

* Functions ≤ **50 effective lines** (excluding comments and imports).
* Classes ≤ **300 lines**; prefer composition over inheritance when expanding.
* Use meaningful names; avoid abbreviations.
* No hardcoded strings or colors — always use `strings.xml` and `colors.xml`.
* Each class should have **one level of abstraction**.

### 1.5 Import Order

```kotlin
// Kotlin standard library
import kotlin.math.*

// Android framework
import android.view.*

// Third-party libraries
import androidx.lifecycle.*

// Project-specific imports
import com.example.app.core.utils.Logger
````

---

## 2. Project Structure

```
project_root/
│
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/example/app/
│   │   │   │   ├── App.kt
│   │   │   │   ├── di/
│   │   │   │   ├── core/
│   │   │   │   ├── data/
│   │   │   │   ├── domain/
│   │   │   │   └── ui/
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       ├── drawable/
│   │   │       ├── values/
│   │   │       └── mipmap/
│   │   └── test/java/com/example/app/
│   │       └── ExampleUnitTest.kt
│   │
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── .gitignore
```

### 2.1 Clean Architecture Layering

| Layer    | Responsibility             | Typical Contents                               |
| -------- | -------------------------- | ---------------------------------------------- |
| `data`   | Data access implementation | Repositories, DataSources, DTOs                |
| `domain` | Business logic layer       | UseCases, Entities, Interactors                |
| `ui`     | Presentation layer         | Activities, Fragments, ViewModels, Composables |
| `core`   | Shared core utilities      | Network, Utils, Extensions                     |
| `di`     | Dependency injection       | Hilt modules, Koin modules                     |

---

## 3. Documentation & Comments

* Use **KDoc** format for all public classes, functions, and properties.

```kotlin
/**
 * Fetches user data from the repository.
 *
 * @param userId The ID of the user to fetch.
 * @return A [User] instance with full profile data.
 */
suspend fun fetchUser(userId: Int): User
```

* Inline comments should clarify non-trivial logic only.
* Each module should have a short `README.md` describing its purpose and dependencies.

---

## 4. Testing Standards

* Use **JUnit5**, **Robolectric**, and **MockK** for unit testing.
* Use **Espresso** or **Compose Testing** for UI tests.
* Minimum coverage: **80%**, verified via `jacocoTestReport` and `jacocoTestCoverageVerification`.
* Test naming convention:

    * `should<ExpectedBehavior>_when<Condition>()`
    * Example: `shouldReturnUser_whenRepositoryHasCachedData()`
* Tests must not depend on network or file I/O — use `MockWebServer` or `FakeRepository`.

---

## 5. Dependencies & Configuration

* Use **Gradle Kotlin DSL** (`build.gradle.kts`) for build scripts.
* Use **Hilt** or **Koin** for dependency injection.
* Access build-time constants via `BuildConfig` or `local.properties`, never hardcode secrets.
* Provide `local.properties.example` for environment variables.

```kotlin
val apiKey = BuildConfig.API_KEY ?: ""
```

---

## 6. Dependency Management

* All dependency versions are defined in `gradle/libs.versions.toml` (Version Catalogs).
* No inline version declarations inside build.gradle files.
* Update dependencies quarterly or when security issues arise.
* Avoid direct GitHub or local `.aar` references unless strictly required.

---

## 7. Git Commit Guidelines

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(ui): add profile screen with data binding
fix(data): correct null response handling
refactor(core): move network layer to module
test: add repository mocking tests
docs: update API usage instructions
```

Never commit `local.properties`, keystore files, or build outputs.

---

## 8. CI/CD Quality Gates

All CI pipelines must include:

* `ktlint` — style and linting
* `detekt` — static analysis
* `unitTestDebugUnitTest` — test coverage ≥ 80%
* `lintDebug` — Android lint
* `gradle build` — release build verification

Builds **must fail** if any check does not pass.

### 8.1 Build & Release

* All CI builds generate **versioned artifacts** using semantic versioning (`1.2.3`).
* Git tag or commit hash is appended for internal builds.
* Release builds must be signed with `release.keystore` (not tracked in VCS).

---

## 9. AI Assistant Behavior (for Copilot & Chat)

When generating or editing Android code, Copilot must:

* Enforce Kotlin style conventions and architecture consistency.
* Add full KDoc for all public symbols.
* Prefer **MVVM + Clean Architecture** for feature design.
* Respect package hierarchy (`data`, `domain`, `ui`, `di`, `core`).
* Suggest required Gradle dependencies if new libraries are implied.
* Avoid deprecated APIs; use Jetpack or modern equivalents.
* Never produce partial, placeholder, or pseudo-code unless explicitly requested.
* Always ensure generated code **compiles successfully**.
* If user code conflicts with this document, **prioritize these conventions**.

---

## 10. Ignore Directories

Copilot must never edit or suggest code inside:

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
```

---

## 11. SOLID Design Principles

1. **Single Responsibility Principle**
   Each class or ViewModel should handle one well-defined function (UI, data mapping, etc.).

2. **Open/Closed Principle**
   Extend functionality without modifying existing logic.

3. **Liskov Substitution Principle**
   Subclasses must behave consistently with their parent abstractions.

4. **Interface Segregation Principle**
   Prefer small, specific interfaces over large monolithic ones.

5. **Dependency Inversion Principle**
   High-level modules depend on abstractions, not concrete implementations.

---

## 12. Structural & Complexity Control

* Keep ViewModels and Activities thin — delegate logic to UseCases or repositories.
* Avoid duplicate logic; abstract shared functionality.
* Maintain one abstraction level per class.
* Use reactive paradigms (`Flow`, `StateFlow`, `LiveData`).
* Code must remain **readable, modular, and testable**.

---

## 13. Logging & Error Handling

* Use **Timber** for logging; avoid direct `Log.d/e` calls.
* Never log sensitive data (tokens, passwords).
* Use `Result` or sealed classes for error handling in UseCases.
* Integrate **Firebase Crashlytics** for crash reporting.
* Ensure meaningful error messages for both users and developers.

---

## 14. Coroutines & Threading Policy

* Use Kotlin **coroutines** with structured concurrency.
* Recommended dispatchers:

    * `Dispatchers.IO` — for I/O operations and repositories
    * `Dispatchers.Default` — for CPU-bound work
    * `Dispatchers.Main` — for UI updates
* Never block the main thread.
* Always cancel coroutines appropriately (e.g., in `onCleared()` for ViewModels).

---

## 15. UI Framework Policy

* Prefer **Jetpack Compose** for new UI modules.
* Use XML-based UI only for legacy components.
* Compose code must follow **unidirectional data flow (UDF)**.
* Avoid mixing Compose and XML in the same screen.

---

## 16. Internationalization

* All user-facing strings must be defined in `strings.xml`.
* Use plural resources and placeholders where applicable.
* Default locale: English.
* Support additional locales as required by product specs.

---

## Version

**Version:** 1.1.0
**Last Updated:** 2025-10-19
**Maintained by:** Android Architecture Team

