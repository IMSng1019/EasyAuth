# TAB 名单可见性排查与修复方案（2026-11-05）

> **2026-11-09 决策更新：** 已确认根因是 Exordium 1.4.1 的缓存指纹遗漏。用户明确要求不由 EasyAuth 修复此第三方问题；服务端兼容绕过补丁及 `.13` 实验包已撤回。本文后续修复分支仅作历史方案保留，不再执行；EasyAuth 保持原有 TAB 行为，只保留可选诊断工具和取证记录。

**目标：** 在不改变账号身份、UUID、认证数据库、真实计分板队伍和隐身状态的前提下，定位并修复真实服务器上"离线玩家 TAB 看不到其他人、MUA 玩家能看到别人、正版玩家看不到 MUA 玩家名字"的混合客户端名单问题；先取得能区分根因的现场证据，再实施对应修复。

**当前结论：** 服务端发包路径在模拟环境（真实 `PlayerList.placeNewPlayer`、EmbeddedChannel、VMP/Krypton/PacketFixer/ServerCore 等组合）中已多次通过，无法复现缺失；因此真实故障更可能位于"客户端侧状态/渲染"或"真实网络与第三方模组处理"阶段，现有证据不足以直接改服务端发包逻辑。

## 已确认事实与证据

1. 唯一一条真实客户端异常日志（`D:\.minecraft\logs\latest.log`，2026-11-05 记录）：

   ```text
   [00:06:14] [Render thread/WARN]: Ignoring player info update for unknown player 01a17d18-ebd6-7c68-ba2f-bfc9554b9742 ([UPDATE_GAME_MODE])
   ```

   该 UUIDv7 是 MUA 玩家 `[MUA]LXW1019` 的本地 UUID。原版 1.20.4 的 `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing` 必带 `ADD_PLAYER + UPDATE_LISTED`；单独广播 `UPDATE_GAME_MODE` 只来自 `ServerPlayerGameMode.changeGameModeForPlayer`（字节码已核对）。这说明真实环境中确实存在"客户端还没拿到某玩家档案、却先收到该玩家属性更新"的时序窗口。它不能直接证明 TAB 缺人，但证明该窗口真实存在，需要在修复中消除或兜底。

2. 现场两个客户端实例共用同一个游戏目录 `D:\.minecraft`：日志时间交错、ViaFabricPlus `blob_cache\LOCK` 获取失败、Bobby 缓存曾大量损坏。这使现有客户端日志无法归属到单一 viewer，结论必须建立在分离目录后的重测上。

3. 现场客户端约 152 个模组（Exordium 1.4.1、ImmediatelyFast、ViaFabricPlus、Sodium、ReplayMod 等）。只读核对：
   - Exordium 的 TAB 缓存哈希包含 listed 名单、displayName 字符串与样式、队伍前缀/后缀/颜色、皮肤、延迟、游戏模式等，正常收包时会刷新；
   - ReplayMod 只把自己的 join 包重新序列化用于录制，不取消处理；
   - 客户端与服务端 mod 扫描均未发现第三方按 UUID/前缀过滤 player-info 的代码；
   - Fuji 的 player-list/AFK 模块为关闭状态，Carpet 只涉及假玩家。
   因此"某个 mod 直接过滤名单"概率较低，但不能排除 ViaFabricPlus/渲染缓存组合在真实现场的交互。

4. 服务端 `.12` 运行期间，离线、MUA、正版三类账号确实同时在线（`Offline_52565`、`[MUA]LXW1019`、`LXW1019`），服务端无相关异常；问题只在实际客户端 TAB 表现上出现。

## 假设排序

| 编号 | 假设 | 能解释的现象 | 验证方式 |
| --- | --- | --- | --- |
| H1 | viewer 客户端缺少/忽略了目标玩家的 `ADD_PLAYER` 或 `UPDATE_LISTED`（时序、丢包、第三方处理） | 整行不存在 | 服务端 trace + 客户端探针 |
| H2 | 显示名组件为空或过期（`UPDATE_DISPLAY_NAME`、客户端队伍投影），或 `tabName` 返回空白组件 | 有行/头像但名字空白 | 客户端探针看 displayName |
| H3 | 客户端渲染缓存（Exordium/ImmediatelyFast 等）或共享目录导致的客户端异常 | 服务端记录正确、客户端不显示 | 干净客户端 / 关闭 Exordium A/B |
| H4 | 第三方服务端 mod 过滤/改写 player-info | 某类 viewer/target 固定缺失 | trace 显示服务端未发包或 listed=false |

## 阶段 0：现场取证（不改代码，先做）

1. **分离客户端目录。** 正版、MUA、离线三个客户端各用独立 `gameDir`（独立 `logs` 与缓存），不要再让两个实例共用 `D:\.minecraft`。这是后续所有结论的前提。
2. **用现有诊断构建复现一次。** 服务端换用 `versions/1.20.3-fabric/build/libs/easyauth-fabric-mc1.20.4-4.0.0-multisource.12-tab-diagnostic.jar`（不要同时放两份 EasyAuth），执行 `auth tab trace 120`（控制台或管理员），让三种客户端依次重连并完成登录，各自按 TAB，然后 `auth tab status`、`auth tab stop`。保留服务端 `logs/latest.log` 与三个客户端各自的 `logs/latest.log`。
3. **记录 3×3 矩阵**（viewer × target）并截图：行是否存在、名字是否空白、头像/延迟是否显示；同时记录聊天、头顶名牌、侧边栏是否正常，用于区分"只有 TAB 异常"还是全局显示异常。
4. **配置级 A/B（不改代码）：**
   - a. 仅禁用/移除 Exordium 后重测（验证 H3 渲染缓存）；
   - b. 清空 `officialNamePrefix`、`offlineNamePrefix` 后 `/auth sources reload` 重测（验证 H2 显示名/队伍投影；不会重命名已分配真实前缀的身份）；
   - c. 交换三种客户端入服顺序重测（验证 H1 时序/补发）；
   - d. 用干净原版客户端（无模组、独立目录）重测（验证 H3 模组兼容）。
