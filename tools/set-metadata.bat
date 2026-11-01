@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0.."

rem  set-metadata.bat - safely write EXE metadata (icon + version)
rem
rem  OLD implementation used rcedit which corrupted the PE resource
rem  sections of GraalVM native-image EXEs, making Windows refuse
rem  to load them ("This app can't run on your PC").
rem  NEW implementation: metadata.txt -> version.rc -> rc.exe -> .res
rem  -> cvtres -> .obj -> relink (icon + version merged by the linker
rem  in a single resource object, replacing gluonfx IconGroup.obj).
rem
rem  NOTE: keep this file pure ASCII + CRLF. It runs under "chcp 65001"
rem  (set by build-single-exe.bat); non-ASCII bytes or LF-only endings
rem  make the cmd parser mis-split lines into garbage commands.

set "EXE=target\gluonfx\x86_64-windows\Starlight Launcher.exe"
if not exist "%EXE%" (
    echo [ERROR] EXE not found: %EXE%
    exit /b 1
)

echo Writing icon + version metadata (safe linker merge) ...
rem apply-metadata.ps1 invokes rc.exe/cvtres.exe/link.exe by full path
rem with explicit SDK/MSVC include+lib dirs, so vcvars64.bat is NOT
rem needed (it shells out to reg.exe and depends on terminal env).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0apply-metadata.ps1"
if errorlevel 1 (
    echo [ERROR] metadata apply failed. EXE remains usable without version info.
    exit /b 1
)

echo.
echo ============================================================
echo  Done.
echo  EXE  : %EXE%
echo  Icon : src\windows\assets\icon.ico (embedded by linker)
echo  Ver  : src\windows\assets\metadata.txt (merged by linker)
echo ============================================================
endlocal
