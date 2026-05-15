<div align="center">
  <img width="128" height="128" src="./docs/logo.svg">
</div>

<h1 align="center">PhotoSync</h1>

<div align="center">

![Android](https://img.shields.io/badge/Android-7.0%20%28API%2024%29-brightgreen?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat-square&logo=kotlin&logoColor=purple)
![Gradle](https://img.shields.io/badge/Gradle-8.12-yellow?style=flat-square&logo=gradle)
![NDK](https://img.shields.io/badge/NDK-27.0.12014523-red?style=flat-square&logo=android-ndk)
![CI](https://img.shields.io/github/actions/workflow/status/devcxl/PhotoSync/release.yml?style=flat-square&logo=github-actions)
![Stars](https://img.shields.io/github/stars/devcxl/PhotoSync?style=flat-square&logo=github)

</div>

---

PhotoSync — Android-приложение-компаньон для камеры, созданное для фотографов и энтузиастов обработки изображений. Подключите камеру напрямую через USB — без Wi-Fi, без настольного ПО — и просматривайте, масштабируйте и экспортируйте фотографии RAW/JPEG прямо на телефоне.

## Функции

- **Прямое USB-подключение** — поддержка протокола MTP/PTP; камера подключается к устройству через USB, без сети
- **Декодирование RAW** — на основе LibRaw + lcms2 управление цветом, поддержка 20+ форматов: ARW, CR3, NEF, DNG, RAF и др.
- **Плавный просмотр** — загрузка миниатюр в первую очередь, плиточная отрисовка больших изображений, масштабирование двумя пальцами
- **Экспорт без потерь** — прямое копирование JPEG, RAW декодируется и сохраняется в галерею системы

## Сценарии использования

- Просмотр на месте без разряда батареи камеры
- Быстрый импорт RAW/JPEG на планшет или устройство с большим экраном для просмотра
- Пакетный экспорт фотографий в локальное хранилище — без сжатия, без облака

## Быстрый старт

1. Подключите камеру к телефону или планшету через USB-кабель
2. На камере выберите режим "PC Connection" или "MTP"
3. Откройте PhotoSync — устройство распознается автоматически, можно начинать просмотр и экспорт

---

## Технологический стек

| Категория | Технология |
|-----------|------------|
| Язык | Kotlin 2.0 / C++ / JNI |
| UI | XML + ViewBinding / Jetpack Compose |
| Асинхронность | Coroutines + Flow |
| База данных | Room |
| Обработка изображений | LibRaw 0.21.4 + lcms2 2.18 + OpenCV 4.12.0 |
| Архитектура | MVVM + Clean Architecture |
| Мин. версия | Android 7.0 (API 24) |

## Поддерживаемые форматы RAW

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · и другие

## Структура проекта

```
PhotoSync/
├── app/                    # Основной модуль приложения
│   └── src/main/java/cn/devcxl/photosync/
│       ├── activity/       # Activity, ViewModel
│       ├── adapter/        # RecyclerView / ViewPager адаптеры
│       ├── data/           # Room база данных, DAO, Entity
│       ├── ptp/            # Реализация протокола USB PTP и управление подключением
│       ├── receiver/       # USB широковещательные приёмники
│       └── utils/          # Утилиты
├── libraw/                 # Модуль нативной библиотеки (LibRaw + lcms2)
│   └── src/main/
│       ├── cpp/            # JNI мост + CMake сборка
│       └── java/           # Kotlin-обёртка RawWrapper
└── gradle/
    └── libs.versions.toml  # Управление версиями зависимостей
```

## Сборка

```bash
# Требования: Android SDK, NDK, CMake 3.22+
# Клонирование с подмодулями
git submodule update --init --recursive

# Debug APK
./gradlew assembleDebug

# Release AAB (Google Play)
./gradlew bundleRelease

# Запуск юнит-тестов
./gradlew testDebugUnitTest

# Проверка линтером
./gradlew lintDebug
```

APK генерируется в `app/build/outputs/apk/debug/app-debug.apk`.
Release AAB находится в `app/build/outputs/bundle/release/app-release.aab`.
