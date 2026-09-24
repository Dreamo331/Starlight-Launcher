$p1 = 'D:\java_8_or_17+\java8'
$p2 = 'D:\java_8_or_17+'
Write-Output ('=== Test ' + $p1 + ' ===')
if (Test-Path $p1) { Get-ChildItem $p1 | Select-Object -First 12 | ForEach-Object { Write-Output $_.Name } } else { Write-Output 'NOT EXISTS' }
Write-Output ('=== Test ' + $p2 + ' ===')
if (Test-Path $p2) { Get-ChildItem $p2 | ForEach-Object { Write-Output $_.Name } } else { Write-Output 'NOT EXISTS' }
Write-Output ('=== java8.exe check ===')
$exe = Join-Path $p1 'bin\java.exe'
if (Test-Path $exe) { & $exe -version 2>&1 | ForEach-Object { Write-Output $_ } } else { Write-Output 'NO java.exe' }
Write-Output ('=== other java dirs ===')
Get-ChildItem 'D:\' -Directory | Where-Object { $_.Name -match 'java|jdk|jre' } | ForEach-Object { Write-Output $_.Name }
Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ('PF-Java: ' + $_.Name) }
Get-ChildItem 'C:\Program Files\Microsoft' -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk' } | ForEach-Object { Write-Output ('PF-MS: ' + $_.Name) }
Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ('PF-Adoptium: ' + $_.Name) }
