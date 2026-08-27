# Windows에서 llm-front 이미지를 만들어 EC2에 tar로 올린다. Docker Hub 사용 안 함.
# 사용: 저장소 루트에서 .\aws\deploy-front.ps1
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$key = Join-Path $PSScriptRoot "test-keypair.pem"
$hostName = "43.202.113.123"
$user = "ubuntu"
$image = "llm-front:1.0"
$tarName = "llm-front-1.0.tar"
$localTar = Join-Path $repo $tarName
$remoteDir = "/home/ubuntu/llm"
$frontDir = Join-Path $repo "front"

if (-not (Test-Path -LiteralPath $key)) {
    throw "PEM not found: $key"
}

function Invoke-Remote([string]$Command) {
    & ssh -i $key -o StrictHostKeyChecking=accept-new "$user@$hostName" $Command
    if ($LASTEXITCODE -ne 0) {
        throw "remote command failed: $Command"
    }
}

Push-Location $frontDir
try {
    if (-not (Test-Path (Join-Path $frontDir "node_modules"))) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    }
    npm run typecheck
    if ($LASTEXITCODE -ne 0) { throw "typecheck failed" }
    $env:NUXT_PUBLIC_API_BASE = ""
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "nuxi generate failed" }
    if (-not (Test-Path (Join-Path $frontDir ".output\public\index.html"))) {
        throw "missing front/.output/public/index.html"
    }
}
finally {
    Pop-Location
}

docker build -t $image $frontDir
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

$remoteSh = Join-Path $env:TEMP "llm-load-front.sh"
$remoteBody = @'
#!/bin/bash
set -euo pipefail
cd __REMOTE_DIR__
docker load -i __TAR_NAME__
if grep -q '^LLM_FRONT_IMAGE=' .env; then
  sed -i 's/^LLM_FRONT_IMAGE=.*/LLM_FRONT_IMAGE=llm-front:1.0/' .env
else
  printf '\nLLM_FRONT_IMAGE=llm-front:1.0\n' >> .env
fi
export LLM_ENV_FILE=__REMOTE_DIR__/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 180 front
docker inspect --format '{{.Name}} image={{.Config.Image}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' llm-front
curl -fsS -m 15 http://127.0.0.1:8083/api/v1/health
echo
'@
$remoteBody = $remoteBody.Replace('__REMOTE_DIR__', $remoteDir).Replace('__TAR_NAME__', $tarName)
$unix = $remoteBody -replace "`r`n", "`n" -replace "`r", "`n"
[System.IO.File]::WriteAllText($remoteSh, $unix)

& scp -i $key -o StrictHostKeyChecking=accept-new $remoteSh "${user}@${hostName}:${remoteDir}/load-front.sh"
if ($LASTEXITCODE -ne 0) { throw "scp load script failed" }
Invoke-Remote "chmod 700 $remoteDir/load-front.sh; bash $remoteDir/load-front.sh"

Write-Host "deployed $image"
