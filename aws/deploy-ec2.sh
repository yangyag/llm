#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.ec2.yml"
ENV_FILE="/home/ubuntu/llm.env"
WAIT_TIMEOUT=180

usage() {
  cat <<'EOF'
Usage: ./deploy-ec2.sh [options]

Options:
  -e, --env-file <path>       Env file path (default: /home/ubuntu/llm.env)
  -t, --wait-timeout <sec>    Maximum wait duration for healthy services (default: 180)
  -w, --wait-seconds <sec>    Deprecated alias of --wait-timeout
  -h, --help                  Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -e|--env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    -t|--wait-timeout)
      WAIT_TIMEOUT="${2:-}"
      shift 2
      ;;
    -w|--wait-seconds)
      WAIT_TIMEOUT="${2:-}"
      echo "[WARN] --wait-seconds is deprecated. Use --wait-timeout instead." >&2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${ENV_FILE}" ]]; then
  echo "[ERROR] env file path is empty." >&2
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "[ERROR] Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[ERROR] Env file not found: ${ENV_FILE}" >&2
  exit 1
fi

if ! [[ "${WAIT_TIMEOUT}" =~ ^[0-9]+$ ]]; then
  echo "[ERROR] wait-timeout must be a non-negative integer: ${WAIT_TIMEOUT}" >&2
  exit 1
fi

echo "[INFO] Compose file: ${COMPOSE_FILE}"
echo "[INFO] Env file: ${ENV_FILE}"
echo "[INFO] Wait timeout: ${WAIT_TIMEOUT}s"

echo "[STEP 1/3] docker compose pull"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull

echo "[STEP 2/3] docker compose up -d --wait --wait-timeout ${WAIT_TIMEOUT} --remove-orphans"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --wait --wait-timeout "${WAIT_TIMEOUT}" --remove-orphans

echo "[STEP 3/3] docker compose ps"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
