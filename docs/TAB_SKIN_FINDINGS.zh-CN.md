# MUA／离线 TAB、头像和前缀排查（2026-11-05）

排查基线为 `cb3e2d2`，Fabric Minecraft 1.20.4，EasyAuth `4.0.0-multisource.12`。最终定位到现场 Exordium 1.4.1 的 TAB 缓存失效判断缺陷。按用户要求，EasyAuth 不承担第三方缓存的绕过处理：已撤销显式 TAB 名称及相关队伍刷新补丁，`.13` 实验包已撤回。当前只保留 `.12-tab-diagnostic` 的可选诊断工具和取证记录，TAB 行为保持原有逻辑。未迁移身份账本，也未自动替换实际服务器文件。

## 最终根因与处理边界

用户补充“明显与加入顺序有关”后，核对 Exordium 的 `PlayerListComponent.fastGetPlayerInfoListHashCode` 发现：当玩家 `tabListDisplayName == null` 且 `team == null`，该方法直接跳过玩家，没有把 UUID、名字或任何指纹加入结果。于是此类玩家从 1 人增加到 2、3 人，缓存指纹仍为 `[]`，已绘制的旧名单不会因新增玩家刷新。先打开 TAB 与后进入的客户端会显示不同人数；跨连接复用的缓存也可能保留旧名单。

原版 null TAB 显示名合法，客户端会回退显示真实名字；原版没有 Exordium 的这层缓存，因此不会触发这个特定缺陷。MUA／离线真实前缀账号把前缀放在 GameProfile 名字中，过去无需额外显示组件，刚好满足缺陷条件。没有队伍和自定义显示名的普通正版账号同样可能受影响，不是 UUIDv7 或身份验证失败。

独立无 GUI 程序加载了现场 Exordium 原始 JAR 中的真实 `PlayerListComponent`，而非重写其算法：null 显示名、无队伍时，1／2／3 人的 `hashes=[]` 且 `hasChanged=false`；改为明确显示名后，指纹长度递增且 `hasChanged=true`。Minecraft 类型使用最小 stub，未验证最终像素；输出位于 `build/exordium-repro/result.txt`。用户随后确认关闭／移除该模组后问题消失。

曾验证过服务端发送显式 TAB 名称的绕过方案，但问题属于 Exordium 的缓存判断。用户明确要求不在 EasyAuth 中修复此第三方问题，故该方案及其队伍刷新扩展均已撤销，原有 null TAB 显示名和队伍处理保持不变。客户端应关闭受影响版本的 TAB 缓存、移除 Exordium，或由 Exordium 修复遗漏玩家的缓存指纹计算。

实验绕过方案也存在客户端专属虚拟队伍装饰的兼容边界；撤销后不引入这一行为变化。

实验包曾通过六种加入顺序及相关兼容回归，证据保留在 `build/tab-order-cache-red.log`、`build/tab-order-fix-final.log`，仅用于解释根因，不作为交付版本。已移除要求 EasyAuth 绕过 Exordium 的专用用例，并恢复原有 null TAB 断言；正常协议、实际入服、诊断记录测试继续保留。

## TAB 名单缺失：基础环境尚未复现

新增 `NameplateRuntimeTest.playerListsReachEveryAuthenticationChannel`，通过实际服务端发送钩子序列化、解码 player-info 包，分别检查正版、MUA 和离线接收方：

- 三方均收到完整 `ADD_PLAYER` / `UPDATE_LISTED` 数据，带前缀名字及纹理属性完整保留。
- 离线玩家尚未密码认证时仍收到名单。
- 显示刷新不改变名单；MUA 玩家移除、重新加入后名单更新正常。

测试接收器现在独立记录 TAB 的 listed 集合，避免仅验证显示文字而漏掉“客户端持有档案但不列入 TAB”的情况。纹理使用测试属性，只验证传递完整性，不验证客户端图形显示。

运行 `:1.20.3-fabric:runGameTest --configure-on-demand --offline`，20 项必需 GameTests 全部通过，记录见 `build/tab-roster-verification.log`。这不覆盖真实网络入服的完整过程、客户端模组或实际服务器上的 Vanish／TAB 等第三方组合，不能据此否定用户遇到的问题。

