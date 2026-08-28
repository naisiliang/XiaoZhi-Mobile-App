@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0PUSH_TO_GITHUB.ps1"
set "EXITCODE=%ERRORLEVEL%"
echo.
if not "%EXITCODE%"=="0" (
  echo Upload script failed with exit code %EXITCODE%.
) else (
  echo Upload script finished successfully.
)
pause
exit /b %EXITCODE%
