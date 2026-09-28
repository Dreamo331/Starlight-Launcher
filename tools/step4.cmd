@echo off
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
  com.example.starlight.MainApp
exit /b %ERRORLEVEL%