@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 goto missing_node
node -e "if (Number(process.versions.node.split('.')[0]) < 24) process.exit(1)"
if errorlevel 1 goto missing_node
call npm --prefix backend ci --include=dev
if errorlevel 1 goto failed
call npm --prefix backend run setup:dev
if errorlevel 1 goto failed
call npm --prefix dashboard ci --include=dev
if errorlevel 1 goto failed
echo.
echo Setup complete. Keep the dashboard password printed above.
echo Next: double-click START-DEV.cmd.
pause
exit /b 0
:missing_node
echo Install Node.js 24 or newer from https://nodejs.org/en/download
pause
exit /b 1
:failed
echo Setup stopped. Read the error above, fix it, and run this file again.
pause
exit /b 1
