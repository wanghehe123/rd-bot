# RD-Bot OpenViking 合同环境

单实例、固定 digest，只给 WP-0 及后续投影合同使用。不是生产 OpenViking。

## 启动 / 停止

在仓库根目录：

```bash
scripts/openviking/up.sh
scripts/openviking/down.sh
```

`up.sh` 会 `docker compose up -d --wait`，并 `curl http://127.0.0.1:1933/health`。Studio 只绑定回环地址。

数据在 Docker volume `rd-bot-openviking-data`，不复用 `~/.openviking`。`down.sh` 不删 volume。

## 钉死镜像

- OpenViking：`ghcr.io/volcengine/openviking:v0.4.13@sha256:92ad51e68b028d17642d2ece77f83800c71cdcb511217eb04349f4f651cc97e8`
- Mock LLM：`python:3.13-alpine@sha256:540c7d91f98ff6880174c40e99067bf5941eb54d818a7a5e094d188b196a934d`

不要改成 `latest`。`ov.conf` 里的 `root_api_key` / mock API key 是本地合同占位符，生产必须替换且不得提交真实密钥。

## 真实合同测试

```bash
./mvnw -q -pl bootstrap -am \
  -Drd.openviking.smoke=true \
  -Dtest=OpenVikingContractJsonFixturesTest,OpenVikingRealContractSmokeTest,OpenVikingRealContractSmokePreconditionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试只在 `viking://resources/rd-bot/wp0-contract/{runId}/` 下创建和删除资源。普通 `./mvnw test` 不会打这个栈。

协议与错误码见 `docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`。
