# 见隅 NAS 网页版

跑在家庭 NAS 上的见隅网页版。一个 Docker 容器，家长和孩子用浏览器直接访问，不需要装 App，也不需要一个中心服务器——数据放在你自己家里那台机器上。

```text
docker build --file apps/jianyu-web-nas/Dockerfile --tag jianyu-nas-web:local .
docker run --detach --name jianyu-nas --publish 8080:8080 --volume jianyu-data:/data jianyu-nas-web:local
```

然后打开 `http://<NAS 的地址>:8080`。

**这是开发预览。** 没有经过独立安全审计，不支持生产级多设备同步，请只使用虚构的家庭数据。下面的「诚实边界」一节逐条说明了它做到哪一步、没做到哪一步。

---

## 它和原生 Android App 是什么关系

同一个 Family Opportunity Engine，两个用法不同的交付物：

| | 原生 Android App | NAS 网页版 |
|---|---|---|
| 主要场景 | 随身设备 | 家里那台常开的机器，浏览器访问 |
| 数据存放 | 手机本机加密 | NAS 上的密文，浏览器里解密 |
| 引擎 | `packages/*` | `packages/*`，同一份 |

两者的家庭状态格式不同，也没有设备加入和签名检查点，所以**不声称**互相兼容。引擎层面是 Opportunity-compatible：同一套 Gate、Diversity、Context Firewall、事件与机会协议、生命周期策略，由仓库里同一批契约测试验证。想换设备时，网页版用自己格式的加密恢复包手动导入，和 Android 的恢复包不是同一个东西，谁也打不开谁。详见 [ADR 0030](../docs/adr/0030-web-family-state-and-recovery-bundle-v1.md)。

## 快速开始

### 用 docker compose

仓库里带了 compose 文件，比裸 `docker run` 好记：

```bash
docker compose --file apps/jianyu-web-nas/docker-compose.yml up --build --detach
```

默认发布到 `8080`，数据放在命名卷 `jianyu-data` 里。

### 用 docker run

```bash
docker volume create jianyu-data
docker run --detach \
  --name jianyu-nas \
  --publish 8080:8080 \
  --volume jianyu-data:/data \
  jianyu-nas-web:local
```

### 健康检查

```bash
curl http://127.0.0.1:8080/healthz
```

镜像自带 HEALTHCHECK，`docker ps` 的 STATUS 一列会显示 `healthy`。刚启动的几秒内是 `starting`，这是正常的。

### 环境变量

都有安全默认值，一个不填也能跑。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `JIANYU_PORT` | `8080` | 容器内监听端口 |
| `JIANYU_HOST` | `0.0.0.0` | 监听地址 |
| `JIANYU_DATA_DIR` | `/data` | 密文存放目录，建议挂卷 |
| `JIANYU_SESSION_TTL_MS` | 30 天 | 会话有效期，滑动续期 |
| `JIANYU_MAX_OBJECTS` | `512` | 每个家庭最多多少个密文对象 |
| `JIANYU_AI_TIMEOUT_MS` | `120000` | AI 代理超时 |
| `JIANYU_ALLOW_PRIVATE_FEED` | 关 | 允许世界信息 feed 指向家庭内网地址 |
| `JIANYU_INSECURE_HTTP` | 关 | 明文 HTTP 下也发会话 Cookie，**只限本机测试** |

改发布端口要同时改两处，否则容器健康检查会打不到：

```bash
docker run --detach --name jianyu-nas \
  --publish 18080:9090 \
  --env JIANYU_PORT=9090 \
  --volume jianyu-data:/data \
  jianyu-nas-web:local
```

---

## 各品牌 NAS 怎么部署

共同点只有一条：**让容器跑起来，把 8080 端口映射出去，给 `/data` 一个持久卷。** 剩下的每家不一样。

### 群晖 DSM（Container Manager）

1. 套件中心安装 **Container Manager**。
2. 项目 → 新增，选「从 Dockerfile 创建」，指向本仓库的 `apps/jianyu-web-nas/docker-compose.yml`。或者先「注册表」里没有现成镜像，就用「映像 → 添加 → 从文件/URL 构建」，构建上下文选**仓库根目录**（不是 `apps/jianyu-web-nas`），Dockerfile 填 `apps/jianyu-web-nas/Dockerfile`。
3. 容器 → 设置 → 端口设置：本地端口 `8080`，容器端口 `8080`，类型 TCP。
4. 存储空间设置：添加一个卷，挂载到 `/data`。
5. 启动。DSM 的「控制面板 → 终端机和 SNMP」不用开。

