# EasyAuth 多认证源：Fabric 1.20.4

本分支提供纯 Fabric 服务端的 Mojang、MUA Union 和多皮肤站 Yggdrasil 会话认证。无需 Velocity/BungeeCord，也不需要服务端 authlib-injector agent。客户端使用 Union/皮肤站时，仍需支持外置登录的启动器完成对应站点的 `join`。

运行环境：Minecraft **1.20.4**、Java **17 或更高**、Fabric Loader **0.18.6 或更高**及匹配 1.20.4 的 Fabric API。一个 jar 同时包含原 EasyAuth companion；服务端多源登录不要求安装 companion。

## 启用

1. 将发布的 `easyauth-fabric-mc1.20.4-4.0.0-multisource.6.jar` 放入服务器 `mods`，替换旧 EasyAuth jar，勿同时保留两份。
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

## 按登录渠道设置名字前缀

`4.0.0-multisource.3` 修正了上一版前缀仅用于显示、不能隔离同名账号的问题：**UNION 和 SKIN_SITE 的 `namePrefix` 会在首次创建身份时加到真实本地账号名上，再进行名称占用检查**。例如官方账号为 `LXW1019`，Union 配置 `"namePrefix":"[MUA] "` 后，新 MUA 账号为 `[MUA]LXW1019`，两者分别保存身份、权限和存档，不合并。MUA 的 hasJoined 和 mapped 查询仍使用提供方原始名字与 UUID。

| 配置位置 | 示例 | 使用渠道 |
| --- | --- | --- |
| OFFICIAL 源的 `namePrefix` | `"[正版] "` | 官方认证，仅聊天／Tab 显示 |
| UNION 源的 `namePrefix` | `"[MUA] "` | 首次生成 `[MUA]原名` 本地身份 |
| 各 SKIN_SITE 源的 `namePrefix` | `"[S1] "` | 首次生成 `[S1]原名`，可逐站设置 |
| 根级 `offlineNamePrefix` | `"[离线] "` | EasyAuth 离线／密码登录，仅显示 |
| 根级 `floodgateNamePrefix` | `"[基岩] "` | Floodgate 玩家，仅显示 |

所有前缀默认 `""`。UNION／SKIN_SITE 的前缀去除首尾空白后，必须为不含空格的可打印 ASCII 字符；方括号有效，例如 `[MUA]`。前缀加原名最多 **16 个字符**，超长会明确拒绝，不会截断。建议选用简短的方括号前缀，以区别于普通正版名字。不同外置源的非空前缀不能大小写重复或互为开头；停用的配置条目也保留其名字空间。官方、离线和 Floodgate 的显示前缀仍支持最多 64 个 Unicode 字符，允许中文及空格，不接受控制字符。

修改后执行 `/auth sources reload`。**真实本地名字和创建时使用的前缀一经保存就保持稳定**：修改前缀影响以后首次创建的身份，不会重命名已有账号。已有未带真实前缀的旧版外置档案继续保留原名，并沿用聊天／Tab 显示前缀；此版本不会自动迁移它们。因旧正版名字冲突而未成功创建的 MUA 身份，会在升级后使用新前缀创建。请保留来源 `id`，不要删除身份账本来重置。

对于新建带前缀的外置身份，头顶名牌、命令目标和服务器账号记录都会使用完整本地名字，例如 `[MUA]LXW1019`；聊天／Tab 不会重复添加前缀。UUID 策略保持不变，也不复制正版账号的密码或迁移其 playerdata。已有显示前缀功能保留原组件和队伍样式，不占用 scoreboard 队伍；队伍变化会同步 Tab。只修改官方／离线／Floodgate 的显示前缀仍不会改变认证模式。

前缀不能代替身份验证：离线客户端不能通过填写 `[MUA]名字` 冒充 Union，其他源也不能占用已保留的来源名字空间。已有真实 UUID 冲突和官方 UUID 冒用仍然拒绝；此修复只让不同来源的同名账号在配置不同前缀后各自拥有独立名字。

## 带前缀账号的传送与命令

原版命令解析器不能将未加引号的 `[MUA]LXW1019` 作为普通名字读取。`.4` 在服务端为**身份账本中已经登记的外置账号**补充了实体目标参数兼容，因此原版 `/tp LXW1019 [MUA]LXW1019` 可以解析；未登记的非法名字、普通正版名字、UUID 和选择器继续采用原版规则。该兼容不会改写账号名字、UUID、存档或权限。

