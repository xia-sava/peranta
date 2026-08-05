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
| `compose.dev.yaml` | 開発用。`127.0.0.1:8090` に平文で publish する |

## 前段のリバースプロキシへの要件

ntfy はポートを publish せず、external network `proxy` を通じて前段の TLS リバースプロキシから
到達させる。**プロキシ自体はこのリポジトリでは扱わない。** 用意する側が満たすべき条件は次のとおり。

- **`peranta-ntfy` コンテナの 80 番へ振り分ける。** コンテナ名は `compose.yaml` で固定していて、
  プロキシ側の設定がこの名前を参照する。
- **プロキシも external network `proxy` に参加させる。**
- **WebSocket（Upgrade）を透過する。** 購読は常時接続で行う。
- **リクエストボディを 350MB 以上通す。** 添付 1 件あたりの上限（`server.yml` の
  `attachment-file-size-limit`）に合わせる。ここが小さいと大きなファイルの転送だけが失敗する。
- **長時間の接続をアイドルで切らない。**
- **プロキシが serve するホスト名を `.env` の `PERANTA_DOMAIN` と一致させる。** ntfy は添付を
  絶対 URL で返すため、ずれると通知は届くのに添付だけ取得できなくなる。

Caddy ならこれで足りる。証明書の取得・更新も任せられる。

```
ntfy.example.com {
	reverse_proxy peranta-ntfy:80
}
```

## 本番の立ち上げ

前提は Docker の動く Linux サーバ 1 台、ドメイン、そして上の要件を満たす前段のリバースプロキシ。
プロキシが Let's Encrypt で証明書を取るなら、**80 番が外から到達できること**も要る。

**どのポートが外から見えるかを決めるのは compose の `ports` であって、ufw ではない。**
Docker の port publish は iptables の `DOCKER-USER` チェーンで処理され、ufw のルールを迂回する。
ufw で 80/443 を許可するのは Docker を経由しない経路のためで、publish したポートは
ufw で塞いだつもりでも外から届く。公開したくないポートは publish しないか、
バインドアドレスを `127.0.0.1` に限る（`compose.dev.yaml` がその形）。

手順 2〜4 は `./setup.sh` でまとめて実行できる。何度流しても同じ状態に落ち着くので、
失敗の原因を直してから実行し直せる。トークンが表示される手順 5 だけは扱わないので、
案内に従って手で実行する。以下は各手順の内訳。

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

この値は ntfy の `base-url` になり、添付の絶対 URL にも使われる。未設定のまま起動しようと
すると compose がエラーで止まる。`.env` は gitignore 済み。

### 3. ネットワークと前段のプロキシを用意する

ntfy とリバースプロキシは別々の compose なので、共有するネットワークを先に作る。

```sh
docker network create proxy
```

続いて、前段のリバースプロキシをこのネットワークへ参加させて起動する。設定の要件は上の節を
参照。ここまで済ませておくと、最後の疎通確認がそのまま前段を含めた確認になる。

### 4. ntfy を起動する

```sh
docker compose up -d
```

### 5. ユーザーとトークンを作る

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

**パスワードは 32 文字以上のランダム文字列にする。** ユーザー名 `peranta` はこの手順に固定文字列で
書かれた公開の値で、ntfy はトークンとは別にパスワードでも認証を通す。したがってパスワードの強度が
そのまま防壁の強度になり、破られると上の ACL がそのまま渡る。

**ACL は上の `peranta-*` と `up*` の 2 つだけとする。** とくに匿名（`*`）への書き込み許可は、
`auth-default-access: deny-all` の前提を崩し、ドメイン名を知るだけの第三者が添付枠を埋められる状態を作る
（他の UnifiedPush 対応アプリを併用したい場合の扱いは [`../docs/setup.md`](../docs/setup.md) を参照）。

`auth-default-access: deny-all` なので、トピックの購読と publish はトークンなしには通らない。
ただし**添付は例外で、URL を知っていれば認証なしに取得できる**（ntfy の仕様）。Peranta は
添付を端末側で暗号化してから置くため、URL が漏れても中身は読めない。

発行したトークンとパスワードはリポジトリに含めないこと。

### 6. クライアントを設定する

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
  `attachment-total-size-limit`）。ここが実質的な天井になる。
  単一の主体がこの枠を占有しないよう、`visitor-attachment-total-size-limit` を全体の 1/4 に置いている。
  1 主体が 72 時間のうちにその量を超えて添付を置こうとすると、超えた分のアップロードが拒まれる
  （保持期間切れで空けば再開する）

## 開発

ローカルでは TLS プロキシを挟まず、平文の `127.0.0.1:8090` で動かす。トークンが平文で流れるため、
バインドはループバックに限る（同じ LAN の第三者に見せない）。

```sh
docker compose -f compose.dev.yaml up -d
```

ユーザー・ACL・トークンの作り方は本番と同じ（`-f compose.dev.yaml` を付ける）。
データと発行したトークンの控えは `.local/` に置く（gitignore 済み）。

開発ビルドは TLS 既定オフなのでこのまま接続できる。リリースビルドは HTTPS 固定。
