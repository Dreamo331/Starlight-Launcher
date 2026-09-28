chcp 65001>nul
@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem ============================================================
rem  Starlight Launcher 一键打包 单EXE (GraalVM Native Image)
rem  日志: temp\log\<时间戳>\stepN-*.log, 实时显示 + 保存
rem ============================================================

set "LOGDIR=%~dp0temp\log"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%a"
set "RUNLOG=%LOGDIR%\build-%TS%"
mkdir "%RUNLOG%" 2>nul
echo Log directory: %RUNLOG%
echo.

rem ---- 日志包含检查: call :logHas "file" "pattern" ----
goto :main

:logHas
powershell -NoProfile -Command "if (Select-String -LiteralPath '%~1' -Pattern '%~2' -SimpleMatch -Quiet) { exit 0 } else { exit 1 }"
exit /b %ERRORLEVEL%

:main
echo [1/7] 初始化环境 (MSVC + GraalVM + TEMP=E:\build-tmp)...
call "E:\visual studio\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
set "JAVA_HOME=D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1"
set "GRAALVM_HOME=%JAVA_HOME%"
set "TMP=E:\build-tmp"
set "TEMP=E:\build-tmp"
set "MAVEN_OPTS=-Djava.io.tmpdir=E:\build-tmp"

echo.
echo [2/7] gluonfx:compile ...
call "D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd" gluonfx:compile 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step2-compile.log'"
call :logHas "%RUNLOG%\step2-compile.log" "BUILD SUCCESS"
if errorlevel 1 ( echo [2/7] COMPILE FAILED & pause & exit /b 1 )

echo.
echo [3/7] 修复 classpath / 资源配置 / 反射配置 ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\step3-fix.ps1" 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step3-fix.log'"
call :logHas "%RUNLOG%\step3-fix.log" "PREPARE_OK"
if errorlevel 1 ( echo [3/7] FIX FAILED & pause & exit /b 1 )

echo.
echo [3/7] 复制 gvm 到 E:\ni-work ...
robocopy "%~dp0target\gluonfx\x86_64-windows\gvm" "E:\ni-work" /E /NFL /NDL /NJH /NJS /NP 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step3-robocopy.log'"
if errorlevel 8 ( echo [3/7] COPY FAILED & pause & exit /b 1 )

echo.
echo [4/7] native-image 编译 (约3-5分钟, 请勿关闭窗口)...
call "%~dp0tools\step4.cmd" 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step4-native-image.log'"
if errorlevel 1 ( echo [4/7] NATIVE-IMAGE FAILED & pause & exit /b 1 )
cd /d "%~dp0"

echo.
echo [5/7] 复制编译产物回项目 gvm ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\step5-copyback.ps1" 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step5-copyback.log'"
call :logHas "%RUNLOG%\step5-copyback.log" "COPYBACK_OK"
if errorlevel 1 ( echo [5/7] COPYBACK FAILED & pause & exit /b 1 )

echo.
echo [6/7] gluonfx:link 链接生成 exe ...
call "D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd" gluonfx:link 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step6-link.log'"
call :logHas "%RUNLOG%\step6-link.log" "BUILD SUCCESS"
if errorlevel 1 ( echo [6/7] LINK FAILED & pause & exit /b 1 )

echo.
echo [7/7] 写入图标与版本信息 ...
call "%~dp0tools\set-metadata.bat" < nul 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%RUNLOG%\step7-metadata.log'"

if exist "target\gluonfx\x86_64-windows\Starlight Launcher.exe" (
    echo.
    echo ============================================================
    echo  打包成功!
    echo  产物: %~dp0target\gluonfx\x86_64-windows\Starlight Launcher.exe
    echo  日志: %RUNLOG%
    echo ============================================================
) else (
    echo 打包失败: exe 未生成
    pause
    exit /b 1
)
echo.
pause