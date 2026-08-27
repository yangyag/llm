# Windows에서 llm-back 이미지를 만들어 EC2에 tar로 올린다. Docker Hub 사용 안 함.
# 사용: 저장소 루트에서 .\aws\deploy-back.ps1
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$key = Join-Path $PSScriptRoot "test-keypair.pem"
$hostName = "43.202.113.123"
$user = "ubuntu"
$image = "llm-back:1.0"
$tarName = "llm-back-1.0.tar"
$localTar = Join-Path $repo $tarName
$remoteDir = "/home/ubuntu/llm"
$backDir = Join-Path $repo "back"

if (-not (Test-Path -LiteralPath $key)) {
    throw "PEM not found: $key"
}

function Invoke-Remote([string]$Command) {
    & ssh -i $key -o StrictHostKeyChecking=accept-new "$user@$hostName" $Command
    if ($LASTEXITCODE -ne 0) {
        throw "remote command failed: $Command"
    }
}

docker build -t $image $backDir
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

if (Test-Path $localTar) {
    Remove-Item -LiteralPath $localTar -Force
}
docker save -o $localTar $image
if ($LASTEXITCODE -ne 0) { throw "docker save failed" }

icacls $key /inheritance:r | Out-Null
icacls $key /grant:r "$($env:USERNAME):R" | Out-Null

$remoteTar = "$remoteDir/$tarName"
& scp -i $key -o StrictHostKeyChecking=accept-new $localTar "${user}@${hostName}:${remoteTar}"
if ($LASTEXITCODE -ne 0) { throw "scp image tar failed" }

$composeLocal = Join-Path $repo "docker-compose.yml"
& scp -i $key -o StrictHostKeyChecking=accept-new $composeLocal "${user}@${hostName}:${remoteDir}/docker-compose.yml"
if ($LASTEXITCODE -ne 0) { throw "scp compose failed" }

$remoteSh = Join-Path $env:TEMP "llm-load-back.sh"
$remoteBody = @'
#!/bin/bash
set -euo pipefail
cd __REMOTE_DIR__
docker load -i __TAR_NAME__
if grep -q '^LLM_BACK_IMAGE=' .env; then
  sed -i 's/^LLM_BACK_IMAGE=.*/LLM_BACK_IMAGE=llm-back:1.0/' .env
else
  printf '\nLLM_BACK_IMAGE=llm-back:1.0\n' >> .env
fi
export LLM_ENV_FILE=__REMOTE_DIR__/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 180 back
docker inspect --format '{{.Name}} image={{.Config.Image}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' llm-back
curl -fsS -m 15 http://127.0.0.1:8083/api/v1/health
echo
'@
$remoteBody = $remoteBody.Replace('__REMOTE_DIR__', $remoteDir).Replace('__TAR_NAME__', $tarName)
$unix = $remoteBody -replace "`r`n", "`n" -replace "`r", "`n"
[System.IO.File]::WriteAllText($remoteSh, $unix)

& scp -i $key -o StrictHostKeyChecking=accept-new $remoteSh "${user}@${hostName}:${remoteDir}/load-back.sh"
if ($LASTEXITCODE -ne 0) { throw "scp load script failed" }
Invoke-Remote "chmod 700 $remoteDir/load-back.sh; bash $remoteDir/load-back.sh"

Write-Host "deployed $image"