推荐使用完整原版语法 `/tp @s "[MUA]LXW1019"`，也适用于 `.3`。原版客户端的本地语法检查没有服务器的补丁，不加引号时仍可能标红。选择器内也需引号，例如 `@a[name="[MUA]LXW1019"]`。第三方 `/tpa` 等命令若使用自己的字符串参数解析器，不在此兼容补丁范围内，需依该模组支持的语法处理。

`.6` 会在原版实体目标参数的 Tab 补全中自动加入必要的引号：输入 `/tp [M` 后按 Tab，会填入 `/tp "[MUA]LXW1019"`。空参数和已经输入开引号的情况也支持。更新服务器并重新进服后生效；手动输入不加引号的完整特殊名字仍可能标红，服务器没有替换客户端的解析器。已有第三方自定义补全器保持原样。

为使原版客户端取得正确候选，此版本让没有自定义补全器的实体参数向服务器请求补全。代价是多一次补全网络请求，且不再自动提供客户端准星指向实体的 UUID 候选；手动输入 UUID 仍有效。服务器只调整原版提供的在线名字候选，不从身份账本枚举离线账号。第三方隐身模组的可见性过滤仍需单独验证。

攻击和击退仍由原版实体逻辑处理。用户在 `.5-diagnostic` 上的复测确认攻击正常，服务器记录也显示伤害、免伤冷却和目标 tick 正常；这不能确定此前间歇异常的唯一原因。`.6` 保留相关回归与默认关闭的诊断开关。

为继续定位这一问题，另提供 `.5-diagnostic` 诊断版。它默认关闭额外日志，通过 JVM 参数 `-Deasyauth.debugCombat=true` 观察攻击包和玩家伤害结果，最多 1000 条记录，不改变伤害或认证规则。详见[诊断版使用方法](COMBAT_DIAGNOSTICS.zh-CN.md)。

## 1.20.4 登录路由的必要选择

部分皮肤站用 `OfflinePlayer:<name>` 的 UUIDv3，与原版同名离线客户端相同。在客户端回答加密握手之前，服务端无法区分它们；原版离线客户端收到在线握手时又可能因无法 `join` 而直接退出，服务器此时无法替它回退密码。

`newPlayerAuth` 控制**新名字**的路由：

| 配置 | 行为 |
| --- | --- |
| `AUTO`（默认） | UUID 与原版离线 UUID 相同的新玩家走密码路径；其他 UUID 触发认证。保留原离线客户端体验。 |
| `EXTERNAL`（Union 示例） | 新玩家优先完成外置/官方握手，包括具有离线形状 UUIDv3 的 Union 用户。 |
| `offlineNames` | 正则表达式列表，完整匹配名字；让指定新离线名字直接走密码路径。示例为 `Offline_` 前缀，管理员可改为确切名字，如 `^Alice$`。 |

已登记的离线密码账号使用原离线 UUID 时继续走密码路径；启用外置源后，同名客户端提供不同 UUID 时可进入握手验证。已登记的官方/外置账号必须由其认证源证明身份，不能利用 `offlineNames`、停用源或源故障退回密码冒用。客户端声明的 UUID **只决定是否发起握手，不是认证证据**。如果某个已有离线账号与外置账号连客户端 UUID 都相同，服务端仍无法自动区分，不能仅靠前缀消除这个协议限制。

因此，EXTERNAL 模式下首次 Union 用户可直接认证进服并自动创建本地 profile，无需注册自有皮肤站；新纯离线用户需匹配 `offlineNames` 或使用 AUTO 模式。这是协议无法自动区分时的显式配置，不是 UUID 迁移。

## 身份与数据

| 来源 | 稳定身份键 | 游戏内 UUID |
| --- | --- | --- |
| 官方 | 官方账号 UUID | Mojang 原始 UUID |
| EasyAuth 离线 | 已有有效离线身份 | 原 UUIDv3；保留已有明确设置的有效 forced UUID，不自动改写 |
| MUA Union | `(source.id, internal_id)` | 首次生成并持久化本地 UUIDv7 |
| 皮肤站 | `(source.id, provider UUID)`，或配置的稳定 subject | 原生 UUIDv7；非 v7 首次映射为 v7 并持久化 |

新增 `config/EasyAuth/identities.sqlite`，实际包含 `profile` 和 `source_identity` 表；名字、大小写归一名字、UUID、来源与 subject 组合都有唯一约束。版本 .3 将账本升级到 schema 2，新增创建时的 `name_prefix` 元数据，旧档案的名字和 UUID 不变；回退旧版 jar 需要同时恢复升级前的账本备份。原 SQLite/MySQL/PostgreSQL/MongoDB 密码数据库继续由 EasyAuth 使用，此身份表是**单服务器本地账本**，不是跨服务器共享身份服务。

