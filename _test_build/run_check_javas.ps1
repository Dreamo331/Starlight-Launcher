$paths = @(
    'D:\java_8_or_17+\java8\bin\java.exe',
    'D:\.jdks\ms-17.0.19\bin\java.exe',
    'D:\java_8_or_17+\java25\OpenJDK25U-jdk_x64_windows_hotspot_25.0.3_9\jdk-25.0.3+9\bin\java.exe'
)
foreach ($p in $paths) {
    if (Test-Path $p) {
        Write-Output ('OK: ' + $p)
        & $p -version 2>&1 | Select-Object -First 1 | ForEach-Object { Write-Output ('   ' + $_) }
    } else {
        Write-Output ('MISSING: ' + $p)
    }
}
Write-Output '===== D:\java_8_or_17+ 内容 ====='
if (Test-Path 'D:\java_8_or_17+') { Get-ChildItem 'D:\java_8_or_17+' -Directory | ForEach-Object { Write-Output $_.Name } }
