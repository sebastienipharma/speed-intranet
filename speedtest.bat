@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "PS1_FILE=%SCRIPT_DIR%speedtest-win.ps1"

if not exist "%PS1_FILE%" (
  echo [ERREUR] Fichier introuvable: %PS1_FILE%
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1_FILE%" %*
exit /b %ERRORLEVEL%