Union `remapuuid` 只更新 provider UUID/name；`internal_id` 相同时，本地 UUID和游戏内名字保持不变。皮肤站已有身份突然返回不同原生 v7 会被拒绝，避免悄悄切换世界数据。

新外置身份先应用本地前缀，再检查是否与旧账号、其他源或已有 UUID 世界数据冲突；最终本地名字仍冲突时拒绝，不自动合并。已知官方 UUID 被第三方返回时拒绝；其他非 v7 第三方 UUID始终隔离到本地 v7，记录 source、subject、provider/local UUID 审计日志。不会为了认证外置源而额外依赖 Mojang 的全局 UUID 查询。

本版本**不提供跨源绑定/合并命令**。新身份可用不同来源前缀隔离上游同名账号；最终本地名字仍被占用时需另选未占用的前缀／名字。不要通过修改数据库、`setUuid` 或复制 playerdata 绕过冲突。原修改 UUID、改名、删除、标记正版等命令不能改动已纳入身份账本的身份，以免留下与账本不一致的记录；旧默认模式下未纳入账本的账号仍沿用原命令。若要支持绑定，应另行设计身份所有权验证、冲突处理和审计流程。

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

`textures` 的 value/signature 原样复制到最终 GameProfile，不改写签名内容。UUID/name 映射不会把 MUA 的纹理签名变成 Mojang 签名，也不会给客户端增加皮肤域名白名单。纯服务端认证成功不等于普通正版客户端能够显示外置皮肤，不能据此承诺与原版所有行为完全一致。

2026-10-27 对本次问题涉及的 MUA 公开 profile 进行只读查询，确认其返回了带签名的 `textures`，`SKIN` 地址位于 `skin.mualliance.ltd`。Minecraft 1.20.4 使用的 authlib 6.0.52 默认仅允许 `.minecraft.net`、`.mojang.com` 下的纹理域名；客户端显示其他玩家皮肤还要求签名受其信任。因此，原样转发这份 MUA 纹理不能让未配置外置皮肤支持的正版客户端显示它。此域名限制已由原版字节码和[authlib-injector 上游规范](https://yushijinhun.github.io/authlib-injector/en/yggdrasil-server-technical-specification.html#texture-domain-whitelist)核对。用户随后提供的正版客户端 `latest.log:1044` 在 19:24:57 明确记录了 `Textures payload contains blocked domain`，确认此次确实触发 MUA 域名拒绝；未发现可确认的签名失败记录。详见[客户端日志核对](CLIENT_LOG_FINDINGS.zh-CN.md)。

要让双方互相显示皮肤，可在双方客户端安装并配置能信任 MUA 纹理公钥、允许其皮肤域名且兼容 Mojang 皮肤的外置皮肤支持。仅让 MUA 玩家通过支持外置登录的启动器进服，不会改变正版玩家客户端的信任配置。另一种方案是使用合法取得的 Mojang 签名纹理及其允许的纹理地址进行桥接；本分支尚未实现该方案。删除签名、改写签名内的 UUID/name 或修改 `relaxCustomChatKeys` 都不能解决这些皮肤信任限制，无需为此改变账号认证或持久化 UUID。

本次另提供[客户端皮肤支持包及安装说明](EXTERNAL_SKINS.zh-CN.md)，使用官方 CustomSkinLoader Universal 15.1 和只包含 `GameProfile` 的加载配置，直接采用服务器转发的纹理。它不是为 MUA 生成 Mojang 签名，也不改登录认证。需要安装到每个观看端客户端；已验证下载摘要和对应实现，用户整合包中的实际显示仍需重启后验收。

当前自动化验证与仍需完成的皮肤验收应区分：

- 现有 GameTest 使用 `fixture-texture` / `fixture-signature`，检查属性被保留；这些值不是真实可渲染纹理，测试通过不代表客户端能下载、验证或显示皮肤。
- 后续自动化回归应检查映射前后的纹理 value/signature 完全一致，并检查实际发送的玩家信息包保留这些属性；不要在测试产物中保存真实账号的原始签名。
- 图形验收需分别用未配置外置皮肤支持的正版客户端，以及正确配置信任的双方客户端进行对照，检查双方视角、TAB 头像、皮肤模型、重新登录和重启后的显示，并记录客户端的域名、签名或下载错误。上述纯服务端测试没有完成这项验收。

用户日志已证明本次真实官方账号与 MUA 账号均通过认证。自有皮肤站完整登录流程、双方客户端皮肤渲染及其他模组兼容性仍需实际验收，不能由 HTTP fixtures、加密 challenge、身份数据库和 GameTests 的结果推断全部通过。

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
