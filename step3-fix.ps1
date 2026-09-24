# ============================================================
# build-single-exe.bat step [3/6] 修复脚本
# 职责:
#   1. 重建 classes.jar (target/classes 打包进 gvm tmp/deps)
#   2. 重建 classpathJar.jar (Class-Path manifest, 72字符折行)
#   3. 修复 gvm 配置 JSON 中的非法转义 (gluonfx 把 <resourcesList>
#      的正则 .*\.png$ 原样写进 resourceconfig JSON, 单反斜杠 \.
#      在 JSON 里是非法转义 -> 统一修复为 \\.)
#   4. 追加缺失的反射条目 (FXML 控制器 / Gson 模型类 / JavaFXLauncher)
#   5. 清空 E:\ni-work (由 bat 随后 robocopy 重新复制)
# ============================================================
$ErrorActionPreference = 'Stop'
$root  = (Get-Location).Path
$gvm   = Join-Path $root 'target\gluonfx\x86_64-windows\gvm'
$deps  = Join-Path $gvm 'tmp\deps'
$tmp   = Join-Path $gvm 'tmp'
$graal = 'D:\graalvm-jdk-17\graalvm-community-openjdk-17.0.9+9.1'
$classes = Join-Path $root 'target\classes'
if (-not (Test-Path $classes)) { throw 'target/classes missing' }
if (-not (Test-Path $deps))    { throw 'gvm tmp/deps missing' }

# --- 1. 重建 classes.jar ---
$classesJar = Join-Path $deps 'classes.jar'
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
& "$graal\bin\jar.exe" cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar classes.jar failed' }

# --- 2. 重建 classpathJar.jar ---
$jars  = Get-ChildItem $deps -Filter *.jar | Sort-Object Name | Select-Object -ExpandProperty Name
$items = $jars | ForEach-Object { "deps/$_" }
$max = 72
$prefix = 'Class-Path: '
$lines = New-Object System.Collections.Generic.List[string]
$cur = $prefix
foreach ($it in $items) {
    $sep = if ($cur -eq $prefix) { '' } else { ' ' }
    $candidate = $cur + $sep + $it
    if ($candidate.Length -le $max) {
        $cur = $candidate
    } else {
        $lines.Add($cur + ' ')
        $cur = ' ' + $it
    }
}
$lines.Add($cur)
$mf = "Manifest-Version: 1.0`r`n" + ($lines -join "`r`n") + "`r`n`r`n"
$mfPath = Join-Path $tmp 'pathing.mf'
[System.IO.File]::WriteAllText($mfPath, $mf, [System.Text.Encoding]::ASCII)
$cpJar = Join-Path $tmp 'classpathJar.jar'
if (Test-Path $cpJar) { Remove-Item $cpJar -Force }
& "$graal\bin\jar.exe" cfm $cpJar $mfPath
if ($LASTEXITCODE -ne 0) { throw 'jar classpathJar.jar failed' }

