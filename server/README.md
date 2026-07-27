# ntfy サーバ

Peranta が中継に使う ntfy サーバの設定と起動用 compose。

ntfy 本体は Go 製の単一バイナリで、公式イメージ（`binwiederhier/ntfy`）をそのまま使う。
ビルドするものは無いので Dockerfile も無い。設定は `server.yml` を読み取り専用で
マウントするだけ。

## ファイル

| ファイル | 用途 |
|----------|------|
| `server.yml` | ntfy の設定。開発・本番で共通（環境依存の値は環境変数で上書きする） |
| `compose.yaml` | 本番。前段の TLS プロキシと external network `proxy` で繋ぐ |
| `compose.caddy.yaml` / `Caddyfile` | 前段の TLS リバースプロキシ。1 台で完結させる場合に使う |
| `compose.dev.yaml` | 開発用。`localhost:8090` に平文で publish する |

## 本番の立ち上げ

前提は Docker の動く Linux サーバ 1 台、ドメイン、そして **80/443 が外から到達できること**
（80 番は Let's Encrypt の証明書取得に必要）。

### 1. DNS を向ける

使うドメインの A レコードをサーバの IP へ向ける。

### 2. ドメインを設定する

`.env.example` をコピーして、公開するドメインを書く。

```sh
cp .env.example .env
```

```sh
PERANTA_DOMAIN=ntfy.example.com
```

この値は ntfy の `base-url` と Caddy が証明書を取るドメインの両方に効く。未設定のまま
起動しようとすると compose がエラーで止まる。`.env` は gitignore 済み。

### 3. ネットワークを作る

ntfy とリバースプロキシは別々の compose なので、共有するネットワークを先に作る。

```sh
docker network create proxy
```

### 4. リバースプロキシを起動する

```sh
docker compose -f compose.caddy.yaml up -d
```

Caddy がドメイン名から Let's Encrypt の証明書を自動で取得し、以後の更新も自動で行う。

**既に別のリバースプロキシが動いているなら、この手順は飛ばす**。代わりに、そちらの設定へ
`peranta-ntfy` コンテナの 80 番への振り分けを足し、そのプロキシも `proxy` ネットワークへ
参加させる。ntfy は購読に WebSocket を使うので、プロキシがプロトコルのアップグレードを
透過することを確認しておくこと。

### 5. ntfy を起動する

```sh
docker compose up -d
```

### 6. ユーザーとトークンを作る

```sh
# ユーザー作成（パスワードは安全な場所に控える）
docker compose exec -e NTFY_PASSWORD=<pw> ntfy ntfy user add peranta

# ACL: Peranta が使うトピックへの読み書きを許可する
docker compose exec ntfy ntfy access peranta "peranta-*" rw
# UnifiedPush 用。ntfy アプリが払い出す upXXXX トピックの購読/配送に必要
docker compose exec ntfy ntfy access peranta "up*" rw

# アクセストークン発行（Peranta の接続設定に使う）
docker compose exec ntfy ntfy token add peranta
```

`auth-default-access: deny-all` なので、トークンを持たないアクセスは一切通らない。
発行したトークンはリポジトリに含めないこと。

### 7. クライアントを設定する

発行したトークンとドメインを Peranta の初期設定ウィザードに入力する。以降の手順は
[`../docs/setup.md`](../docs/setup.md) を参照。

## ディスクとバックアップ

**バックアップすべきなのは `data/user.db` だけ**。ユーザー・ACL・トークンが入っていて、
数 KB のまま増えない。失うとトークンと ACL を作り直すことになり、全端末の再設定が必要になる。
逆にこのファイルさえ退避しておけば、サーバはいつでも作り直せる。

`cache/` の中身（メッセージと添付）は**バックアップ不要**。いずれも保持期間を過ぎると
自動で消えるため、失っても直近のメッセージが消えるだけで自然に回復する。

**容量は放っておいてよい**。ntfy に単調増加するものは無く、定常状態で頭打ちになる。

- メッセージ（`cache.db`）: 保持は既定 24 時間。1 件あたり最大 16 KB なので合計しても数 MB
  程度。優先度の高い通知は publish 時に 60 秒へ短縮している
- 添付（暗号化 blob）: 保持 72 時間、合計上限 2 GB（`server.yml` の
  `attachment-total-size-limit`）。ここが実質的な天井になる

## 開発

ローカルでは TLS プロキシを挟まず、平文の `localhost:8090` で動かす。

```sh
docker compose -f compose.dev.yaml up -d
```

ユーザー・ACL・トークンの作り方は本番と同じ（`-f compose.dev.yaml` を付ける）。
データと発行したトークンの控えは `.local/` に置く（gitignore 済み）。

開発ビルドは TLS 既定オフなのでこのまま接続できる。リリースビルドは HTTPS 固定。
