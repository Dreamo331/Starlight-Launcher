# Starlight Launcher — Third-Party Notices

**Version 1.0 · Last updated: 20 October 2026**

This file lists the third-party software, data and services used by Starlight Launcher, together with the license or terms that govern each of them. It is provided to satisfy the attribution requirements of those licenses and to tell Users which third party is responsible for what.

> **Authoritative list.** The definitive list, with full license texts, is the launcher's own **Settings → Copyright → Open-source licenses** screen, together with any `THIRD-PARTY-NOTICES` file shipped inside a release package. Where this file and that screen differ, that screen prevails.
>
> **Scope.** This file does not modify, restrict or supplement any third-party license. If a third party's terms conflict with this file, the third party's terms control with respect to that third party's material.
>
> **中文版：** 见 [第三方声明](第三方声明-Third-Party-Notices-中文.md)。

---

## 1. Runtime and UI

| Component | Used for | License | Notes |
|---|---|---|---|
| JavaFX 17.0.6 | Entire user interface | GPL v2 with Classpath Exception | Permits linking from MIT-licensed code; the Exception removes the copyleft effect for this use. |
| OpenJDK / Eclipse Temurin | Running the launcher and Minecraft | GPL v2 with Classpath Exception | **Not bundled.** Downloaded on the User's request from Tsinghua TUNA / Huawei Cloud mirrors or Adoptium, and stored under `~/.starlight/java/`. |

## 2. Libraries

| Component | Used for | License |
|---|---|---|
| Gson | JSON parsing | Apache-2.0 |
| Jackson (databind, core, annotations) | JSON parsing for the plugin API | Apache-2.0 |
| Logback / SLF4J | Logging | EPL-2.0 / LGPL-2.1 (SLF4J: MIT) |
| OSHI | Reading memory and CPU information locally | Apache-2.0 |
| ice4j | STUN/TURN for LAN and relay play | Apache-2.0 / LGPL-2.1 |
| ZXing | QR codes for log-share links | Apache-2.0 |
| JNA | Native access where required | LGPL-2.1 / Apache-2.0 |
| Maven (build only) | Build and packaging | Apache-2.0 |

## 3. Artwork, fonts and icons

| Component | Used for | License |
|---|---|---|
| Lucide icon set | UI icons | ISC |
| Starlight Launcher icon and artwork | Project identity | © 2026 Starlight Launcher Contributors. **Not licensed for reuse in forks** — see [LICENSE-EN.md](LICENSE-EN.md) Section 4. |

## 4. Network services and data sources

The launcher talks to the following services **on the User's behalf**. Each is operated by a third party, under that party's own terms and privacy policy. Starlight Launcher neither controls nor endorses them.

| Service | Used for | Operator / terms |
|---|---|---|
| `piston-meta.mojang.com`, `launchermeta.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net` | Game version metadata, libraries, assets | Mojang Studios / Microsoft |
| `login.microsoftonline.com`, `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`, `api.minecraftservices.com` | Microsoft account login and Minecraft profile | Microsoft |
| `bmclapi2.bangbang93.com` (BMCLAPI), `download.mcbbs.net` (MCBBS mirror, retired) | Mainland-China download mirror | Mirror operators, not affiliated with the project |
| `meta.fabricmc.net`, `maven.fabricmc.net` | Fabric loader | FabricMC |
| `maven.minecraftforge.net`, `files.minecraftforge.net` | Forge loader | MinecraftForge |
| `maven.neoforged.net` | NeoForge loader | NeoForged |
| `meta.quiltmc.org` | Quilt loader | QuiltMC |
| `optifine.net` | OptiFine | OptiFine |
| `api.modrinth.com`, `cdn.modrinth.com` (or the `mod.mcimirror.top` mirror) | Mod and modpack search and download | Modrinth |
| `api.curseforge.com`, `edge.forgecdn.net` (or mirror) | Mod and modpack search and download | Overwolf / CurseForge |
| `raw.githubusercontent.com` (HMCL mod dictionary), `www.mcmod.cn` | Chinese mod names and links | HMCL contributors; mcmod.cn |
| `littleskin.cn`, user-selected skin sites | Third-party ("Yggdrasil" / authlib) skin authentication | The skin site operator |
| `authlib-injector.yushi.moe`, GitHub releases | authlib-injector, downloaded on request | yushijinhun, AGPL-3.0 |
| `mirrors.tuna.tsinghua.edu.cn`, `repo.huaweicloud.com`, `adoptium.net` | JDK downloads | Mirror operators; Eclipse Adoptium |
| `api.logshare.cn` | **Opt-in** log upload when the User clicks "Upload log" | Logshare |
| User-configured AI provider (OpenAI, DeepSeek, Moonshot, Zhipu, Qwen, Doubao, SiliconFlow, OpenRouter, Groq, MiniMax, Hunyuan, Ernie, Spark, StepFun, Baichuan, Gemini, Anthropic) | Text features the User explicitly invokes | The selected provider |
| Third-party community site API (only if the community module is configured) | Community posts and check-in | That site |
| GitHub / Gitee | Update checks and release downloads | GitHub, Inc. / Gitee |

## 5. Game content and trademarks

- **Minecraft** is a trademark of Mojang Studios. Starlight Launcher is **not** an official Minecraft product and is **not** affiliated with, endorsed by, sponsored by or otherwise connected to Mojang Studios or Microsoft.
- Game files, assets and metadata are downloaded from Mojang-hosted services or their mirrors and remain subject to Mojang's terms. The launcher does not grant, and cannot grant, any right in Mojang's game content.
- Mods, modpacks, resource packs, shaders, skins and capes that a User downloads remain subject to the terms of their respective authors.

## 6. What is *not* included

For the avoidance of doubt, Starlight Launcher:

- contains **no** Mojang game code and **no** Minecraft client files;
- bundles **no** JDK, **no** authlib-injector, and **no** mods — these are downloaded only when the User asks for them;
- contains **no** advertising SDK, **no** analytics SDK, **no** crash-reporting SDK, and **no** tracking of User behaviour;
- includes **no** built-in payment, top-up, recharge or tipping function, and processes **no** payments of any kind;
- ships **no** monetized server list and receives **no** share of revenue from any third-party server.

## 7. Attribution obligations for redistributors

If you redistribute a build of Starlight Launcher, you must:

1. keep the full MIT notice (see [LICENSE-EN.md](LICENSE-EN.md) Section 3.3);
2. keep the notices in this file for every component your build contains;
3. provide the license text of each bundled component in a form the recipient can read — in practice, keep the "Settings → Copyright → Open-source licenses" screen intact;
4. label your build as unofficial (see [LICENSE-EN.md](LICENSE-EN.md) Section 5.1).

Removing the open-source licenses screen is a breach of the licenses of JavaFX, Logback, OSHI and the other components listed above, independently of the project's own terms.

---

*Document version 1.0 · 2026. Report an inaccurate entry via the Issues page of the official repositories.*
