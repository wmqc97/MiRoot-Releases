@echo off
setlocal
cd /d "%~dp0"

REM Release：先 clean + 本次构建禁用构建缓存；arm64-v8a、R8 混淆 + 资源缩减见 app/build.gradle.kts
call gradlew.bat clean --no-daemon
if errorlevel 1 exit /b 1
call gradlew.bat :app:assembleRelease --no-daemon --no-build-cache
if errorlevel 1 exit /b 1

echo.
echo 【分发包】%~dp0app\build\outputs\apk\release\ ^(应用名_版本_re.apk^)
dir /b "%~dp0app\build\outputs\apk\release\*_re.apk" 2>nul
echo.
echo 【Gradle 默认】%~dp0app\build\outputs\apk\release\app-release.apk
exit /b 0
