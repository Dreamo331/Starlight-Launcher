param([string]$workDir)
$env:JAVA_HOME='D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1'
$env:GRAALVM_HOME=$env:JAVA_HOME
$env:Path=$env:JAVA_HOME+'\bin;'+$env:Path

# MSVC + Windows SDK toolchain environment (equivalent to vcvars64.bat)
$msvc = 'E:\visual studio\VC\Tools\MSVC\14.51.36231'
$sdk  = 'C:\Program Files (x86)\Windows Kits\10'
$sdkv = '10.0.26100.0'
$env:INCLUDE = $msvc + '\include;' + $msvc + '\atlmfc\include;' + $sdk + '\Include\' + $sdkv + '\ucrt;' + $sdk + '\Include\' + $sdkv + '\um;' + $sdk + '\Include\' + $sdkv + '\shared'
$env:LIB     = $msvc + '\lib\x64;' + $msvc + '\atlmfc\lib\x64;' + $sdk + '\Lib\' + $sdkv + '\ucrt\x64;' + $sdk + '\Lib\' + $sdkv + '\um\x64'
$env:LIBPATH = $env:LIB
$env:Path = $msvc + '\bin\Hostx64\x64;' + $env:Path

Set-Location $workDir
& 'D:\apache-maven-3.8.8\apache-maven-3.8.8\bin\mvn.cmd' gluonfx:build *> "$workDir\build_native.log"
exit $LASTEXITCODE
