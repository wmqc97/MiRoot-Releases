@echo off
setlocal
cd /d "%~dp0"

REM Debug：先 clean + 本次构建禁用构建缓存；arm64-v8a 见 defaultConfig.ndk
call gradlew.bat clean --no-daemon
if errorlevel 1 exit /b 1
call gradlew.bat :app:assembleDebug --no-daemon --no-build-cache
if errorlevel 1 exit /b 1

echo.
echo 标准 APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
echo 命名分发包 ^(应用名_版本_debug.apk^):
dir /b "%~dp0app\build\outputs\apk\debug\*_debug.apk" 2>nul
exit /b 0