构建上下文必须是仓库根目录：浏览器客户端是按相对路径直接 import `packages/*` 的，镜像里没有构建步骤，也没有 npm 依赖。

### QNAP（Container Station）

1. App Center 安装 **Container Station**。
2. 「映像」→「构建」→ 上传本仓库（或 `git clone` 到 NAS 上），构建上下文选仓库根目录，Dockerfile 选 `apps/jianyu-web-nas/Dockerfile`。
3. 建容器时在「网络」里把 8080 映射出去，在「存储卷」里挂一个卷到 `/data`。
4. QNAP 的默认防火墙放行你选的那个本地端口。

### 绿联 / 铁威马 / OMV / Portainer

凡是能跑 Docker 的地方步骤都一样：构建上下文 = 仓库根目录，Dockerfile = `apps/jianyu-web-nas/Dockerfile`，端口映射 8080，卷挂 `/data`。Portainer 里用 Stacks 粘进去 compose 内容最省事。

OMV 用 `compose` 插件或者 `portainer` 插件都可以，注意 compose 文件里的相对路径 `../..` 要对应你放仓库的实际位置。

---

## 从外网访问：TLS 必须由反向代理终止

容器默认只听 HTTP。**不要**把 8080 直接暴露到公网——口令和密文都会以明文过网。做法是在 NAS 的反向代理上终止 TLS，再把请求转发到 8080：

```nginx
server {
  listen 443 ssl;
  server_name jianyu.example.com;

  ssl_certificate     /path/to/fullchain.pem;
  ssl_certificate_key /path/to/privkey.pem;

  location / {
    proxy_pass         http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
  }
}
```

代理一开 HTTPS，会话 Cookie 就会自动带上 `Secure`，不用额外配置。

如果你只有 HTTP 就想试一下（仅限本机或家庭内网调试），可以加 `--env JIANYU_INSECURE_HTTP=1`。这个开关只做一件事：让会话 Cookie 在没有 HTTPS 时也发得出去。它不是用来长期跑在生产上的，界面上也会显示「未启用安全 Cookie，仅限本机测试」。

群晖自带反向代理（控制面板 → 登录门户 → 反向代理），QNAP 有 myQNAPcloud 和反向代理，也可以用 Nginx Proxy Manager、Traefik、Caddy 任何一个。证书用 Let's Encrypt 或 NAS 自带的都可以。

---

## 备份

数据全在 `/data` 里，备份它就是备份整个见隅：

```bash
# 停下来再复制，最稳
docker stop jianyu-nas
docker run --rm --volumes-from jianyu-nas -v "$PWD:/backup alpine \
  tar czf /backup/jianyu-$(date +%Y%m%d).tar.gz -C /data .
docker start jianyu-nas
```

`/data` 下面只有三个目录：`households/`、`sessions/`、`objects/`。**里面全是密文**，备份件泄露不会直接泄露家庭内容——但请仍然当它是敏感资料：密文的大小、数量、时间戳都还在，而且口令和恢复码不在备份里，丢了它们备份也打不开。

恢复就是把备份解回 `/data` 再启动容器。

**口令和恢复码不在这里，也不在 NAS 上。** 口令只在你脑子里；恢复包是导出时下载到你自己机器上的一个加密文件，加上只显示一次的恢复码。两样都丢了，家庭数据就取不回来了——这一点在导出界面里写清楚了。

---

## 它到底怎么保护数据

一句话：**NAS 上只有密文。** 解密、Context Firewall、Gate、多样性选择，全在浏览器里用仓库里那套公共引擎完成。

- **建库**：浏览器生成随机盐，用 PBKDF2-SHA-256 派生两把独立的钥匙——一把给服务器做认证校验，一把加密家庭状态。后者从不出浏览器。
- **服务器存什么**：家庭的随机 ID、盐、迭代次数、`SHA-256(verifier)`、密文对象、会话的哈希。没有明文，没有姓名，没有兴趣，没有口令。
- **解锁**：浏览器提交 verifier，换一个 `HttpOnly`、`SameSite=Strict` 的会话 Cookie。服务器只存它的哈希。
- **写入**：单活跃写入者。另一台设备改过，服务器会拒绝并要求重载合并，界面明说「另一台设备已经更改过家庭记录」——**这不是自动同步**。
- **加入第二台设备**：导出加密恢复包 → 在另一台浏览器本地验证 → 显示两边家庭称呼让你确认 → 单独确认替换。全程手动，界面明确标注「不是自动同步」。