# --- 3. 修复配置 JSON 中的非法转义 ---
# 通用算法: 在 JSON 字符串内部遇到 '\' 时, 若下一字符不属于 JSON 合法
# 转义字符集 (" \ / b f n r t u), 则补一个反斜杠变成合法的 \\.
# 对已合法的输入是恒等变换 (幂等), 不依赖具体 pattern 内容,
# 以后 pom 的 resourcesList 再怎么改都能自动修好.
function Repair-JsonEscapes {
    param([string]$Text)
    $valid = @('"','\','/','b','f','n','r','t','u')
    $sb = New-Object System.Text.StringBuilder
    $inStr = $false
    $len = $Text.Length
    for ($i = 0; $i -lt $len; $i++) {
        $ch = [string]$Text[$i]
        if ($ch -eq '"') { $inStr = -not $inStr; [void]$sb.Append($ch); continue }
        if ($inStr -and $ch -eq '\') {
            $next = if ($i + 1 -lt $len) { [string]$Text[$i + 1] } else { '' }
            if ($valid -contains $next) {
                # 合法转义对: 原样保留并跳过下一字符 (如 \\ \" \n \uXXXX)
                [void]$sb.Append($ch)
                [void]$sb.Append($next)
                $i++
            } else {
                # 非法转义 (如 \. \( ): 补成合法的 \\.
                [void]$sb.Append('\\')
            }
            continue
        }
        [void]$sb.Append($ch)
    }
    return $sb.ToString()
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
foreach ($name in @('resourceconfig-x86_64-windows.json','reflectionconfig-x86_64-windows.json','jniconfig-x86_64-windows.json')) {
    $p = Join-Path $gvm $name
    if (-not (Test-Path $p)) { continue }
    $c = Get-Content $p -Raw
    $c = $c.TrimStart([char]0xFEFF)
    $fixed = Repair-JsonEscapes $c
    # 自检: 修复后必须是合法 JSON, 否则让构建立即失败而不是带病前进
    $null = $fixed | ConvertFrom-Json
    [System.IO.File]::WriteAllText($p, $fixed, $utf8NoBom)
}

# --- 4. 追加缺失的反射条目 (FXML 控制器 + Gson 模型类等) ---
# pom <reflectionList> 的类由 gluonfx 写入, 这里按名单再兜底一次:
# 若 target/gvm 配置缺失某类 (如插件版本差异/重新生成遗漏), 补全量注册.
$requiredReflection = @(
    'com.example.starlight.launch.LaunchRecord',
    'com.example.starlight.community.CommunityApi$CommunityUser',
    'com.example.starlight.community.CommunityApi$CommunityPost',
    'com.example.starlight.community.CommunityApi$CommunityComment',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthProject',
    'com.example.starlight.ModsApi.ModrinthAPI$GameVersion',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthVersion',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthFile',
    'com.example.starlight.version.CacheManager$CacheEntry',
    'com.example.starlight.JavaFXLauncher',
    'com.example.starlight.newui.StarlightSplashController',
    # --- FXML 中实例化的 JavaFX 类: FXMLLoader 需要无参构造器反射元数据 ---
    'javafx.scene.canvas.Canvas',
    'javafx.scene.layout.Region',
    'javafx.scene.layout.StackPane',
    'javafx.scene.layout.VBox',
    'javafx.scene.layout.HBox',
    'javafx.scene.text.Text',
    'javafx.scene.shape.Rectangle',
    'javafx.geometry.Insets',
    'javafx.scene.layout.Pane',
    'javafx.scene.layout.AnchorPane',
    'javafx.scene.layout.BorderPane',
    'javafx.scene.layout.GridPane',
    'javafx.scene.control.Label',
    'javafx.scene.control.Button',
    'javafx.scene.image.ImageView',
    'javafx.scene.control.ProgressBar',
    'javafx.scene.control.TextField',
    'javafx.scene.control.PasswordField',
    'javafx.scene.control.ListView',
    'javafx.scene.control.ScrollPane',
    'javafx.scene.control.Separator',
    'javafx.scene.control.CheckBox',
    'javafx.scene.control.RadioButton',
    'javafx.scene.control.ComboBox',
    'javafx.scene.control.TextArea',
    'javafx.scene.control.Hyperlink',
    'javafx.scene.control.Tab',
    'javafx.scene.control.TabPane',
    'javafx.scene.control.ToggleGroup',
    'javafx.scene.layout.ColumnConstraints',
    'javafx.scene.layout.RowConstraints',
    'javafx.scene.Group',
    'javafx.scene.web.WebView',
    # --- Prism 特效原生 peer 类: Bloom/Blur/BlendMode 特效渲染时反射加载,
    #     缺注册报 "Could not create peer Brightpass" 且整帧画不出来 ---
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_ADDPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_BLUEPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_COLOR_BURNPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_COLOR_DODGEPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_DARKENPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_DIFFERENCEPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_EXCLUSIONPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_GREENPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_HARD_LIGHTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_LIGHTENPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_MULTIPLYPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_OVERLAYPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_REDPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SCREENPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SOFT_LIGHTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SRC_ATOPPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SRC_INPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SRC_OUTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBlend_SRC_OVERPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSBrightpassPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSColorAdjustPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSDisplacementMapPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSEffectPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSInvertMaskPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSLinearConvolvePeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSLinearConvolveShadowPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSOneSamplerPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSPerspectiveTransformPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSPhongLighting_DISTANTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSPhongLighting_POINTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSPhongLighting_SPOTPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSSepiaTonePeer',
    'com.sun.scenario.effect.impl.prism.ps.PPStoPSWDisplacementMapPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSTwoSamplerPeer',
    'com.sun.scenario.effect.impl.prism.ps.PPSZeroSamplerPeer',
    # --- Prism 主包 peers ---
    'com.sun.scenario.effect.impl.prism.PrCropPeer',
    'com.sun.scenario.effect.impl.prism.PrFloodPeer',
    'com.sun.scenario.effect.impl.prism.PrMergePeer',
    'com.sun.scenario.effect.impl.prism.PrReflectionPeer',
    # --- 软件渲染回退管线 (JSW=纯Java, SSE) ---
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_ADDPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_BLUEPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_COLOR_BURNPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_COLOR_DODGEPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_DARKENPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_DIFFERENCEPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_EXCLUSIONPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_GREENPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_HARD_LIGHTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_LIGHTENPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_MULTIPLYPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_OVERLAYPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_REDPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SCREENPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SOFT_LIGHTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SRC_ATOPPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SRC_INPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SRC_OUTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBlend_SRC_OVERPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBoxBlurPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBoxShadowPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWBrightpassPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWColorAdjustPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWDisplacementMapPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWEffectPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWInvertMaskPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWLinearConvolvePeer',
    'com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWPerspectiveTransformPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWPhongLighting_DISTANTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWPhongLighting_POINTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWPhongLighting_SPOTPeer',
    'com.sun.scenario.effect.impl.sw.java.JSWSepiaTonePeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_ADDPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_BLUEPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_COLOR_BURNPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_COLOR_DODGEPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_DARKENPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_DIFFERENCEPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_EXCLUSIONPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_GREENPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_HARD_LIGHTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_LIGHTENPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_MULTIPLYPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_OVERLAYPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_REDPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SCREENPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SOFT_LIGHTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_ATOPPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_INPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OUTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OVERPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBoxBlurPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBoxShadowPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEBrightpassPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEColorAdjustPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEDisplacementMapPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEEffectPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEInvertMaskPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSELinearConvolvePeer',
    'com.sun.scenario.effect.impl.sw.sse.SSELinearConvolveShadowPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEPerspectiveTransformPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEPhongLighting_DISTANTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEPhongLighting_POINTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSEPhongLighting_SPOTPeer',
    'com.sun.scenario.effect.impl.sw.sse.SSESepiaTonePeer'
)
$rf = Join-Path $gvm 'reflectionconfig-x86_64-windows.json'
$r = Get-Content $rf -Raw
$r = $r.TrimStart([char]0xFEFF)
$rObj = $r | ConvertFrom-Json
$have = @($rObj | ForEach-Object { $_.name })
$missing = @($requiredReflection | Where-Object { $have -notcontains $_ })
if ($missing.Count -gt 0) {
    Write-Host ("reflection missing " + $missing.Count + ": " + ($missing -join ', '))
    foreach ($m in $missing) {
        $entry = [ordered]@{
            name = $m
            allDeclaredConstructors = $true
            allPublicConstructors = $true
            allDeclaredFields = $true
            allPublicFields = $true
            allDeclaredMethods = $true
            allPublicMethods = $true
        }
        $entryJson = (ConvertTo-Json @($entry) -Depth 4)
        $entryJson = $entryJson.Trim()
        if ($entryJson.StartsWith('[')) { $entryJson = $entryJson.Substring(1) }
        if ($entryJson.EndsWith(']')) { $entryJson = $entryJson.Substring(0, $entryJson.Length - 1) }
        $entryJson = $entryJson.Trim()
        $r = $r.TrimEnd()
        if ($r.EndsWith(']')) { $r = $r.Substring(0, $r.Length - 1).TrimEnd() }
        $r = $r + ",`n" + $entryJson + "`n]"
        # 同步更新 have, 防重复
        $have += $m
    }
    # 自检: 追加后必须仍是合法 JSON
    $null = $r | ConvertFrom-Json
    [System.IO.File]::WriteAllText($rf, $r, $utf8NoBom)
}

# --- 5. 清空 E:\ni-work (bat 随后 robocopy 重新复制) ---
if (Test-Path 'E:\ni-work') { Remove-Item 'E:\ni-work' -Recurse -Force }
Write-Host 'PREPARE_OK'
