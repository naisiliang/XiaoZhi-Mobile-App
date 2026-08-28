@echo off
setlocal
set /p "PROXYPORT=Enter local HTTP/Mixed proxy port [7890]: "
if "%PROXYPORT%"=="" set "PROXYPORT=7890"
set "XIAOZHI_GIT_PROXY=http://127.0.0.1:%PROXYPORT%"
echo Using proxy for this upload only: %XIAOZHI_GIT_PROXY%
echo.
call "%~dp0PUSH_TO_GITHUB.bat"
exit /b %ERRORLEVEL%
