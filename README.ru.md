<p align="center">
  <img width="128" height="128" src="data:image/svg+xml,%3Csvg%20width%3D%22512%22%20height%3D%22512%22%20viewBox%3D%220%200%20512%20512%22%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%3E%3Crect%20width%3D%22512%22%20height%3D%22512%22%20rx%3D%2264%22%20fill%3D%22%23F7F4EE%22%2F%3E%3Cpath%20d%3D%22M%20353.5%20412.0%20A%20184%20184%200%201%201%20408.5%20358.9%22%20fill%3D%22none%22%20stroke%3D%22%23D4D4D4%22%20stroke-width%3D%2222%22%20stroke-linecap%3D%22round%22%2F%3E%3Cpath%20d%3D%22M%20408%20333.0%20L%20408.5%20358.9%20L%20431.5%20346%22%20fill%3D%22none%22%20stroke%3D%22%23D4D4D4%22%20stroke-width%3D%2222%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%2F%3E%3Ccircle%20cx%3D%22256%22%20cy%3D%22256%22%20r%3D%22144%22%20fill%3D%22%233F7FDC%22%2F%3E%3Ccircle%20cx%3D%22428%22%20cy%3D%2275%22%20r%3D%2221%22%20fill%3D%22%23EA4335%22%2F%3E%3C%2Fsvg%3E">
</p>

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

Android-приложение для синхронизации и предпросмотра фотографий с камеры по USB PTP. Подключение к камерам напрямую через USB Host, локальное кэширование RAW/JPEG, поддержка свайп-просмотра, масштабирования, декодирования RAW и экспорта.

## Функции

- **Прямое подключение по USB PTP** — поддержка протокола MTP/PTP для подключения камер с автоматическим обнаружением устройств
- **Декодирование RAW** — на основе LibRaw + lcms2 управление цветом, поддержка 20+ форматов RAW (ARW, CR3, NEF, DNG, RAF и др.)
- **Плавный просмотр** — загрузка миниатюр в первую очередь + двухслойный растровый кэш и плиточная отрисовка больших изображений
- **Масштабирование** — масштабирование двумя пальцами и панорамирование для просмотра фотографий с высоким разрешением
- **Экспорт фотографий** — прямое копирование JPEG (без потерь), RAW декодируется и экспортируется в галерею системы

## Технологический стек

| Категория | Технология |
|-----------|------------|
| Язык | Kotlin 2.0 / Java |
| UI | XML + ViewBinding / Jetpack Compose |
| Асинхронность | Coroutines + Flow |
| База данных | Room |
| Обработка изображений | LibRaw 0.21.4 + lcms2 2.18 + OpenCV 4.12.0 |
| Архитектура | MVVM + Clean Architecture |
| Мин. версия | Android 7.0 (API 24) |

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

# Запуск юнит-тестов
./gradlew testDebugUnitTest

# Проверка линтером
./gradlew lintDebug
```

APK генерируется в `app/build/outputs/apk/debug/app-debug.apk`.

## Поддерживаемые форматы RAW

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · и другие

## Лицензия

- **LibRaw** — двойная лицензия LGPL 2.1 / CDDL 1.0
- **Little-CMS** — MIT
