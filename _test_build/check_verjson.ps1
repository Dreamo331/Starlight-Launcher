param([string]$Base)
$v = Join-Path $Base 'versions'
$files = @(
    @{ Name = '1.0.0-rc2-2'; Path = Join-Path $v '1.0.0-rc2-2\1.0.0-rc2-2.json' },
    @{ Name = '26.2-Fabric 0.19.3'; Path = Join-Path $v '26.2-Fabric 0.19.3\26.2-Fabric 0.19.3.json' }
)
foreach ($f in $files) {
    Write-Output ('===== ' + $f.Name + ' =====')
    if (-not (Test-Path -LiteralPath $f.Path)) { Write-Output 'JSON NOT FOUND'; continue }
    $c = [System.IO.File]::ReadAllText($f.Path)
    $json = $c | ConvertFrom-Json
    Write-Output ('id: ' + $json.id)
    Write-Output ('mainClass: ' + $json.mainClass)
    Write-Output ('inheritsFrom: ' + $json.inheritsFrom)
    Write-Output ('type: ' + $json.type)
    if ($json.libraries) { Write-Output ('libraries count: ' + $json.libraries.Count) } else { Write-Output 'libraries: NONE' }
    if ($json.fabricLoader) {
        Write-Output ('fabricLoader present: true')
        Write-Output ('  fabricLoader.version: ' + $json.fabricLoader.version)
        Write-Output ('  fabricLoader.minecraftVersion: ' + $json.fabricLoader.minecraftVersion)
    } else { Write-Output 'fabricLoader: NONE' }
    Write-Output ('file size: ' + $c.Length + ' chars')
    Write-Output ''
}
