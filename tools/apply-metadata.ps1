param()

$ErrorActionPreference = 'Stop'

# 取通配符匹配的最新版本工具路径（按目录名倒序，如 Windows Kits SDK 版本号 / MSVC 版本号）
function Get-LatestTool($pattern) {
    $hits = Get-Item -Path $pattern -ErrorAction SilentlyContinue |
            Sort-Object { $_.FullName } -Descending
    if (-not $hits) { Write-Error "tool not found: $pattern" }
    return $hits[0].FullName
}

$selfDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $selfDir
$metaPath = Join-Path $root 'src\windows\assets\metadata.txt'
$exePath  = Join-Path $root 'target\gluonfx\x86_64-windows\Starlight Launcher.exe'
$gvmRoot  = Join-Path $root 'target\gluonfx\x86_64-windows\gvm'
$workDir  = Join-Path $root 'temp\metadata'
$rcExe    = Get-LatestTool 'C:\Program Files (x86)\Windows Kits\10\bin\*\x64\rc.exe'
$msvcBin  = Split-Path -Parent (Get-LatestTool 'E:\visual studio\VC\Tools\MSVC\*\bin\Hostx64\x64\link.exe')
$linkExe  = Join-Path $msvcBin 'link.exe'
$cvtresExe = Join-Path $msvcBin 'cvtres.exe'

# 不再依赖 vcvars64.bat（它内部调 reg.exe，且依赖终端环境）。
# 直接显式指定 Windows SDK / MSVC 的 include 与 lib 路径，脚本自成一体。
$sdkRoot  = 'C:\Program Files (x86)\Windows Kits\10'
$sdkVer   = (Get-ChildItem (Join-Path $sdkRoot 'Include') -Directory |
             Sort-Object Name -Descending | Select-Object -First 1).Name
if (-not $sdkVer) { Write-Error "Windows SDK not found under $sdkRoot\Include" }
$sdkInc   = Join-Path $sdkRoot "Include\$sdkVer"
$sdkLib   = Join-Path $sdkRoot "Lib\$sdkVer"
# $msvcBin = ...\MSVC\<ver>\bin\Hostx64\x64 -> 上溯 3 层到 ...\MSVC\<ver>
$msvcVerDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $msvcBin))
$msvcLib  = Join-Path $msvcVerDir 'lib\x64'
Write-Output "[metadata] SDK $sdkVer / MSVC $(Split-Path -Leaf $msvcVerDir)"

if (-not (Test-Path $metaPath)) { Write-Error "metadata.txt not found: $metaPath" }
if (-not (Test-Path $exePath))  { Write-Error "exe not found: $exePath (run gluonfx:link first)" }
if (-not (Test-Path $rcExe))    { Write-Error "rc.exe not found: $rcExe" }
if (-not (Test-Path $linkExe))  { Write-Error "link.exe not found: $linkExe" }

# ---- 1. parse metadata.txt (Key=Value; ignore blank/#/;) ----
$meta = @{}
foreach ($l in (Get-Content -LiteralPath $metaPath -Encoding UTF8)) {
    $t = $l.TrimStart()
    if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith(';')) { continue }
    $i = $l.IndexOf('=')
    if ($i -lt 1) { continue }
    $meta[$l.Substring(0, $i).Trim()] = $l.Substring($i + 1).Trim()
}
function Get-Meta($key, $def) { if ($meta.ContainsKey($key)) { $meta[$key] } else { $def } }

$fileDesc = Get-Meta 'FileDescription' 'Starlight Launcher'
$prodName = Get-Meta 'ProductName' 'Starlight Launcher'
$company  = Get-Meta 'CompanyName' ''
$copy     = Get-Meta 'LegalCopyright' ''
$fileVer  = Get-Meta 'FileVersion' '1.0.0.0'
$prodVer  = Get-Meta 'ProductVersion' '1.0.0.0'
$fileVerCsv = $fileVer -replace '\.', ','
$prodVerCsv = $prodVer -replace '\.', ','
$internalName = [System.IO.Path]::GetFileNameWithoutExtension($exePath)
$origName = [System.IO.Path]::GetFileName($exePath)