本地 Minecraft 1.20.4 `PlayerList.placeNewPlayer` 字节码确认：新玩家先收到现有玩家的完整初始化包，随后服务器广播新玩家的完整初始化包；此流程没有按 MUA／正版区分。不能仅凭皮肤失败推断名字会从 TAB 中消失，也不应未经定位就强行重新广播所有玩家而暴露隐身玩家。

## 离线 TAB 无头像：原版客户端的加密判断

本地 1.20.4 `PlayerTabOverlay.render` 字节码确认，只有本地单人服务器或 `Connection.isEncrypted()` 为真时才绘制玩家头像。

EasyAuth 的直接密码登录分支跳过加密握手，测试 `OfflineLoginRuntimeTest` 也明确验证没有发送 challenge。因此，即使服务器已经提供纹理，原版客户端在该连接上仍不画 TAB 头像。这与“名单中的名字缺失”不同。

1.20.4 的在线握手会触发客户端 session join；不能为离线账号简单强制启用正版握手，否则会阻止本来能够密码登录的离线客户端。若要在现有直接离线路径显示头像，需要客户端兼容支持；服务端纹理桥接本身无法改变这个绘制条件。

## BlueMap：默认按 Mojang UUID 重新查询皮肤

只读核对了 BlueMap 官方仓库当时的 master：

- [PlayerSkinUpdater](https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/src/main/java/de/bluecolored/bluemap/common/plugin/skins/PlayerSkinUpdater.java) 默认安装 `MojangSkinProvider`，玩家加入时向该 provider 传递服务器玩家 UUID。
- [MojangSkinProvider](https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/src/main/java/de/bluecolored/bluemap/common/plugin/skins/MojangSkinProvider.java) 使用该 UUID 请求 Mojang session profile，并从返回的纹理下载图片。

MUA 本地 UUIDv7 和普通离线 UUID 不对应 Mojang 档案。即使 EasyAuth 的 GameProfile 已携带有效皮肤，这种重新查询方式也无法找到对应皮肤。因此，不能把 MineSkin 转换成功等同于 BlueMap 头像兼容。

修复方向是为实际安装的 BlueMap 版本提供皮肤接口适配，按本服 UUID 读取已有 profile 的纹理；必须保留身份 UUID，不可为了头像改成提供方 UUID。用户实际 BlueMap 版本、是否安装自定义皮肤 provider 尚未知，尚未编写或验证专用适配。纯离线账号也没有可凭空恢复的外置皮肤，仍需要明确的皮肤来源。

## MUA 前缀不随配置改变：已有身份的名字被固定

`IdentityStore.resolve` 对已有 `(sourceId, subjectId)` 返回已持久化档案，不重新计算 `namePrefix`。`SourceNameDisplay.prefix` 也对已经含真实前缀的账号返回空，避免叠加前缀。这不是 `[MUA]` 常量覆盖配置；新身份使用当前配置，旧身份保留创建时的名字。

如果要求已有身份也跟随配置，需要增加受控改名／别名方案，处理账号数据库、名字冲突、在线会话和按名字关联的第三方数据，同时保持 UUID、密码及世界数据归属。直接删除账本重建或替换显示字符串不能完整解决该需求。

## 继续定位需要的现场证据

需要实际 EasyAuth／BlueMap jar 版本，以及一次“正版和 MUA 均在线、离线玩家已经完成登录”的服务器和受影响客户端 `latest.log`。日志中的模组列表与 player-info、texture、签名、异常记录可用于确定缺失发生在服务器发送、第三方过滤还是客户端处理阶段。分享前应移除令牌、密钥和其他敏感信息；不需要提供密码、MineSkin key 或身份数据库。

## 收到客户端日志后的进一步检查

用户提供 `D:/.minecraft/logs/latest.log`（最后修改时间 2026-11-05 00:27:46）。第 837 行在 00:06:14 记录：

```text
Ignoring player info update for unknown player 01a17d18-ebd6-7c68-ba2f-bfc9554b9742 ([UPDATE_GAME_MODE])
```

