Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in @(
    'C:\Users\Administrator\.m2\repository\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar',
    'C:\Users\Administrator\.m2\repository\net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar',
    'C:\Users\Administrator\.m2\repository\com\github\oshi\oshi-core\6.6.3\oshi-core-6.6.3.jar'
)) {
    Write-Output "=== $jar ==="
    try {
        $z = [IO.Compression.ZipFile]::OpenRead($jar)
        $z.Entries | Where-Object { $_.FullName -like 'META-INF/native-image/*' } | ForEach-Object { Write-Output $_.FullName }
        $z.Dispose()
    } catch {
        Write-Output "ERROR: $_"
    }
}