### AI 和世界信息

默认走 NAS 上的**瞬时代理**，浏览器直连 OpenAI 兼容服务大多会被 CORS 挡掉，代理就是为这个存在的。

代理只在一次调用的时间里经手你的 API 密钥和已经最小化过的任务上下文：**不写盘、不记日志、不留到下一次请求**。Context Firewall 在浏览器里先跑完，所以代理看到的只是你为这一次批准的那一小块。连接设置页把这条边界写明白了，也给了「浏览器直连」选项给自建服务。这就是 [FORKING.md](../FORKING.md) §7 要求的披露：代理是便利功能，不是隐私模型的一部分。

世界信息 feed 只接收地区、时间范围、语言和类别，而且必须精确匹配你已经批准的那一条查询，匹配在本机做。不可达时可以走同一个 feed 代理（只走 HTTPS、不带凭据、有大小和超时上限、默认拒绝私网地址；`JIANYU_ALLOW_PRIVATE_FEED=1` 是给家庭内网自建服务开的口子）。

**BYOK 的密钥只存在你这台浏览器的加密存储里，从不发到服务器持久化。**

---

## 诚实边界

这一节是和上面同等重要的部分。

**做到了：**

- 一个家庭在浏览器里建库、解锁、锁定、销毁（删掉服务器上全部密文）。
- 完整闭环：今天（发现 → 几扇门 + 留白 → 选 / 孩子否决 / 留白 → 可选看法）、家庭（孩子、成员、五阶段权力）、回望（时间线、孩子纠正、删除）、设置（BYOK、世界信息、导出、销毁）、16+ 最简成年交接。
- 引擎行为复用 `packages/*`，由仓库同一批契约测试验证。
- 服务器只存密文；对象不可变、ID 不透明、分页有上限、认证失败只延迟不锁死。

**没有做到，不要声称：**

- **没有独立安全审计。** SECURITY.md §10 的门禁一项都没过。
- **不是生产级多设备同步。** 是「单活跃写入者 + 冲突重载合并」，不是自动同步。
- **PBKDF2 不抗内存破解。** 210k 迭代和参考实现一致，但换 Argon2id 需要单独的 ADR、测试向量和评审。这是 v1 明说的弱点，也是它叫开发预览的原因之一。
- **没有儿童独立持钥。** 拿到家庭密钥或恢复包的人能读内容，和 Android 版说明的边界一样。
- **和 Android App 不互通。** 状态格式、恢复包格式都不一样。
- **没有遥测、没有通知、没有后台任务。** 服务器也不会做任何明文功能。
- **不声称模型推荐质量。** 离线演示是固定模板，明确标注为预览，不发送也不保存。

**残余风险，说清楚：** 密文的大小和数量、请求的时间、IP 地址，服务器都看得见。这是 SECURITY.md 给同步后端列过的元数据风险，这里一样存在。

---

## 开发

```bash
# 语法检查
node scripts/check.mjs

# 全部测试（含 NAS 网页版）
node --test "tests/**/*.test.js"

# 只跑 NAS 网页版的测试
node --test "tests/nas-web/*.test.js"
```

仓库没有 npm 运行时依赖，没有构建步骤。客户端是无构建的 ESM，改完刷新浏览器就行。

想不装 Docker 直接跑服务器：

```bash
JIANYU_PORT=8080 JIANYU_STATIC_ROOT=. node apps/jianyu-web-nas/server/server.mjs
```

`JIANYU_STATIC_ROOT` 指向仓库根目录，这样 `/apps/jianyu-web-nas/web/` 和 `/packages/*/src/` 才找得到。

## 相关文档

- [ADR 0028 自托管 Web 运行时](../docs/adr/0028-self-hosted-web-runtime-for-family-nas.md)
- [ADR 0029 NAS 服务器信任边界](../docs/adr/0029-nas-ciphertext-only-store-and-transient-proxy.md)
- [ADR 0030 家庭状态格式与兼容立场](../docs/adr/0030-web-family-state-and-recovery-bundle-v1.md)
- [docs/UI-SYSTEM.md](../docs/UI-SYSTEM.md) — 网页版沿用的页面层级、布局令牌、语义色调和家庭用语
- [SECURITY.md](../SECURITY.md) §10 — 上线前必须过的门禁
- [PRIVACY.md](../PRIVACY.md) §8 — 托管方只收客户端加密对象
