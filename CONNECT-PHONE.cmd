@echo off
setlocal
set "SH_ADB="
where adb >nul 2>&1
if not errorlevel 1 set "SH_ADB=adb"
if not defined SH_ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "SH_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined SH_ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SH_ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined SH_ADB goto missing_adb
"%SH_ADB%" reverse tcp:3000 tcp:3000
if errorlevel 1 goto failed
"%SH_ADB%" reverse tcp:5173 tcp:5173
if errorlevel 1 goto failed
echo.
echo Phone connected to the laptop's backend and dashboard.
echo In Android Studio, select the dev build variant, then click Run.
echo Optional: open http://localhost:5173 in the phone's browser.
pause
exit /b 0
:missing_adb
echo Install Android Studio's SDK Platform-Tools, then try again.
echo If your SDK is in another folder, set ANDROID_HOME to that folder.
pause
exit /b 1
:failed
echo Connect one phone by USB, enable USB debugging, and accept its computer prompt.
echo Then run CONNECT-PHONE.cmd again. See LOCAL-DEVELOPMENT.md.
pause
exit /b 1
