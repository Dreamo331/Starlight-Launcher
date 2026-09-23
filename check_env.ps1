$assets = curl.exe -s -m 30 "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases/tags/jdk-17.0.9" | ConvertFrom-Json | Select-Object -ExpandProperty assets | Select-Object -ExpandProperty name
Write-Output "=== jdk-17.0.9 assets ==="
$assets | ForEach-Object { Write-Output $_ }
$ext = Get-ChildItem 'src\main\resources' -Recurse -File | Group-Object Extension
Write-Output "=== resources extensions ==="
$ext | ForEach-Object { Write-Output ("{0} : {1}" -f $_.Name, $_.Count) }
