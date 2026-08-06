#!/usr/bin/env bash
# EC2 접속 정보는 Git 저장소가 아닌 사용자 로컬 ~/aws 또는 환경 변수에서만 읽는다.
set -euo pipefail

AWS_DIR="${EC2_SSH_AWS_DIR:-${HOME}/aws}"
ACCESS_NOTE="${EC2_SSH_ACCESS_NOTE:-${AWS_DIR}/ec2-llm-access.md}"
SSH_USER="${EC2_SSH_USER:-ubuntu}"
SSH_HOST="${EC2_SSH_HOST:-}"
SSH_PORT="${EC2_SSH_PORT:-22}"
KEY_FILE="${EC2_SSH_KEY:-}"

usage() {
  cat <<'EOF'
Usage: ./connect-ec2.sh [remote-command...]

사용자 로컬 ~/aws의 접속 메모와 PEM 파일을 사용해 EC2에 SSH로 접속합니다.
접속 정보는 이 저장소에 저장하지 않습니다.

Environment overrides:
  EC2_SSH_AWS_DIR       Local AWS directory
  EC2_SSH_ACCESS_NOTE   Connection-note file used to find the host
  EC2_SSH_HOST          SSH host (skips access-note lookup)
  EC2_SSH_USER          SSH user (default: ubuntu)
  EC2_SSH_PORT          SSH port (default: 22)
  EC2_SSH_KEY           PEM key path (skips automatic PEM discovery)

Examples:
  ./connect-ec2.sh
  ./connect-ec2.sh 'docker ps'
EOF
}

fail() {
  echo "[ERROR] $1" >&2
  exit 1
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  --)
    shift
    ;;
esac

if [[ -z "${SSH_HOST}" ]]; then
  [[ -r "${ACCESS_NOTE}" ]] || fail "EC2 호스트를 찾을 접속 메모를 읽을 수 없습니다. EC2_SSH_HOST를 설정하세요."
  SSH_HOST="$(grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' "${ACCESS_NOTE}" | head -n 1 || true)"
  [[ -n "${SSH_HOST}" ]] || fail "접속 메모에서 EC2 호스트를 찾지 못했습니다. EC2_SSH_HOST를 설정하세요."
fi

if [[ -z "${KEY_FILE}" ]]; then
  shopt -s nullglob
  pem_files=("${AWS_DIR}"/*.pem)
  shopt -u nullglob
  (( ${#pem_files[@]} == 1 )) || fail "PEM 파일을 하나만 자동 탐지할 수 없습니다. EC2_SSH_KEY를 설정하세요."
  KEY_FILE="${pem_files[0]}"
fi

[[ -r "${KEY_FILE}" ]] || fail "EC2 SSH 키 파일을 읽을 수 없습니다. EC2_SSH_KEY를 확인하세요."
[[ "${SSH_PORT}" =~ ^[0-9]+$ ]] || fail "EC2_SSH_PORT는 숫자여야 합니다."

exec ssh \
  -o ConnectTimeout=10 \
  -o IdentitiesOnly=yes \
  -o LogLevel=ERROR \
  -o StrictHostKeyChecking=yes \
  -p "${SSH_PORT}" \
  -i "${KEY_FILE}" \
  "${SSH_USER}@${SSH_HOST}" \
  "$@"
