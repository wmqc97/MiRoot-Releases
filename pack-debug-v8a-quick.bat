@echo off
setlocal
cd /d "%~dp0"

REM 日常快速 Debug：不 clean、不禁用构建缓存；依赖用 %USERPROFILE%\.gradle\caches，一般不会重复下载
call gradlew.bat :app:assembleDebug
if errorlevel 1 exit /b 1

echo.
echo 标准 APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
echo 命名分发包 ^(应用名_版本_debug.apk^):
dir /b "%~dp0app\build\outputs\apk\debug\*_debug.apk" 2>nul
exit /b 0
