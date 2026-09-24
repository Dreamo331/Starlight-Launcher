@echo off
cd /d "%~dp0"
call mvn -q exec:java -o -Dexec.mainClass=com.example.starlight.LayoutProbe
