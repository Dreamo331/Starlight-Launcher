@echo off
call "E:\visual studio\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
set JAVA_HOME=D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1
set GRAALVM_HOME=%JAVA_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "D:\Starlight Launcher启动器工程-Java\UI\2026.6.21"
echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
where native-image 2>&1
call mvn gluonfx:build 2>&1
echo BUILD_EXIT_CODE=%ERRORLEVEL%
