<#
    Mod 中文搜索链路 —— 离线自检脚本（不联网）

    用法（在项目根目录或本目录下执行均可）：
        pwsh -File _dict_check\run-selftest.ps1              # 先编译主工程再自检
        pwsh -File _dict_check\run-selftest.ps1 -SkipBuild   # 跳过 mvn 编译，用现有 target\classes

    覆盖内容：
        字典加载与条目统计 → 中文 LCS 召回 → 英文检索词翻译 → 后端结果分区重排
        → slug/标题反查中文名 → 本地 jar 元数据解析 → 字典格式校验（防止错误页面写入缓存）
    另外会打印一批真实模组文件名的反查结果（Probe），便于快速判断匹配质量。
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$projectRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $PSScriptRoot 'classes'
$classpath = "$outDir;$projectRoot\target\classes;$projectRoot\src\main\resources"
# 让 JVM 以 UTF-8 输出中文，避免在非 936 代码页控制台下出现乱码
$javaEncArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        Write-Host '[1/3] 编译主工程（mvn -o -q compile）...' -ForegroundColor Cyan
        & mvn -o -q compile -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "mvn compile 失败（exit=$LASTEXITCODE）" }
    } else {
        Write-Host '[1/3] 跳过编译' -ForegroundColor DarkGray
    }

    Write-Host '[2/3] 编译自检程序...' -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $sources = @(
        (Join-Path $PSScriptRoot 'DictSelfTest.java'),
        (Join-Path $PSScriptRoot 'Probe.java')
    )
    & javac -nowarn -encoding UTF-8 -d $outDir -cp "$outDir;$projectRoot\target\classes" $sources
    if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit=$LASTEXITCODE）" }

    Write-Host '[3/3] 运行自检...' -ForegroundColor Cyan
    & java @javaEncArgs -cp $classpath DictSelfTest
    $selfTestExit = $LASTEXITCODE

    Write-Host ''
    Write-Host '---- 真实模组文件名反查（Probe）----' -ForegroundColor Cyan
    & java @javaEncArgs -cp $classpath Probe

    if ($selfTestExit -ne 0) {
        Write-Host ''
        Write-Host "自检存在失败项（exit=$selfTestExit）" -ForegroundColor Red
        exit $selfTestExit
    }
    Write-Host ''
    Write-Host '自检全部通过。' -ForegroundColor Green
} finally {
    Pop-Location
}
