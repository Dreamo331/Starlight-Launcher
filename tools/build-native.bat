@echo off
rem ================================================================
rem  Starlight Launcher - native EXE packaging (jpackage)
rem
rem  Outputs:
rem    build\StarlightLauncher\StarlightLauncher-2.0.0.exe   (installer, single file)
rem    build\StarlightLauncherApp\StarlightLauncher\         (portable dir, no install)
rem  Require: JDK 17 (with jlink/jpackage), Maven 3.6+, WiX Toolset 3.x
rem           (candle.exe must be on PATH for --type exe)
rem ================================================================
setlocal

rem --- Redirect temp to E: (C: drive is full on this machine) ---
if not exist "E:\build-tmp" mkdir "E:\build-tmp"
set "TMP=E:\build-tmp"
set "TEMP=E:\build-tmp"
set "MAVEN_OPTS=-Djava.io.tmpdir=E:\build-tmp"

rem --- Clean stale javafx module jars so they are re-copied ---
if exist "target\modules\*.jar" del /q "target\modules\*.jar"

rem --- Clean previous build outputs (jlink/jpackage refuse to overwrite) ---
if exist "target\runtime" rmdir /s /q "target\runtime"
if exist "build\StarlightLauncher" rmdir /s /q "build\StarlightLauncher"
if exist "build\StarlightLauncherApp" rmdir /s /q "build\StarlightLauncherApp"

echo [1/1] mvn install -P native-package (skip tests, skip local-repo install) ...
call mvn install -P native-package -DskipTests -Dmaven.test.skip=true -Dmaven.install.skip=true %*
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

echo.
echo ================================================================
echo  Done.
echo    Installer (single file): build\StarlightLauncher\StarlightLauncher-2.0.0.exe
echo      NOTE: installer extracts payload to C:\Users\...\AppData\Local
echo      (WiX Burn cache) - C: drive must have free space, otherwise
echo      you get "No space left on device" when running it.
echo    Portable  : build\StarlightLauncherApp\StarlightLauncher\StarlightLauncher.exe
echo      Runs directly, no installation, no C: drive usage.
echo ================================================================
endlocal
