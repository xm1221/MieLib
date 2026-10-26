@echo off
rem HexCLI one-click installer - Windows wrapper.
rem Runs install.ps1 with a process-scoped execution policy so the user's machine settings
rem are not changed. All arguments are forwarded (e.g. install.bat -y, install.bat -Dir server).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "HEXCLI_EXIT=%ERRORLEVEL%"
if not "%HEXCLI_EXIT%"=="0" (
    echo.
    echo HexCLI install failed with exit code %HEXCLI_EXIT%. See the messages above.
    if not defined HEXCLI_NO_PAUSE pause
)
exit /b %HEXCLI_EXIT%