function RcStr($s) { return $s -replace '"', '' }

# ---- 2. generate version.rc (含图标 + 版本信息, 合并为一个资源对象,
#      链接器只接受一个含资源的输入: 用它替换 gluonfx 的 IconGroup.obj) ----
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$iconPath = Get-Meta 'Icon' 'src\windows\assets\icon.ico'
if (-not [System.IO.Path]::IsPathRooted($iconPath)) {
    $iconPath = Join-Path $root $iconPath
}
# 图标缺失只降级警告（EXE 保留 gluonfx 嵌的图标），版本信息照常写入；
# 之前的实现在这里直接中止，导致公司/版权/版本号全部丢失。
$hasIcon = Test-Path $iconPath
if (-not $hasIcon) {
    Write-Warning "icon not found: $iconPath -- version info will be written WITHOUT icon"
}
$iconPathRc = ($iconPath -replace '\\', '/')
$rcPath = Join-Path $workDir 'version.rc'
$resPath = Join-Path $workDir 'version.res'
$iconLine = ''
if ($hasIcon) { $iconLine = "IDI_ICON1 ICON `"$iconPathRc`"`n" }
$rcText = @"
#include <winver.h>

$iconLine
VS_VERSION_INFO VERSIONINFO
 FILEVERSION     $fileVerCsv
 PRODUCTVERSION  $prodVerCsv
 FILEFLAGSMASK 0x3fL
 FILEFLAGS 0x0L
 FILEOS VOS_NT_WINDOWS32
 FILETYPE VFT_APP
 FILESUBTYPE VFT2_UNKNOWN
BEGIN
    BLOCK "StringFileInfo"
    BEGIN
        BLOCK "040904b0"
        BEGIN
            VALUE "CompanyName",      "$(RcStr $company)"
            VALUE "FileDescription",  "$(RcStr $fileDesc)"
            VALUE "FileVersion",      "$(RcStr $fileVer)"
            VALUE "InternalName",     "$(RcStr $internalName)"
            VALUE "LegalCopyright",   "$(RcStr $copy)"
            VALUE "OriginalFilename", "$(RcStr $origName)"
            VALUE "ProductName",      "$(RcStr $prodName)"
            VALUE "ProductVersion",   "$(RcStr $prodVer)"
        END
    END
    BLOCK "VarFileInfo"
    BEGIN
        VALUE "Translation", 0x0409, 1200
    END
END
"@
[System.IO.File]::WriteAllText($rcPath, $rcText, [System.Text.Encoding]::UTF8)
Write-Output "[metadata] rc written: $rcPath"

# ---- 3. compile .res with rc.exe (直接调用, 显式传 SDK include 路径) ----
& $rcExe /nologo /fo $resPath /i "$sdkInc\shared" /i "$sdkInc\um" /i "$sdkInc\ucrt" $rcPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $resPath)) {
    Write-Error "rc.exe failed (exit=$LASTEXITCODE)"
}
Write-Output "[metadata] res compiled: $resPath"

# ---- 3b. cvtres: .res -> .obj (MSVC 链接器只接受一个含资源的输入,
#      glass.lib 的 GlassResources.res / IconGroup.obj 已占用该名额;
#      所以用"图标+版本"合并后的 .obj 在链接时替换 IconGroup.obj) ----
$resObjPath = Join-Path $workDir 'version.obj'
& $cvtresExe /nologo /MACHINE:X64 /OUT:$resObjPath $resPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $resObjPath)) {
    Write-Error "cvtres.exe failed (exit=$LASTEXITCODE)"
}
Write-Output "[metadata] res->obj: $resObjPath"

