@echo off
setlocal
cd /d "%~dp0"
set "NO_PAUSE=0"
if /i "%~1"=="/nopause" set "NO_PAUSE=1"
set "EXIT_CODE=0"

REM Release packaging: stop Gradle, delete app\build, then assembleRelease.
call gradlew.bat --stop >nul 2>nul

if exist "%~dp0app\build" (
  echo Cleaning "%~dp0app\build" ...
  rmdir /s /q "%~dp0app\build" >nul 2>nul
  if exist "%~dp0app\build" (
    timeout /t 2 /nobreak >nul
    rmdir /s /q "%~dp0app\build" >nul 2>nul
  )
  if exist "%~dp0app\build" (
    echo.
    echo ERROR: Unable to delete "app\build".
    echo Close Android Studio/File Explorer/Antivirus and retry.
    set "EXIT_CODE=1"
    goto :END
  )
)

call gradlew.bat :app:assembleRelease --no-daemon --no-build-cache
if errorlevel 1 (
  echo.
  echo ERROR: Gradle build failed.
  set "EXIT_CODE=1"
  goto :END
)

echo.
echo Dist APK path: %~dp0app\build\outputs\apk\release\*_re.apk
dir /b "%~dp0app\build\outputs\apk\release\*_re.apk" 2>nul
echo.
echo Gradle default: %~dp0app\build\outputs\apk\release\app-release.apk

:END
echo.
if "%EXIT_CODE%"=="0" (
  echo Done. ExitCode=0
) else (
  echo Failed. ExitCode=%EXIT_CODE%
)
if not "%NO_PAUSE%"=="1" pause
exit /b %EXIT_CODE%
