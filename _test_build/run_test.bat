@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
set /p CP=<target\cp.txt
javac -encoding UTF-8 -cp "target\classes;%CP%" -d _test_build _test_build\ProgressBarLayoutTest.java
echo COMPILE_EXIT=%ERRORLEVEL%
java -cp "_test_build;target\classes;%CP%" ProgressBarLayoutTest
echo RUN_EXIT=%ERRORLEVEL%
