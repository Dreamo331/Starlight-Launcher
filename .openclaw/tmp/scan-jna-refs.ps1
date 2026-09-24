# 全量扫描 src 下所有 JNA/OSHI 相关引用（防止遗漏 native 下会炸的使用点）
$root = 'D:\Starlight Launcher启动器工程-Java\UI\2026.6.21\src'
$pattern = 'com\.sun\.jna|oshi\.|net\.java\.dev\.jna|Kernel32|WinNT|GetProcessMemoryInfo|EmptyWorkingSet|jnidispatch|PSAPI|psapi'
Get-ChildItem -Path $root -Recurse -File -Include *.java,*.kt | ForEach-Object {
    $file = $_.FullName
    $lineNo = 0
    foreach ($line in [System.IO.File]::ReadAllLines($file, [System.Text.Encoding]::UTF8)) {
        $lineNo++
        if ($line -match $pattern) {
            $rel = $file.Substring($root.Length + 1)
            Write-Output ("{0}:{1}: {2}" -f $rel, $lineNo, $line.Trim())
        }
    }
}
Write-Output "SCAN_DONE"
