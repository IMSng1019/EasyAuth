# EasyAuth 多认证源：Fabric 1.20.4

本分支提供纯 Fabric 服务端的 Mojang、MUA Union 和多皮肤站 Yggdrasil 会话认证。无需 Velocity/BungeeCord，也不需要服务端 authlib-injector agent。客户端使用 Union/皮肤站时，仍需支持外置登录的启动器完成对应站点的 `join`。

运行环境：Minecraft **1.20.4**、Java **17 或更高**、Fabric Loader **0.18.6 或更高**及匹配 1.20.4 的 Fabric API。一个 jar 同时包含原 EasyAuth companion；服务端多源登录不要求安装 companion。

## 启用

1. 将发布的 `easyauth-fabric-mc1.20.4-4.0.0-multisource.1.jar` 放入服务器 `mods`，替换旧 EasyAuth jar，勿同时保留两份。
2. 初次启动会生成 `config/EasyAuth/sources.json`。默认只配置官方源，走原 EasyAuth 登录行为，Union/皮肤站不会自动开启。
3. 启用多源前，停服备份世界和原认证数据库。修改 `server.properties`：

   ```properties
   online-mode=true
   enforce-secure-profile=false
   ```

4. 将 [sources.union-example.json](sources.union-example.json) 的内容复制到 `config/EasyAuth/sources.json`。该示例启用官方与 MUA Union；自有皮肤站条目是关闭的占位项，修改地址后才能启用。多家皮肤站分别使用不同 `id` 和 `priority`。
5. 确保 `extended.conf` 的 `forced-offline-uuid=false`，`proxy.conf` 的 `enabled=false`。与 raw UUID 或直接握手冲突的配置会明确报错，不会悄悄改变玩家 UUID。
6. 执行 `/auth sources reload`。该命令只验证并原子替换认证源配置，错误配置保留旧快照；正在认证的连接继续使用它开始时的快照。修改服务器自身 properties 后需重启。

仅 1.20.4 构建启用新认证链路；其他版本若尝试启用会明确失败，默认旧行为仍可构建。默认配置保留上游的正版自动登录、密码、会话、2FA、存储后端、Floodgate、LuckPerms 和 companion 机制。

## 1.20.4 登录路由的必要选择

部分皮肤站用 `OfflinePlayer:<name>` 的 UUIDv3，与原版同名离线客户端相同。在客户端回答加密握手之前，服务端无法区分它们；原版离线客户端收到在线握手时又可能因无法 `join` 而直接退出，服务器此时无法替它回退密码。

`newPlayerAuth` 控制**新名字**的路由：

| 配置 | 行为 |
| --- | --- |
| `AUTO`（默认） | UUID 与原版离线 UUID 相同的新玩家走密码路径；其他 UUID 触发认证。保留原离线客户端体验。 |
| `EXTERNAL`（Union 示例） | 新玩家优先完成外置/官方握手，包括具有离线形状 UUIDv3 的 Union 用户。 |
| `offlineNames` | 正则表达式列表，完整匹配名字；让指定新离线名字直接走密码路径。示例为 `Offline_` 前缀，管理员可改为确切名字，如 `^Alice$`。 |

已登记的离线密码账号继续使用其原路径；已登记的官方/外置账号必须由其认证源证明身份，不能利用 `offlineNames`、停用源或源故障退回密码冒用。客户端声明的 UUID **只决定是否发起握手，不是认证证据**。

因此，EXTERNAL 模式下首次 Union 用户可直接认证进服并自动创建本地 profile，无需注册自有皮肤站；新纯离线用户需匹配 `offlineNames` 或使用 AUTO 模式。这是协议无法自动区分时的显式配置，不是 UUID 迁移。

## 身份与数据

| 来源 | 稳定身份键 | 游戏内 UUID |
| --- | --- | --- |
| 官方 | 官方账号 UUID | Mojang 原始 UUID |
| EasyAuth 离线 | 已有有效离线身份 | 原 UUIDv3；保留已有明确设置的有效 forced UUID，不自动改写 |
| MUA Union | `(source.id, internal_id)` | 首次生成并持久化本地 UUIDv7 |
| 皮肤站 | `(source.id, provider UUID)`，或配置的稳定 subject | 原生 UUIDv7；非 v7 首次映射为 v7 并持久化 |

新增 `config/EasyAuth/identities.sqlite`，实际包含 `profile` 和 `source_identity` 表；名字、大小写归一名字、UUID、来源与 subject 组合都有唯一约束。原 SQLite/MySQL/PostgreSQL/MongoDB 密码数据库继续由 EasyAuth 使用，此身份表是**单服务器本地账本**，不是跨服务器共享身份服务。

Union `remapuuid` 只更新 provider UUID/name；`internal_id` 相同时，本地 UUID和游戏内名字保持不变。皮肤站已有身份突然返回不同原生 v7 会被拒绝，避免悄悄切换世界数据。

新外置身份与旧账号、其他源或已有 UUID 世界数据冲突时拒绝，不按同名、同 UUID 自动合并。已知官方 UUID 被第三方返回时拒绝；其他非 v7 第三方 UUID始终隔离到本地 v7，记录 source、subject、provider/local UUID 审计日志。不会为了认证外置源而额外依赖 Mojang 的全局 UUID 查询。