5. **判定表：**
   - trace 中出现该 viewer→target 的 `ADD_PLAYER` 且 `listed=true`，但 TAB 仍缺 → 客户端侧（走 H1/H2/H3）；
   - trace 本身缺 `ADD_PLAYER`、`listed=false` 或被 `REMOVE` → 服务端/第三方（走 H4）；
   - 行存在但名字空白 → H2；
   - 干净客户端正常、现场模组客户端异常 → H3。

## 阶段 1：客户端 TAB 探针（需批准后加代码，默认关闭）

服务端 trace 无法证明客户端内存状态，需要一个小型、只读的客户端探针（放入 companion 或独立 dev jar，默认关闭）：

- TAB 打开时输出：`playerInfoMap.size`、`listedPlayers.size`，以及每条的 UUID、profile name、`tabListDisplayName` 字符串与样式、队伍名/前缀、游戏模式、延迟；
- 对收到的 `ClientboundPlayerInfoUpdatePacket` / `ClientboundSetPlayerTeamPacket` 做有界日志（动作集合、profileId、listed、displayName、成员名）；
- 只写日志，不修改任何包或渲染结果。

这一步能最终区分"entry 根本不存在 / entry 存在但 displayName 为空 / 数据都正确但界面不显示"。

## 阶段 2：修复分支

**A. 时序与补发（H1，及 H4 中属于服务端的窗口）**

- 新玩家完成入服广播后，对其初始化包做一次幂等补发（真实客户端即使错过第一次也能恢复）；
- viewer 完成认证、重生、`/auth sources reload` 后，向该 viewer 发送当前可见玩家的完整初始化快照（去重、限流、尊重 Vanish 与不应看到的关系）；
- 新增 `/auth tab resync [player|all]` 运维命令；可选的周期对账默认关闭；
- 不改变任何玩家的 `listed` 隐身语义，不向"看不到"的关系泄露玩家档案。
- 测试：模拟丢弃 `ADD_PLAYER` 后自动恢复、重复补发幂等、Vanish 仍隐藏、无重复条目、会话中途重登。

**B. 服务端发包顺序 / 第三方（H4）**

只按 trace 证据修：若确认某第三方 mod 在 EasyAuth 之后拦截/改写包，记录兼容性结论并按需提供开关；不做猜测性屏蔽。

**C. 显示名空组件与过期（H2）**

- `SourceNameDisplay.tabName` 永不返回空组件：原 displayName 非空但内容为空白时回退到 `PlayerTeam.formatNameForTeam(...)`，两者都空时返回 `null` 让原版使用 profile name；
- 保证入服初始化包发出前 displayName 已是最终值，避免"先 ADD 再 UPDATE_DISPLAY_NAME"的竞态；对 real-prefixed（`[MUA]/[OFF]`）玩家跳过无意义的显示名更新；
- 增加 `tab-display` 开关（默认开启）：可关闭纯显示前缀与客户端队伍投影，用于兼容排查和第三方冲突降级。

**D. 客户端兼容（H3）**

- 若确认 Exordium/ImmediatelyFast/ViaFabricPlus 的渲染缓存问题：先给出关闭/升级该模组的兼容说明；
- 必要时在显示名更新后补一次 `UPDATE_LISTED` 触发客户端缓存哈希变化（需客户端证据支持后再做，并补回归测试）。

**E. 环境要求**

文档和验收中明确"一个客户端一个 gameDir"；共享目录属于已知问题，不再作为有效复现条件。

## 阶段 3：验证与交付

- 真实客户端 3×3 矩阵（干净客户端 + 现场模组客户端），覆盖三种 viewer × 三种 target、任意入服顺序、重登/重生/重载配置、Vanish 开/关；
- GameTests：补发恢复、幂等、Vanish 不泄露、displayName 空组件防护，保留现有 Nameplate/PlayerList 回归；
- 构建 `.13`，更新 `TAB_SKIN_FINDINGS` / `TAB_TRACE` 文档；
- 不迁移身份账本、不改 UUID、不通过清前缀或删除队伍"掩盖"问题。

## 已知限制（不属于本次缺陷）

- 离线客户端走未加密连接，原版 1.20.4 客户端只在加密连接上绘制 TAB 头像；因此离线玩家 TAB 没有头像是原版行为，不等于"看不到名字"。
- 纯显示前缀仍受原版协议限制：同一客户端队伍不能为不同成员设置不同前缀，好友隐身/队伍碰撞等关系可能受影响；真实前缀身份不依赖该机制。

## 需要确认的信息

1. "看不到"具体是：整行没有 / 有行但名字空白 / 只有头没有名字 / 只是没有头像？
2. 三个客户端是否已使用独立游戏目录？MUA 与正版是否同一台机器上的两个实例？
3. 能否提供最近一次复现的服务端 `latest.log` 和三个客户端各自 `latest.log`（可脱敏）？
4. 客户端模组列表（尤其 Exordium / ImmediatelyFast / ViaFabricPlus 版本），是否愿意用干净客户端做一次对照？
5. 是否同意进入阶段 1/2 的代码改动（默认关闭的探针 + 低风险加固），还是先只做阶段 0 取证？
