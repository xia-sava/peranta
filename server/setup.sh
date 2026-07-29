#!/usr/bin/env bash
# 本番の ntfy を立ち上げる。各手順の背景は README.md を参照。
#
#   ./setup.sh
#
# 何度実行しても同じ状態に落ち着くので、失敗した原因を直してから流し直せる。
# ユーザー・ACL・トークンの作成は扱わない（発行したトークンがシェル履歴やログへ残るのを
# 避けるため）。必要な手順は最後に案内する。

set -euo pipefail

cd "$(dirname "$0")"

# 証明書の取得を待つ上限。Let's Encrypt との往復は通常 10 秒ほどで終わる。
readonly CERTIFICATE_TIMEOUT_SECONDS=120

# 新規取得を示す Caddy のログ。取得済みの再実行ではこの行が出ないため、
# 証明書ファイルの実在（certificate_present）と併せて判定する。
readonly CERTIFICATE_SUCCESS_PATTERN='certificate obtained successfully'

# 取得済みの証明書がボリュームにあるか。コンテナを作り直しただけの再実行を成功と判定する。
certificate_present() {
    docker compose -f compose.caddy.yaml exec -T caddy \
        find /data/caddy/certificates -name "$PERANTA_DOMAIN.crt" -type f 2> /dev/null |
        grep -q .
}

abort() {
    echo "エラー: $1" >&2
    shift
    for line in "$@"; do
        echo "  $line" >&2
    done
    exit 1
}

step() {
    echo
    echo "== $1"
}

step "設定を読む"

[ -f .env ] || abort ".env がありません。" \
    "cp .env.example .env でコピーし、公開するドメインを設定してください。"

# shellcheck source=/dev/null
. ./.env
: "${PERANTA_DOMAIN:?.env に PERANTA_DOMAIN を設定してください（.env.example 参照）}"
echo "ドメイン: $PERANTA_DOMAIN"

command -v docker > /dev/null || abort "docker が見つかりません。" \
    "README.md の手順で Docker を入れてください。"
docker compose version > /dev/null 2>&1 || abort "docker compose (v2) が見つかりません。" \
    "docker-compose-plugin を入れてください。"
docker info > /dev/null 2>&1 || abort "docker デーモンに接続できません。" \
    "sudo usermod -aG docker \$USER のあと、ログインし直してください。"

step "DNS を確かめる"

getent hosts "$PERANTA_DOMAIN" > /dev/null || abort "$PERANTA_DOMAIN を名前解決できません。" \
    "A レコード（または CNAME）を用意してください。向き先がこのサーバかどうかまでは確かめません。"
echo "名前解決できました。"

step "ネットワークを用意する"

if docker network inspect proxy > /dev/null 2>&1; then
    echo "proxy は作成済みです。"
else
    docker network create proxy > /dev/null
    echo "proxy を作成しました。"
fi

step "リバースプロキシを起動する"

docker compose -f compose.caddy.yaml up -d

step "証明書の取得を待つ"

deadline=$((SECONDS + CERTIFICATE_TIMEOUT_SECONDS))
while true; do
    if docker compose -f compose.caddy.yaml logs caddy 2>/dev/null | grep -q "$CERTIFICATE_SUCCESS_PATTERN"; then
        echo "取得できました。"
        break
    fi
    if certificate_present; then
        echo "取得済みの証明書があります。"
        break
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        abort "${CERTIFICATE_TIMEOUT_SECONDS} 秒待っても証明書を取得できませんでした。" \
            "80 番が外から届いていない可能性があります。次を確認してください:" \
            "  - さくら VPS のパケットフィルタ（22/80/443 を許可、または無効化）" \
            "  - sudo ufw status（inactive でなければ 80/443 を許可）" \
            "ログ: docker compose -f compose.caddy.yaml logs caddy"
    fi
    sleep 2
done

step "ntfy を起動する"

docker compose up -d

step "疎通を確かめる"

if curl -fsS "https://$PERANTA_DOMAIN/v1/health" > /dev/null; then
    echo "https://$PERANTA_DOMAIN/v1/health が応答しました。"
else
    echo "警告: health に応答がありません。docker compose logs ntfy を確認してください。" >&2
fi

cat <<MESSAGE

== 残りの手順（トークンが表示されるため、この後は手で実行してください）

  docker compose exec -e NTFY_PASSWORD=<パスワード> ntfy ntfy user add peranta
  docker compose exec ntfy ntfy access peranta "peranta-*" rw
  docker compose exec ntfy ntfy access peranta "up*" rw
  docker compose exec ntfy ntfy token add peranta

up* は UnifiedPush 用です（ntfy アプリが払い出す upXXXX トピックの購読に要ります）。
パスワードは 32 文字以上のランダム文字列にしてください。ユーザー名 peranta は公開の固定文字列で、
ntfy はトークンとは別にパスワードでも認証を通すためです。
ACL は上の peranta-* と up* の 2 つだけにしてください（匿名の書き込み許可については README.md 参照）。
発行したトークンとパスワードは安全な場所に控えてください。
MESSAGE
