#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
if [[ ! -f "${DEFAULT_COMPOSE_FILE}" && -f "${SCRIPT_DIR}/../docker-compose.yml" ]]; then
  DEFAULT_COMPOSE_FILE="${SCRIPT_DIR}/../docker-compose.yml"
fi
COMPOSE_FILE="${COMPOSE_FILE:-${DEFAULT_COMPOSE_FILE}}"
DEFAULT_ENV_FILE="${SCRIPT_DIR}/.env"
if [[ ! -f "${DEFAULT_ENV_FILE}" && -f "/home/ubuntu/llm/.env" ]]; then
  DEFAULT_ENV_FILE="/home/ubuntu/llm/.env"
fi
ENV_FILE="${ENV_FILE:-${DEFAULT_ENV_FILE}}"
PROJECT_NAME="${PROJECT_NAME:-${COMPOSE_PROJECT_NAME:-ubuntu}}"
WAIT_TIMEOUT=180

usage() {
  cat <<'EOF'
Usage: ./deploy-ec2.sh [options]

Options:
  -c, --compose-file <path>   Compose file path (default: docker-compose.yml next to this script or repo root)
  -e, --env-file <path>       Env file path (default: .env next to this script)
  -p, --project-name <name>   Compose project name (default: ubuntu)
  -t, --wait-timeout <sec>    Maximum wait duration for healthy services (default: 180)
  -w, --wait-seconds <sec>    Deprecated alias of --wait-timeout
  -h, --help                  Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    -e|--env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    -p|--project-name)
      PROJECT_NAME="${2:-}"
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

if [[ -z "${PROJECT_NAME}" ]]; then
  echo "[ERROR] project name is empty." >&2
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
echo "[INFO] Project name: ${PROJECT_NAME}"
echo "[INFO] Wait timeout: ${WAIT_TIMEOUT}s"

# Preflight: Docker daemon reachable + required external network present.
# auto_default is owned by the shared auto-postgres stack; auto-creating an
# empty one here would only mask a missing database, so fail fast with guidance.
if ! docker info >/dev/null 2>&1; then
  echo "[ERROR] Cannot reach the Docker daemon. Is Docker running and do you have permission?" >&2
  exit 1
fi
if ! docker network inspect auto_default >/dev/null 2>&1; then
  echo "[ERROR] Required external network 'auto_default' not found." >&2
  echo "[ERROR] This stack joins 'auto_default' to reach the shared auto-postgres database." >&2
  echo "[ERROR] Ensure the auto-postgres stack is running (it owns this network)," >&2
  echo "[ERROR] or create it intentionally: docker network create auto_default" >&2
  exit 1
fi

# On any deploy step failure, surface container state for diagnosis. This does
# NOT roll back automatically (latest-only tags; see docs/13 for manual rollback).
deployment_failed() {
  local code=$?
  echo "[ERROR] Deployment step failed (exit ${code}). Current container state:" >&2
  LLM_ENV_FILE="${ENV_FILE}" docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps 2>&1 || true
  echo "[ERROR] For logs: docker compose --project-name ${PROJECT_NAME} --env-file ${ENV_FILE} -f ${COMPOSE_FILE} logs --tail 100" >&2
}
trap deployment_failed ERR

echo "[STEP 1/3] docker compose pull"
LLM_ENV_FILE="${ENV_FILE}" docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull

echo "[STEP 2/3] docker compose up -d --wait --wait-timeout ${WAIT_TIMEOUT} --remove-orphans"
LLM_ENV_FILE="${ENV_FILE}" docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --wait --wait-timeout "${WAIT_TIMEOUT}" --remove-orphans

echo "[STEP 3/3] docker compose ps"
LLM_ENV_FILE="${ENV_FILE}" docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