随后出现 `[MUA]LXW1019` 加入消息。此条记录只能确认在处理该次游戏模式更新时尚无对应档案，不能证明后续 `ADD_PLAYER` / `UPDATE_LISTED` 缺失。日志中有时间倒序、被拼接的行，不能将全部内容视作单一客户端的连续事件流。

进一步新增 `PlayerListRuntimeTest.actualJoinsInitializeBothDirectionsForAllSources`，直接执行真正的 `PlayerList.placeNewPlayer`，由该流程消费测试连接的来源证明并初始化玩家，依次让正版、UUIDv7 MUA 和离线玩家加入。每一步均序列化、解码实际收到的玩家信息包，检查各接收方的档案名字与 listed 集合，最后走真实移除流程。21 项必需 GameTests 全部通过，记录为 `build/tab-join-red.log`（文件名是预期复现阶段的标签，实际执行成功，未复现缺失）。

只读检查了日志中的客户端模组及相关字节码：ViaFabricPlus 的名单集合替换只对 1.19.1 及更早协议启用；Exordium 使用原版列出的玩家并根据名单哈希刷新缓存；检查范围内未发现按 UUIDv7 或 `[MUA]` 名称排除玩家的逻辑。这些静态检查不能代替实际客户端渲染，也不能排除未检查的服务端模组。

尚未获得对应服务端日志、实际模组及配置，无法确认是否存在服务端名单过滤或登录后隐身状态变化。本次没有通过强制补发名单、解除隐身或修改账号 UUID 来掩盖问题；后续修复需要先核对服务端这一侧的证据。

## 收到服务端日志后的检查与诊断构建

服务端日志为 `K:/mcf/新建文件夹 (2)/mc/server/logs/latest.log`，启动于 00:07:49，结束于 00:20:11。因此客户端 00:06:14 的 unknown-player 警告来自上一次服务端运行，不能当作本次 `.12` 运行丢失名单的直接证据。

服务端确认为 Minecraft 1.20.4、EasyAuth `.12`、BlueMap 5.3，共 170 个模组。实际 EasyAuth jar 的 SHA-256 与已验证的 `.12` 构建一致。日志证实三种账号均入服，MUA 和正版在 00:11:51 之后同时在线；没有相关编码异常。未加载 Vanish；Fuji 的 `stronger_player_list`、`skin`、`afk` 配置均关闭。VMP 的 `use_async_chunks_on_login` 为 true。只读核对 MUA／正版玩家存档根级 UUID，也分别与各自登录 UUID 一致。

完整入服测试进一步改为使用真实 `Connection` 发送路径和 Netty EmbeddedChannel：加入现场 VMP 及其自带依赖后，21 项测试通过，见 `build/tab-vmp-connection.log`。随后加入现场 Carpet、GCA、Axiom、ServerCore、Krypton、PacketFixer、Areas Hint、Architectury 后，名单入服测试仍通过；4 个既有认证夹具因没有真实加密通道而与 Krypton 的 cipher 注入不兼容（`channel` 为 null），这不是 TAB 故障的复现。相关记录为 `build/tab-combined-reproduction.log`。

为获得普通日志缺少的证据，生成 `.12-tab-diagnostic`：通过 `/auth tab trace 120` 开启限时跟踪，分别记录发送入口与成功编码后的 player-info 包，可区分没有观察到初始化、`listed=false`、档案移除和正常编码。不改变原包，不强制广播、不解除隐身。观察不是客户端接收确认。

新诊断回归首先以“TAB packet tracing command is missing”失败（`build/tab-trace-red.log`），实现后覆盖实际 PacketEncoder 序列化、两个观察阶段、取消 listed、档案移除、停止以及纹理载荷不进入状态文本。最终基础构建、22 项 GameTests、单元测试和分发包检查通过，见 `build/tab-diagnostic-final.log`。实际故障仍需要在该诊断构建下复现一次，不能将这些测试通过视为原问题已修复。

## 2026-11-08 继续检查

服务端日志与已安装 jar 均未更新，仍是 11 月 5 日运行的 `.12`；客户端 latest.log 已被 11 月 7 日另一场会话覆盖，没有本次 TAB 跟踪记录。因此尚不能推进到针对现场根因的修复。

