Write-Output '=== java dirs ==='
$dirs = [System.IO.Directory]::GetDirectories('D:\java_8_or_17+')
$dirs | ForEach-Object { Write-Output $_ }
Write-Output '=== JAVA_HOME ==='
Write-Output $env:JAVA_HOME
Write-Output '=== PATH java ==='
$cmd = Get-Command java -ErrorAction SilentlyContinue
if ($cmd) { Write-Output $cmd.Source } else { Write-Output 'java not on PATH' }
Write-Output '=== registry HKLM JavaSoft ==='
$keys = Get-ChildItem 'HKLM:\SOFTWARE\JavaSoft' -ErrorAction SilentlyContinue
if ($keys) { $keys | ForEach-Object { Write-Output $_.Name } } else { Write-Output 'no JavaSoft key' }
$keys64 = Get-ChildItem 'HKLM:\SOFTWARE\JavaSoft' -ErrorAction SilentlyContinue
$jdkKeys = Get-ChildItem 'HKLM:\SOFTWARE\JavaSoft\JDK' -ErrorAction SilentlyContinue
if ($jdkKeys) { $jdkKeys | ForEach-Object { Write-Output $_.Name } }
$jreKeys = Get-ChildItem 'HKLM:\SOFTWARE\JavaSoft\Java Runtime Environment' -ErrorAction SilentlyContinue
if ($jreKeys) { $jreKeys | ForEach-Object { Write-Output $_.Name } }
