# 1.20.4 服务端皮肤桥接

`4.0.0-multisource.7` 的 Fabric 1.20.4 构建可在入服前处理皮肤，让原版客户端显示已转换的 MUA／皮肤站皮肤，无需安装客户端皮肤模组。账号认证、名字隔离、UUID、权限和玩家存档仍按原有多源认证规则处理；本功能只替换皮肤属性。

| 已验证的账号来源 | 皮肤处理 |
| --- | --- |
| `OFFICIAL` | 保留 Mojang 返回的原始皮肤属性，不进入 MineSkin 转换队列；缺少属性时仅从固定 Mojang 资料接口补查。 |
| `UNION`，包括 MUA | 已有可验证的 Mojang 签名纹理直接使用；其他标准皮肤提取图片 URL 和模型后通过 MineSkin 转换。 |
| `SKIN_SITE` | 与 `UNION` 使用相同的转换流程，各来源的账号身份继续独立。 |

## 配置密钥并启用

1. 在 [MineSkin 官方密钥页面](https://account.mineskin.org/keys/)创建服务器使用的 API key；流程参见[官方入门文档](https://docs.mineskin.org/docs/guides/getting-started/)。
2. 任选一种方式保存密钥：在启动 Java 服务器的进程环境中设置 `EASYAUTH_MINESKIN_API_KEY`，或填写 `config/EasyAuth/mineskin.key`，文件只放密钥本身的一行文本，不加引号或 `Bearer ` 前缀。非空环境变量优先于文件。密钥只保存在服务器端，不要发送到聊天或提交到代码仓库。[官方密钥使用建议](https://docs.mineskin.org/docs/guides/api-best-practises/)
3. 首次启动会在 `config/EasyAuth/skin-bridge.json` 写入默认配置；也可复制 [skin-bridge.example.json](skin-bridge.example.json) 到该位置。默认 `enabled` 为 `true`。从 `.8` 起，每次成功加载皮肤配置时都会补建缺失的空 `mineskin.key`，包括启动和 `/auth skins reload`；已有文件不会被覆盖，环境变量密钥也不会写入该文件。空文件仍需填写真实 API key。目录不可写时会记录提示，环境变量方式仍可使用。
4. 修改配置或密钥文件后，由管理员执行 `/auth skins reload`，或重启服务器。修改操作系统环境变量后，应重启 Java 进程，使其取得新的环境。`/auth skins reload` 会保留已有的持久缓存，但会结束旧桥接实例的后台任务；在线玩家的皮肤在下次登录时应用。

启动日志或重载结果中的 `MineSkin key=configured` 表示已读取密钥，不等于密钥已通过远端验证。`MineSkin key=missing (cache only)` 表示未配置密钥：已有缓存仍可使用，不能生成新的转换结果。若没有可用缓存，第三方皮肤在原版客户端上仍可能显示默认皮肤；账号可以照常完成认证。

密钥不放在 `skin-bridge.json` 或 `sources.json` 中。配置格式错误会拒绝该次皮肤配置重载并保留旧配置；启动时皮肤配置不可用也不会绕过或关闭账号认证。

## 配置字段

下表对应当前实现。未知字段会被拒绝；整数必须在列出的范围内。

| 字段 | 默认值 | 范围／用途 |
| --- | --- | --- |
| `configVersion` | `1` | 当前只支持 `1`。 |
| `enabled` | `true` | `true`／`false`；关闭后跳过桥接。 |
| `loginWaitMillis` | `8000` | `0`–`30000` 毫秒；一次登录最多等待皮肤的时间。设为 `0` 不等待，后台结果可供之后登录使用。 |
| `requestTimeoutMillis` | `5000` | `1`–`60000` 毫秒；单次 HTTP 请求的时间预算。 |
| `generationTimeoutMillis` | `60000` | `1`–`300000` 毫秒；后台一次 MineSkin 生成流程的预算，包括提交、轮询和限流等待。 |
| `refreshAfterHours` | `24` | `1`–`8760` 小时；匹配缓存达到该年龄后可触发后台刷新。 |
| `maxQueuedSkins` | `64` | `1`–`1024`；图片生成使用独立单工作线程，此值限制等待队列长度。 |
| `failureRetrySeconds` | `60` | `1`–`86400` 秒；相同图片／模型生成失败后的重试退避。 |

额外皮肤等待最多使用“本次认证开始后 25 秒”之前的剩余时间：实际等待取 `loginWaitMillis` 与该剩余时间中较小的值。认证已耗尽这段时间时，皮肤阶段不再延长等待；这不把原有认证流程强制截断为 25 秒，认证源自己的请求超时仍由 `sources.json` 控制。通常带有皮肤属性的正版登录直接通过，不等待外置皮肤生成。同时等待皮肤的登录最多为 `maxQueuedSkins + 2` 个，超出后直接用原皮肤继续认证，不无限堆积等待对象。

## 认证源缺少皮肤属性时

优先使用本次认证成功响应中的 `textures`。只有缺少这个属性时，才向该已验证来源的资料接口补查；补查使用**提供方 UUID**，不会使用本服隔离后的 UUIDv7，也不会根据相同名字查找另一来源的账号。

当来源的 `hasJoinedUrl` 以 `/session/minecraft/hasJoined` 结尾时，会自动推导同一路径下的 `profile/{uuid}?unsigned=false`。普通 Yggdrasil `apiRoot` 配置通常已符合这个形式。自定义登录端点不能自动推导时，可在 `sources.json` 对应来源中填写资料接口模板，例如：

```json
"profileUrl": "https://skin.example.org/api/yggdrasil/sessionserver/session/minecraft/profile/{uuid}?unsigned=false"
```

这里的域名是占位示例，应替换为该皮肤站实际提供的接口。`{uuid}` 会被替换成不带连字符的提供方 UUID。响应中的 `id` 必须与已经验证的提供方 UUID 一致；不一致或响应格式错误只导致皮肤补查失败，不改变已验证账号。`OFFICIAL` 只能使用固定的 Mojang 资料接口。

修改来源的 `profileUrl` 后使用 `/auth sources reload`；`/auth skins reload` 专门重载桥接配置与密钥。

## 缓存、超时和换肤

相同图片 URL 和模型会合并生成请求。匹配的新鲜缓存可直接复用；缓存过期后仍可使用已验证的旧纹理，并在后台刷新。来源换成另一张图片时，转换失败也会保留该身份之前的有效皮肤。

第一次生成超过默认 8 秒等待时间时，玩家先用已有有效缓存或原属性完成登录。后台生成可继续到自己的时间上限，成功结果只更新缓存，**不会突然重生、传送或修改已在线玩家**。生成完成后重新连接即可应用；在皮肤站换肤后的更新也于后续登录处理。同一 URL 内容变化时，还需等待缓存刷新周期；过期缓存触发的后台更新通常需要再登录一次才能看到。

缓存位于 `config/EasyAuth/skin-cache.json`，采用原子替换，文件最大 8 MiB。纹理记录和身份关联分别最多 10000 项，超限会淘汰旧缓存及其关联。身份索引使用散列键，不保存 API key 或单独的玩家名／UUID 字段；签名纹理载荷原样保存。缓存读取有大小和结构限制，实际使用前还会验证签名、纹理域名和模型。无效缓存不会作为可信皮肤发给客户端。

MineSkin 不可用、密钥错误、生成队列满或皮肤补查失败只影响外观，不会把外置账号切换为正版、离线或另一个账号。相同图片失败后默认退避 60 秒；遇到 HTTP 429 会遵守服务端的限流等待，不会为了赶上登录期限而忽略限流。[MineSkin 限流说明](https://docs.mineskin.org/docs/guides/rate-limits/)

## 数据发送与支持边界

转换时，图片 URL 和 `classic`／`slim` 模型会发送给 MineSkin；请求不附加玩家账号名，使用 `visibility=unlisted`。`unlisted` 表示不公开列入列表，**不等于 `private`**，不能当作私密图片存储。[MineSkin 可见性说明](https://docs.mineskin.org/blog/mineskin-v2/)

图片必须是 MineSkin 能直接访问的标准皮肤图片。需要登录、内网访问或特殊客户端处理的图片，以及自定义第三方披风，不保证能在原版客户端显示。最终发给客户端的转换纹理必须使用 Mojang 可验证的签名和 `textures.minecraft.net` 图片；不会伪造签名或关闭原版客户端校验。

当前测试使用本地 HTTP、签名密钥和登录数据夹具验证请求流程、缓存、超时隔离及 Minecraft 数据包。尚未提供真实 MineSkin API key，因此**真实 MineSkin 生成加双客户端图形显示尚未验收**；启动出现 `configured` 或自动化测试通过不能代替这一步。配置密钥后，可用一个 MUA 账号和一个正版原版客户端重新登录，分别观察自身、对方和 Tab 中的皮肤。