独立审查后，编码观察改为从实际输出 ByteBuf 的独立视图重新解码，不再把原 Packet 对象当作实际线上字段。新增回归先复现“原对象 listed=true、实际输出 listed=false 时诊断误报”，修正后通过；记录见 `build/tab-encoded-review-red.log` 和 `build/tab-encoded-review-green.log`。同时补充显示名 null／空白／文本分类、每连接编号及 `/eatabdebug` 客户端只读快照。

离线回归使用标准 v3 UUID。入服回归现在等待三个玩家实际加入世界，再经过三个 tick 检查名单；现场同版本 VMP 的日志确认三方异步加载完成，22 项 GameTests 全部通过，见 `build/tab-vmp-async-verification.log`。最终诊断包仍需要一次现场复现和两端记录，尚未替换真实服务器文件或启动真实服务器。

## 2026-11-08 23:11–23:17 现场重测

用户确认本次仅剩离线客户端看不到 MUA。三方已在 23:11–23:13 入服，离线最后重连是 23:13:35；服务端跟踪到 23:15:41 才开始，故 72 条观察均为周期性的 UPDATE_LATENCY，没有 ADD_PLAYER／UPDATE_LISTED／UPDATE_DISPLAY_NAME／REMOVE。其中发给 Offline_52565 的 MUA 延迟更新已进入队列并完成编码，证明该段发送路径工作，但不能证明更早的名单初始化情况。status 的 profiles/listed=0 是跟踪没见到初始化，不是服务器或客户端名单实际为空。

客户端未加载 EasyAuth，23:15:09、23:15:58、23:16:38 三次 `/eatabdebug` 都被当成未知服务器命令；没有 `[tab-client-debug]` 快照。日志仍有时间交错、行拼接与缓存锁冲突。已将诊断包补装到 `D:/.minecraft/mods`（未更改服务器 jar），客户端需重启。客户端快照另外保存为带 viewer UUID 和随机后缀的独立文件，以免重测证据被共享 latest.log 覆盖。

下一次只需保持 MUA 在线，先开启 `auth tab trace 120`，再让离线玩家重连并登录，缺人时执行本地 `/eatabdebug`，最后 `auth tab status`／`auth tab stop`。无需给离线玩家 OP 权限。

## 2026-11-08 23:42 客户端快照：名单已完整到达

本次取得三个客户端各自的独立快照。离线客户端 23:42:29 的快照（`D:/.minecraft/logs/easyauth-tab-d803c25a-b68e-32e8-8547-50f2e8cb3e02-16578358951539021754.log`）确认：

- `profiles=3 listed=3 encrypted=false`。
- MUA 档案名字及原版格式化名字均为 `[MUA]LXW1019`，`listed_object=true`、`mapped_object=true`。
- `tab_name=null` 表示正常使用 profile 名字回退，并非空白名称；`team=null`，没有队伍前后缀干预。
- 正版与 MUA 自身视角也均有完整三人名单。

这些现场证据排除了此快照时刻的档案缺失、未列入 TAB、陈旧 listed 对象及空白名字；用户所述“离线仍看不到 MUA”应继续从客户端显示路径排查。此次服务端跟踪仍晚于入服，但客户端实际状态已补足关键证据，不能再把服务端跟踪计数 0 当成真实名单为空。

只读核对现场 Exordium 1.4.1 字节码：启用 TAB 缓存时可跳过原版 render 而直接绘制缓存；关闭对应 enabled 开关后会调用原版绘制。当前 `tablistSettings.enabled=true`，因此做单变量兼容验证：仅将 `D:/.minecraft/config/exordium.json` 的 `tablistSettings.enabled` 改为 false，并逐项验证其他 JSON 配置不变。原文件备份为 `exordium.json.before-tab-cache-20261108-234700.bak`。

尚未观察这次配置修改后的实际图形结果，所以暂不能断言 Exordium 是唯一根因或故障已消失。下一步仅需重启离线客户端并检查 TAB；不改服务端名单、身份、UUID 或玩家权限。