# ---- 4. locate SVM obj dir (timestamped per build) ----
$svm = Get-ChildItem -Path (Join-Path $gvmRoot 'tmp') -Directory -Filter 'SVM-*' |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $svm) { Write-Error "SVM obj dir not found under $gvmRoot\tmp" }
$svmObj = Join-Path $svm.FullName 'com.example.starlight.mainapp.obj'
if (-not (Test-Path $svmObj)) { Write-Error "SVM mainapp obj not found: $svmObj" }
Write-Output "[metadata] svm obj: $svmObj"

# ---- 5. relink (same inputs as gluonfx + version.obj).
#      先链接到暂存路径, 成功后才覆盖正式 exe (link 失败会删除输出文件,
#      直接 /OUT 到正式路径会把还能用的 exe 弄丢) ----
$launcherObj = Join-Path $gvmRoot 'Starlight Launcher\launcher.obj'
$iconObj     = Join-Path $gvmRoot 'Starlight Launcher\IconGroup.obj'
$mgmtObj     = Join-Path $gvmRoot 'Starlight Launcher\management_stub.obj'
$javafxLib   = 'C:\Users\Administrator\.gluon\substrate\javafxStaticSdk\17.0.8\windows-x86_64\sdk\lib'
$graalSvmLib = 'D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1\lib\svm\clibraries\windows-amd64'
$graalStLib  = 'D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1\lib\static\windows-amd64'
$stagingExe  = Join-Path $workDir 'Starlight Launcher.exe'

$linkArgs = @(
    $launcherObj, $svmObj, $mgmtObj,
    'j2pkcs11.lib','java.lib','net.lib','nio.lib','prefs.lib','fdlibm.lib','zip.lib','sunmscapi.lib','jvm.lib','libchelper.lib',
    'advapi32.lib','iphlpapi.lib','secur32.lib','userenv.lib','version.lib','ws2_32.lib','winhttp.lib','ncrypt.lib','crypt32.lib',
    '/NODEFAULTLIB:libcmt.lib','/SUBSYSTEM:WINDOWS','/ENTRY:mainCRTStartup',
    'comdlg32.lib','dwmapi.lib','gdi32.lib','imm32.lib','shell32.lib','uiautomationcore.lib','urlmon.lib','winmm.lib',
    'glass.lib','javafx_font.lib','javafx_iio.lib','prism_common.lib','prism_d3d.lib',
    '/WHOLEARCHIVE:glass.lib','/WHOLEARCHIVE:javafx_font.lib','/WHOLEARCHIVE:javafx_iio.lib',
    '/WHOLEARCHIVE:prism_common.lib','/WHOLEARCHIVE:prism_d3d.lib',
    $resObjPath,
    "/OUT:$stagingExe",
    "/LIBPATH:$javafxLib", "/LIBPATH:$graalSvmLib", "/LIBPATH:$graalStLib",
    "/LIBPATH:$sdkLib\um\x64", "/LIBPATH:$sdkLib\ucrt\x64", "/LIBPATH:$msvcLib"
) + @('/INCREMENTAL:NO','/DEBUG:NONE')

$linkLog = Join-Path $workDir 'relink.log'
$oldPath = $env:PATH
$env:PATH = "$msvcBin;$oldPath"   # link.exe 需要 mspdbcore.dll 等同目录 DLL
try {
    & $linkExe @linkArgs *> $linkLog
} finally {
    $env:PATH = $oldPath
}
$linkExit = $LASTEXITCODE
if ($linkExit -ne 0 -or -not (Test-Path $stagingExe)) {
    Write-Error "relink failed (exit=$linkExit). see $linkLog (正式 exe 未被改动)"
}
Copy-Item -LiteralPath $stagingExe -Destination $exePath -Force
$size = (Get-Item $exePath).Length
Write-Output "[metadata] relink OK: $exePath ($size bytes)"
Write-Output "METADATA_OK"
