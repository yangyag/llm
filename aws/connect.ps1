# EC2 SSH. Usage: .\connect.ps1   or   .\connect.ps1 "cd /home/ubuntu/kiwoom; ls"
$ErrorActionPreference = "Stop"

$key = Join-Path $PSScriptRoot "test-keypair.pem"
$hostName = "43.202.113.123"
$user = "ubuntu"

if (-not (Test-Path -LiteralPath $key)) {
    throw "PEM not found: $key"
}

# Windows OpenSSH rejects a key that other accounts can read.
icacls $key /inheritance:r | Out-Null
icacls $key /grant:r "$($env:USERNAME):R" | Out-Null

$sshArgs = @(
    "-i", $key,
    "-o", "StrictHostKeyChecking=accept-new",
    "$user@$hostName"
)
if ($args.Count -gt 0) {
    $sshArgs += $args
}

& ssh @sshArgs
exit $LASTEXITCODE
