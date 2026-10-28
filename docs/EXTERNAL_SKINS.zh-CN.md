# 让正版客户端显示 MUA 皮肤

本次正版客户端在 `19:24:57` 明确报错 `Textures payload contains blocked domain`，拒绝了 `skin.mualliance.ltd` 的皮肤地址。服务端已经转发 MUA 的纹理属性；要让观看者显示它，需要客户端支持外置纹理。这里使用 CustomSkinLoader 直接读取服务器发送的 `GameProfile.textures`，不使用服内的 `[MUA]` 名字查询上游皮肤站，也不更改账号 UUID 或登录认证。

## 已准备的客户端文件

交付目录：`build/libs/client-skin-support/`。

- `CustomSkinLoader_Universal-15.1.jar`：从[官方稳定版 v15.1](https://github.com/xfl03/MCCustomSkinLoader/releases/tag/v15.1)下载的原文件，未运行。
- `CustomSkinLoader.json`：只采用服务器已提供纹理的最小配置。
- `SHA256SUMS.txt`：官方发布摘要，下载后已比对本地 SHA-256。

官方 v15.1 的[版本元数据](https://github.com/xfl03/MCCustomSkinLoader/blob/v15.1/build.info.json)明确列出 Fabric、Minecraft 1.20.4 和 Java 17。该版本使用 Universal jar，不要安装源码包或内部 Common jar。官方文件长度为 222917 字节，SHA-256 为：

```text
c8769a38b3cc1da2a7b2f5827d1a53a24cd1ef6d0784b1eb545569f5882a2ffb
```

## 安装与配置

1. 退出两个客户端。给正版和 MUA 实例使用不同的实际游戏目录，避免共写日志和缓存。
2. 将上述 jar 放进**每个需要观看 MUA 皮肤的客户端**的 `mods` 目录。仅安装在 MUA 客户端上，不会改变正版客户端看到的皮肤。已有 CustomSkinLoader 的实例先备份旧 jar，再使用一个版本，避免同时加载多个版本。不要把这个客户端支持包放进服务器。
3. 启动客户端一次，再退出。确认实际游戏目录下生成 `CustomSkinLoader/CustomSkinLoader.json`。
4. 如果已有配置，先备份该文件，保留其他顶层设置，仅把 `loadlist` 改成下面的数组。仅在新实例或确认没有需要保留的配置时，才用支持包中的最小配置文件替换它。

   ```json
   {
     "loadlist": [
       {
         "name": "GameProfile",
         "type": "GameProfile"
       }
     ]
   }
   ```

5. 重启各客户端，并让正版和 MUA 玩家全部退出服务器后重新进入。在双方视角检查人物皮肤、细胳膊模型和 TAB 头像。

`loadlist` 只包含 `GameProfile`，可避免其他来源按同名账号查询并混入纹理。若想保留其他来源，至少把 `GameProfile` 放在首项；这仍可能发出其他来源的查询。已有 `CustomSkinLoader/ExtraList` 文件会在启动时加入配置，验收时应检查最终加载列表，而不要直接删除已有自定义文件。

## 确认是否生效

查看每个实例自己的 `CustomSkinLoader/CustomSkinLoader.log`：应显示版本 `15.1`，加载列表包含 `GameProfile`，并在 `[MUA]` 玩家加载过程中出现 `Default profile will be used.` 及指向 `skin.mualliance.ltd` 的 `SkinUrl`。随后仍须在游戏内确认实际显示；打印出 URL 本身不代表图片下载或渲染成功。

如果仍不显示，保留该实例的 `CustomSkinLoader.log` 与 `logs/latest.log`，检查是否读取了错误的游戏目录、加载列表被其他配置补充、图片下载失败，或其他渲染模组与它冲突。无需为皮肤问题重置 EasyAuth 身份账本、玩家存档或 MUA UUID。

## 依据与范围

- [GameProfileLoader](https://github.com/xfl03/MCCustomSkinLoader/blob/v15.1/Common/src/main/java/customskinloader/loader/GameProfileLoader.java)直接解码现有 `textures` 属性并读取图片地址，不按玩家名调用 MUA 或 Mojang API。其配置类型就是 `GameProfile`。
- [客户端皮肤加载适配](https://github.com/xfl03/MCCustomSkinLoader/blob/v15.1/Common/src/main/java/customskinloader/fake/FakeSkinManager.java)让对应 Minecraft 版本使用这些外置纹理。这是客户端选择接受服务器提供的纹理，不是为 MUA 纹理生成 Mojang 签名，也不改变服务器登录认证。
- [配置读取](https://github.com/xfl03/MCCustomSkinLoader/blob/v15.1/Common/src/main/java/customskinloader/config/Config.java)支持上述最小配置；未填写的其他字段采用默认值，启动时会补写。
- [加载顺序实现](https://github.com/xfl03/MCCustomSkinLoader/blob/v15.1/Common/src/main/java/customskinloader/CustomSkinLoader.java)按列表顺序合并结果，但会并发启动所有来源查询。因此只设置 `forceLoadAllTextures=false` 不能代替只保留 `GameProfile` 的列表。

此配置同样显示服务器提供的正版纹理。它不会为没有 `textures` 的角色或仅有名字的头颅按名补全皮肤。当前已核实官方版本声明、相关源码、下载来源和文件摘要；尚未在用户这套客户端模组组合中启动或完成图形验收，不能承诺安装后所有渲染模组组合都无冲突。
