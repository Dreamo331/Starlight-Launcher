$jars = Get-ChildItem (Get-Location) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch 'node_modules|\.m2' }
foreach ($j in $jars) {
    $bytes = [System.IO.File]::ReadAllBytes($j.FullName)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    $has = $text.Contains('NonNMethodCodeHeapSize')
    Write-Output ($j.FullName.Substring((Get-Location).Path.Length + 1) + ' | ' + $j.Length + 'B | ' + $j.LastWriteTime.ToString('MM-dd HH:mm:ss') + ' | NonNMethod=' + $has)
}
Write-Output '===== .m2 相关 ====='
$m2 = Get-ChildItem (Join-Path $env:USERPROFILE '.m2') -Recurse -Filter 'starlight-launcher-core-2.0.0.jar' -ErrorAction SilentlyContinue
foreach ($j in $m2) {
    $bytes = [System.IO.File]::ReadAllBytes($j.FullName)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    Write-Output ($j.FullName + ' | ' + $j.Length + 'B | ' + $j.LastWriteTime.ToString('MM-dd HH:mm:ss') + ' | NonNMethod=' + $text.Contains('NonNMethodCodeHeapSize'))
}
