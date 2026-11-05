param([string]$Config = "$PSScriptRoot/local-config.psd1")
$ErrorActionPreference = 'Stop'
try {
    if (-not (Test-Path -LiteralPath $Config)) { throw 'Copy local-config.example.psd1 to local-config.psd1 and set your MySQL password first.' }
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Config, [ref]$null, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { throw 'Invalid configuration syntax.' }
    $settings = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.HashtableAst] }, $true).SafeGetValue()
    foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key, [string]$settings[$key], 'Process') }
    if ($env:CAM_DB_PASSWORD -eq 'REPLACE_WITH_YOUR_DATABASE_PASSWORD') { throw 'Set CAM_DB_PASSWORD in local-config.psd1.' }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    $versionText = (& $java --version | Out-String)
    if ($versionText -notmatch '(?:openjdk|java) (\d+)' -or [int]$Matches[1] -lt 21) { throw 'Install JDK 21 and set JAVA_HOME to its installation folder.' }
    if ([int]$Matches[1] -ne 21) { Write-Warning 'JDK 21 is the documented version. Your newer JDK will compile for Java 21.' }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($env:CAM_DB_HOST, [int]$env:CAM_DB_PORT)
        if (-not $task.Wait(5000) -or -not $client.Connected) { throw 'Connection timeout' }
    } catch { throw 'MySQL is not reachable. Start the MySQL service and check CAM_DB_HOST/CAM_DB_PORT.' }
    finally { $client.Dispose() }
    $env:CAM_DB_URL = "jdbc:mysql://$($env:CAM_DB_HOST):$($env:CAM_DB_PORT)/$($env:CAM_DB_NAME)?serverTimezone=UTC"
    Push-Location "$PSScriptRoot/backend"
    try {
        & .\mvnw.cmd --batch-mode verify
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. Review the Maven error above.' }
        Write-Host 'Starting http://localhost:8080. Wait for Started AttendanceApplication, then open the browser. Ctrl+C stops the server.'
        & $java -jar target/attendance-api-1.0.0.jar
        if ($LASTEXITCODE -ne 0) { throw 'Startup failed. Check MySQL credentials/database and the error above.' }
    } finally { Pop-Location }
} catch { Write-Error $_; exit 1 }
