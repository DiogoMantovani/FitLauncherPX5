@echo off
setlocal
cd /d "%~dp0.."
where gradle >nul 2>&1
if errorlevel 1 (
    echo Gradle nao encontrado. Utilize Android Studio ou o workflow GitHub Actions incluido.
    exit /b 1
)
if not defined ANDROID_HOME if not defined ANDROID_SDK_ROOT (
    echo Android SDK nao configurado. Utilize Android Studio ou GitHub Actions.
    exit /b 1
)
call gradle --no-daemon :app:assembleDebug
if errorlevel 1 exit /b 1
echo APK gerado em app\build\outputs\apk\debug\app-debug.apk
