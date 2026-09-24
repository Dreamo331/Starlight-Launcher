@echo off
cd /d "%~dp0"
setlocal

set "MSG=%~1"
if "%MSG%"=="" set "MSG=update %date% %time%"
set "RC=0"

git add -u
git add src pom.xml lib README.md LICENSE *.bat *.ps1 1>nul 2>nul

git diff --cached --quiet
if not errorlevel 1 goto push

git commit -q -m "%MSG%"
if errorlevel 1 goto fail
echo [OK] committed: %MSG%

:push
git push github HEAD:main
if errorlevel 1 (echo [X] GitHub FAILED & set "RC=1") else (echo [OK] GitHub)
git push gitee HEAD:main
if errorlevel 1 (echo [X] Gitee FAILED & set "RC=1") else (echo [OK] Gitee)
if "%RC%"=="1" goto fail
echo [OK] all done
exit /b 0

:fail
echo [X] some pushes failed - just re-run; if rejected run: git pull --rebase
exit /b 1
