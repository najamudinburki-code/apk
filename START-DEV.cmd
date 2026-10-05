@echo off
setlocal
cd /d "%~dp0"
node tools\local-dev.cjs
if errorlevel 1 pause
