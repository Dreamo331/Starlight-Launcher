# ============================================================
# build-single-exe.bat step [5/6] 回拷脚本
# 职责: native-image 在 E:\ni-work 产生 SVM-* 目录后,
#       把含 com.example.starlight.mainapp.obj 的最新目录
#       回拷到项目 gvm\tmp, 供 gluonfx:link 使用.
# ============================================================
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$gvm = Join-Path $root 'target\gluonfx\x86_64-windows\gvm'
$svmSrc = Get-ChildItem 'E:\ni-work\tmp' -Directory -Filter 'SVM-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $svmSrc) { throw 'no SVM dir produced by native-image' }
$obj = Join-Path $svmSrc.FullName 'com.example.starlight.mainapp.obj'
if (-not (Test-Path $obj)) { throw 'com.example.starlight.mainapp.obj missing' }
Get-ChildItem (Join-Path $gvm 'tmp') -Directory -Filter 'SVM-*' -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force
Copy-Item $svmSrc.FullName (Join-Path $gvm "tmp\$($svmSrc.Name)") -Recurse -Force
Write-Host 'COPYBACK_OK'