本版本**不提供跨源绑定/合并命令**。重名必须先在提供方选择未占用的名字；不要通过修改数据库、`setUuid` 或复制 playerdata 绕过冲突。原修改 UUID、改名、删除、标记正版等命令不能改动已纳入身份账本的身份，以免留下与账本不一致的记录；旧默认模式下未纳入账本的账号仍沿用原命令。若要支持绑定，应另行设计身份所有权验证、冲突处理和审计流程。

多源启用后，或账本仍有外置身份时，上游隐式离线→在线 playerdata 迁移停止。已有密码记录与世界数据不自动迁移。官方与旧离线账号同名但 UUID 不同，也会拒绝自动接管旧账号。

`id` 是持久化身份命名空间；不能将同一个 id 改作无关皮肤站。不要删除账本来“重置配置”，否则会丢失稳定映射。停服后，将 `identities.sqlite`（若存在，还包括 `-wal` / `-shm`）、原认证数据库与世界一起备份、恢复；现有 EasyAuth 密码数据库备份不包含这个新账本。恢复后再启动。

## 配置字段

- `configVersion`：目前必须为 `1`。
- `parallelism`：单次登录同时请求源数，1–16；官方与自定义请求使用分开的有界执行器。1 可串行请求。
- `overallTimeoutMillis`：整次查询预算，100–30000ms，包括映射与重试。失败的 Union 不无限阻塞本地登录；官方成功不等待较低优先级源。
- 登录协调任务另有全局上限：8 个密码任务、64 个提供方任务，均不无限排队；超出容量会明确提示稍后重连。HTTP 层为官方保留 16 个工作线程，自定义源最多 48 个。
- `sources[].id/type/enabled/priority`：唯一来源标识，类型 `OFFICIAL/UNION/SKIN_SITE`；priority 越小越优先。重复 id/priority 拒绝；官方必须是启用源中的最高优先级。
- `apiRoot` 或 `hasJoinedUrl`：自定义源二选一。`apiRoot` 自动追加 `/sessionserver/session/minecraft/hasJoined`。官方地址固定为 Mojang，不能把自定义 URL 标记为官方。
- `timeoutMillis/retries`：单次请求 100–10000ms，重试 0–3。超时、429、5xx 可重试；204/404 等无认证结果可继续下个源。已收到成功响应但格式错误、读取不完整或映射不一致会拒绝，不降级密码。
- `mappedUrl`：必须含 `{uuid}`；占位符替换为此次成功 `hasJoined` 返回的无横线 UUID。映射响应必须带同值 `uuid`，绝不能用未经认证的输入名字确定 subject。
- Union 必填 `mappedUrl`，`subjectIdField` 固定 `internal_id`；`unionMode` 可为 `ALL/ONLY/EXCLUDES`，后两项须配 `unionServers`，如 `["SJMC","FDC","MUA"]`。
- 皮肤站可配 `subjectIdField`（如 `user.id`）和 `createdAtField`（非负 Unix 时间戳字段），组合稳定 ID 与创建时间，避免站点重用 ID。标准 Yggdrasil 本身不提供这些字段，需要站点有对应的只读映射 API。没有该 API 时，以 provider UUID 为 subject；站点改变 UUID 后会被视为另一身份，冲突时拒绝。
- `relaxCustomChatKeys`：默认 false；Union 示例 true。仅对本次连接已经认证成功的外置玩家跳过不兼容的聊天 key 更新；官方玩家保持原验证。UUID 重映射会使站点原聊天证书与本地 UUID 不匹配，所以实际部署 Union 时通常需开启。它不让客户端信任第三方皮肤公钥。

配置是严格 JSON：未知字段、错误类型、非法 URL、重复标识/优先级或缺失必填项都会报错。URL 不接受账号密码、query 或 fragment。

## 皮肤与认证范围

`textures` 的 value/signature 原样复制到最终 GameProfile，不改写签名内容。原版客户端对第三方签名、公钥和皮肤域名仍有自身限制；纯服务端方案无法保证所有客户端彼此显示外置皮肤，也不代替客户端外置登录支持。

没有提供自有皮肤站实际 API/账号，因此交付验证包括本地 HTTP fixtures、真实 Minecraft 登录方法与加密 challenge、身份数据库和 GameTests；不等于已完成真实 MUA/自有站点账号登录验收。

## 构建与验证

上游构建工具使用 Java 25；1.20.4 产物字节码及测试运行目标为 Java 17。构建机需安装二者：

```powershell
.\gradlew.bat '-PeasyauthTarget=1.20.3-fabric' :1.20.3-fabric:build :1.20.3-fabric:runGameTest :1.20.3-fabric:collectJars --configure-on-demand
```

节点名沿用上游 `1.20.3-fabric`，其 `minecraft_version` 实际为 1.20.4。产物收集到 `build/libs`；不要安装 `-sources.jar` 或 `-all.jar`。

不启动 Minecraft 的独立回归：

```powershell
.\gradlew.bat -p tools/source-tests test
```

协议参考：[MUA 联合认证](https://docs.mualliance.cn/zh/dev/union/auth)、[Yggdrasil 技术规范](https://yushijinhun.github.io/authlib-injector/en/yggdrasil-server-technical-specification.html)。
