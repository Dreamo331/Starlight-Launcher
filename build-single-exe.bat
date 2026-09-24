chcp 65001>nul
@echo off
rem ============================================================
rem  Starlight Launcher 涓€閿墦鍖?鍗旹XE (GraalVM Native Image)
rem  浜х墿: target\gluonfx\x86_64-windows\Starlight Launcher.exe
rem  鐗规€? 鍘熺敓鏈哄櫒鐮? 鍗曟枃浠? 涓嶈兘琚В鍘嬪伐鍏疯В寮€, 鏃犻渶瀹夎
rem  渚濊禆: MSVC(vcvars64) + GraalVM 17 + Maven + E鐩樼┖闂?
rem ============================================================
setlocal
cd /d "%~dp0"

echo [1/6] 鍒濆鍖栫幆澧?(MSVC + GraalVM + TEMP=E:\build-tmp)...
call "E:\visual studio\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
set "JAVA_HOME=D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1"
set "GRAALVM_HOME=%JAVA_HOME%"
set "TMP=E:\build-tmp"
set "TEMP=E:\build-tmp"
set "MAVEN_OPTS=-Djava.io.tmpdir=E:\build-tmp"

echo [2/6] gluonfx:compile 鐢熸垚 gvm 缁撴瀯 (classpath 鎶ラ敊灞為鏈?...
call "D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd" gluonfx:compile > "_build_step2.log" 2>&1

echo [3/6] 淇 classpathJar/璧勬簮閰嶇疆/鍙嶅皠閰嶇疆 骞跺鍒跺埌 E:\ni-work...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0step3-fix.ps1"
if errorlevel 1 ( echo [3/6] FIX FAILED & exit /b 1 )
robocopy "%~dp0target\gluonfx\x86_64-windows\gvm" "E:\ni-work" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 ( echo [3/6] COPY FAILED & exit /b 1 )

echo [4/6] native-image 缂栬瘧 (绾?-5鍒嗛挓, 璇峰嬁鍏抽棴绐楀彛)...
cd /d "E:\ni-work\Starlight Launcher"
"D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1\lib\svm\bin\native-image.exe" ^
  "-Djdk.internal.lambda.eagerlyInitialize=false" ^
  --no-server ^
  -H:+SharedLibrary ^
  -H:+AddAllCharsets ^
  -H:+ReportExceptionStackTraces ^
  -H:-DeadlockWatchdogExitOnTimeout ^
  "-H:DeadlockWatchdogInterval=0" ^
  -H:+RemoveSaturatedTypeFlows ^
  -H:+ExitAfterRelocatableImageWrite ^
  "--features=org.graalvm.home.HomeFinderFeature" ^
  "-H:TempDirectory=E:\ni-work\tmp" ^
  "-H:EnableURLProtocols=http,https" ^
  -H:+PrintAnalysisCallTree ^
  "-H:Log=registerResource:" ^
  "-H:ReflectionConfigurationFiles=E:\ni-work\reflectionconfig-x86_64-windows.json" ^
  -H:+JNI ^
  "-H:JNIConfigurationFiles=E:\ni-work\jniconfig-x86_64-windows.json" ^
  "-H:ResourceConfigurationFiles=E:\ni-work\resourceconfig-x86_64-windows.json" ^
  "-H:IncludeResourceBundles=com/sun/javafx/scene/control/skin/resources/controls,com/sun/javafx/scene/control/skin/resources/controls-nt,com.sun.javafx.tk.quantum.QuantumMessagesBundle,com/sun/glass/ui/win/themes,com.sun.media.jfxmedia.MediaErrors,com.sun.webkit.graphics.Images,com.sun.webkit.LocalizedStrings,javafx.scene.web.HTMLEditorSkin,com.sun.org.apache.xerces.internal.impl.msg.XMLMessages" ^
  "-Dsvm.platform=org.graalvm.nativeimage.Platform$WINDOWS_AMD64" ^
  -cp "C:\Users\Administrator\.m2\repository\com\gluonhq\substrate\0.0.69\substrate-0.0.69.jar;E:\ni-work\tmp\classpathJar.jar" ^
  com.example.starlight.MainApp > "E:\ni-work\_ni.log" 2>&1
if errorlevel 1 ( echo [4/6] NATIVE-IMAGE FAILED & echo --- _ni.log tail --- & type "E:\ni-work\_ni.log" & exit /b 1 )
cd /d "%~dp0"

echo [5/6] 澶嶅埗缂栬瘧浜х墿鍥為」鐩?gvm...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0step5-copyback.ps1"
if errorlevel 1 ( echo [5/6] COPYBACK FAILED & exit /b 1 )

echo [6/6] gluonfx:link 閾炬帴鐢熸垚 exe...
call "D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd" gluonfx:link > "_build_step6.log" 2>&1
if errorlevel 1 ( echo [6/6] LINK FAILED & exit /b 1 )

if exist "target\gluonfx\x86_64-windows\Starlight Launcher.exe" (
    echo.
    echo ============================================================
    echo  鎵撳寘鎴愬姛!
    echo  浜х墿: %~dp0target\gluonfx\x86_64-windows\Starlight Launcher.exe
    echo  鐗规€? 鍗曟枃浠跺師鐢烢XE, 鏃燡RE渚濊禆, 鏃犳硶琚В鍘嬪伐鍏疯В寮€
    echo ============================================================
) else (
    echo 鎵撳寘澶辫触: exe 鏈敓鎴? 璇锋鏌ヤ笂鏂规棩蹇?
    exit /b 1
)
