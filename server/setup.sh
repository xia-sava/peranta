#!/usr/bin/env bash
# 本番の ntfy を立ち上げる。各手順の背景は README.md を参照。
#
#   ./setup.sh
#
# 何度実行しても同じ状態に落ち着くので、失敗した原因を直してから流し直せる。
# 前段のリバースプロキシは扱わない（README.md「前段のリバースプロキシへの要件」）。
# ユーザー・ACL・トークンの作成も扱わない（発行したトークンがシェル履歴やログへ残るのを
# 避けるため）。必要な手順は最後に案内する。

set -euo pipefail

cd "$(dirname "$0")"

# 前段のプロキシを通った疎通を待つ上限。
readonly HEALTH_TIMEOUT_SECONDS=60

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

step "前段のプロキシを確かめる"

# ネットワークはプロキシと共有するもので、プロキシを用意する側が作る。ここで作ってしまうと、
# プロキシの繋がっていないネットワークができて、起動は通るのに外から届かない状態になる。
docker network inspect proxy > /dev/null 2>&1 || abort "ネットワーク proxy がありません。" \
    "前段のリバースプロキシを先に用意してください（README.md）。" \
    "ネットワーク自体が無いなら docker network create proxy で作れます。"
echo "proxy があります。"

step "ntfy を起動する"

docker compose up -d

step "疎通を確かめる"

# 前段を通った応答が、TLS 終端から ntfy までが繋がっていることの証拠になる。
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
while ! curl -fsS "https://$PERANTA_DOMAIN/v1/health" > /dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        abort "${HEALTH_TIMEOUT_SECONDS} 秒待っても https://$PERANTA_DOMAIN/v1/health が応答しません。" \
            "次を確認してください:" \
            "  - 前段のプロキシが proxy ネットワークに参加し、peranta-ntfy:80 へ振り分けているか" \
            "  - プロキシが $PERANTA_DOMAIN の証明書を取得できているか" \
            "  - 80/443 が外から到達できるか（ホストのファイアウォール、プロバイダのパケットフィルタ）" \
            "ログ: docker compose logs ntfy"
    fi
    sleep 2
done
echo "https://$PERANTA_DOMAIN/v1/health が応答しました。"

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
