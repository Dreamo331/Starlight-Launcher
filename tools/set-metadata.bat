@echo off
chcp 65001>nul
setlocal EnableDelayedExpansion
cd /d "%~dp0.."

set "EXE=target\gluonfx\x86_64-windows\Starlight Launcher.exe"
set "META=src\windows\assets\metadata.txt"
set "RCEDIT=tools\rcedit-x64.exe"

rem ---- defaults ----
set "Icon=src\windows\assets\icon.ico"
set "FileDescription=Starlight Launcher"
set "ProductName=Starlight Launcher"
set "CompanyName="
set "LegalCopyright="
set "FileVersion=1.0.0.0"
set "ProductVersion=1.0.0.0"
rem ----------------

if not exist "%EXE%" (
    echo [ERROR] EXE not found: %EXE%
    exit /b 1
)

if exist "%META%" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0parse-meta.ps1" -Meta "%META%" -Out "%TEMP%\sl_meta.bat"
    if errorlevel 1 ( echo [ERROR] parse metadata failed & exit /b 1 )
    call "%TEMP%\sl_meta.bat"
    del /q "%TEMP%\sl_meta.bat" >nul 2>&1
) else (
    echo [WARN] metadata.txt not found: %META%
)

for %%I in ("%Icon%") do set "ICON_FULL=%%~fI"
if not exist "%ICON_FULL%" (
    echo [ERROR] Icon file not found: %ICON_FULL%
    exit /b 1
)

if not exist "%RCEDIT%" (
    echo [INFO] rcedit not found, downloading...
    if not exist "tools" mkdir "tools"
    powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe' -OutFile 'tools\rcedit-x64.exe' } catch { exit 1 }"
    if errorlevel 1 ( echo [ERROR] rcedit download failed & exit /b 1 )
)

echo Writing icon + metadata ...
"%RCEDIT%" "%EXE%" --set-icon "%ICON_FULL%" --set-version-string "FileDescription" "%FileDescription%" --set-version-string "ProductName" "%ProductName%" --set-version-string "CompanyName" "%CompanyName%" --set-version-string "LegalCopyright" "%LegalCopyright%" --set-file-version "%FileVersion%" --set-product-version "%ProductVersion%"
if errorlevel 1 ( echo [ERROR] rcedit failed & exit /b 1 )

ie4uinit.exe -show >nul 2>&1

echo.
echo ============================================================
echo  Done.
echo  EXE             : %EXE%
echo  Icon            : %ICON_FULL%
echo  FileDescription : %FileDescription%
echo  ProductName     : %ProductName%
echo  CompanyName     : %CompanyName%
echo  LegalCopyright  : %LegalCopyright%
echo  FileVersion     : %FileVersion%
echo  ProductVersion  : %ProductVersion%
echo ============================================================
endlocal
echo.
pause