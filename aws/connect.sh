#!/usr/bin/env bash
# EC2 SSH 접속. 사용법: ./connect.sh 또는 ./connect.sh "cd /home/ubuntu/kiwoom; ls"
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KEY="$SCRIPT_DIR/test-keypair.pem"
HOST="43.202.113.123"
USER_NAME="ubuntu"

if [[ ! -f "$KEY" ]]; then
    printf 'PEM 키를 찾을 수 없습니다: %s\n' "$KEY" >&2
    exit 1
fi

# OpenSSH가 개인 키를 거부하지 않도록 소유자만 읽고 쓸 수 있게 한다.
chmod 600 "$KEY"

exec ssh \
    -i "$KEY" \
    -o StrictHostKeyChecking=accept-new \
    "$USER_NAME@$HOST" \
    "$@"
