Add-Type -AssemblyName System.Drawing
$root = $PSScriptRoot
$img = [System.Drawing.Image]::FromFile((Join-Path $root "screen.png"))
$crop = New-Object System.Drawing.Bitmap(1200, 400)
$g = [System.Drawing.Graphics]::FromImage($crop)
$src = New-Object System.Drawing.Rectangle(700, 400, 1200, 400)
$dst = New-Object System.Drawing.Rectangle(0, 0, 1200, 400)
$g.DrawImage($img, $dst, $src, [System.Drawing.GraphicsUnit]::Pixel)
$crop.Save((Join-Path $root "crop_top.png"))
Write-Output "OK"
