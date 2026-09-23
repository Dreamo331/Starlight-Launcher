@echo off
rem ================================================================
rem  Starlight Launcher - single-file native EXE via GraalVM + GluonFX
rem  Output: target\gluonfx\x86_64-windows\Starlight Launcher.exe
rem  (single native exe, no JRE needed, cannot be extracted by 7-Zip)
rem ================================================================
setlocal

rem --- work dir = script dir (avoids non-ASCII path issues in cmd) ---
cd /d "%~dp0"

rem --- MSVC + Windows SDK toolchain ---
call "E:\visual studio\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)

rem --- GraalVM ---
set "JAVA_HOME=D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1"
set "GRAALVM_HOME=%JAVA_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem --- Redirect all temp to E: (C: drive has only ~2GB free) ---
if not exist "E:\build-tmp" mkdir "E:\build-tmp"
set "TMP=E:\build-tmp"
set "TEMP=E:\build-tmp"
set "MAVEN_OPTS=-Djava.io.tmpdir=E:\build-tmp"

rem --- Clean stale gluonfx output from previous failed attempt ---
if exist "target\gluonfx" rmdir /s /q "target\gluonfx"

echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
where native-image 2>&1
echo ================================================================
echo Starting gluonfx:build ... (may take 20-40 minutes)
echo ================================================================

call "D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd" gluonfx:build 2>&1
set "BUILD_EXIT=%ERRORLEVEL%"

echo ================================================================
echo BUILD_EXIT_CODE=%BUILD_EXIT%
if exist "target\gluonfx\x86_64-windows\Starlight Launcher.exe" (
    echo SUCCESS: target\gluonfx\x86_64-windows\Starlight Launcher.exe
) else (
    echo NO EXE FOUND - build failed
)
echo ================================================================
exit /b %BUILD_EXIT%
