@echo off
setlocal
cd /d "%~dp0"

REM 日常快速 Release：不 clean、不禁用构建缓存；R8 + 资源缩减仍按 app/build.gradle.kts release
call gradlew.bat :app:assembleRelease
if errorlevel 1 exit /b 1

echo.
echo 【分发包】%~dp0app\build\outputs\apk\release\ ^(应用名_版本_re.apk^)
dir /b "%~dp0app\build\outputs\apk\release\*_re.apk" 2>nul
echo.
echo 【Gradle 默认】%~dp0app\build\outputs\apk\release\app-release.apk
exit /b 0
