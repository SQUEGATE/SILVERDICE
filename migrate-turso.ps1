$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$databaseUrl = 'libsql://silverdice-database-squegate.aws-us-east-1.turso.io'
$buildDirectory = "gradle-build-migration-$([guid]::NewGuid().ToString('N'))"

Write-Host 'Compiling migration code into a fresh build folder...'
$compileLog = Join-Path $env:TEMP 'comp-manager-turso-compile-latest.log'
& (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot "-PverificationBuildDir=$buildDirectory" compileJava --no-daemon --console=plain *> $compileLog
$compileExitCode = $LASTEXITCODE
Get-Content -LiteralPath $compileLog -Encoding Unicode
if ($compileExitCode -ne 0) {
    throw "Migration code compilation failed with exit code $compileExitCode. No token was requested and no Turso changes were made. See $compileLog."
}

$null = Add-Type -AssemblyName System.Windows.Forms
$null = Add-Type -AssemblyName System.Drawing
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Turso Migration Token'
$form.Size = New-Object System.Drawing.Size(540, 190)
$form.StartPosition = 'CenterScreen'
$form.TopMost = $true

$label = New-Object System.Windows.Forms.Label
$label.Text = 'Paste a NEW database-scoped READ/WRITE Turso token:'
$label.AutoSize = $true
$label.Location = New-Object System.Drawing.Point(14, 16)
$form.Controls.Add($label)

$tokenBox = New-Object System.Windows.Forms.TextBox
$tokenBox.Location = New-Object System.Drawing.Point(14, 46)
$tokenBox.Size = New-Object System.Drawing.Size(495, 26)
$tokenBox.UseSystemPasswordChar = $true
$form.Controls.Add($tokenBox)

$okButton = New-Object System.Windows.Forms.Button
$okButton.Text = 'Run Migration'
$okButton.Location = New-Object System.Drawing.Point(300, 92)
$okButton.Size = New-Object System.Drawing.Size(120, 32)
$okButton.DialogResult = [System.Windows.Forms.DialogResult]::OK
$form.Controls.Add($okButton)
$form.AcceptButton = $okButton

$cancelButton = New-Object System.Windows.Forms.Button
$cancelButton.Text = 'Cancel'
$cancelButton.Location = New-Object System.Drawing.Point(430, 92)
$cancelButton.Size = New-Object System.Drawing.Size(80, 32)
$cancelButton.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
$form.Controls.Add($cancelButton)
$form.CancelButton = $cancelButton

if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
    $form.Dispose()
    throw 'Migration cancelled before making any changes.'
}
$token = $tokenBox.Text.Trim()
$tokenBox.Clear()
$form.Dispose()
if ([string]::IsNullOrWhiteSpace($token)) {
    throw 'No token was entered. Run the migration again and paste the JWT into the masked dialog.'
}
$tokenSegmentCount = ([System.Text.RegularExpressions.Regex]::Matches($token, '\.').Count) + 1
Write-Host "Token input diagnostics: $($token.Length) characters, $tokenSegmentCount JWT segments (value hidden)."
if ($tokenSegmentCount -ne 3) {
    throw 'This is not a three-part database JWT. Use the token printed by `turso db tokens create silverdice-database`; paste only the token, not the URL, token name, or command.'
}

$exitCode = 1
try {
    $env:TURSO_DATABASE_URL = $databaseUrl
    $env:TURSO_AUTH_TOKEN = $token
    Write-Host "Token received ($($token.Length) characters; value hidden). Testing Turso before migration..."
    $logPath = Join-Path $env:TEMP 'comp-manager-turso-migration-latest.log'
    & (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot "-PverificationBuildDir=$buildDirectory" migrateTurso --no-daemon --console=plain *> $logPath
    $exitCode = $LASTEXITCODE
    Get-Content -LiteralPath $logPath -Encoding Unicode
    Write-Host "Migration output saved to $logPath"
} finally {
    $token = $null
    Remove-Item Env:TURSO_AUTH_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:TURSO_DATABASE_URL -ErrorAction SilentlyContinue
}

exit $exitCode
