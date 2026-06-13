# vpn-engine 维护文档

`vpn-engine` 是从 [v2rayNG](https://github.com/2dust/v2rayNG) 剥离出的 **VPN 引擎库模块**，供 `app/` 自研会员中心 UI 调用。UI 不在本模块内，入口为 `app` 模块的 `VpnEngineBridge`。

上游版本记录见 [`../vendor/UPSTREAM.md`](../vendor/UPSTREAM.md)。

---

## 1. 模块职责

| 职责 | 说明 |
|------|------|
| VPN 隧道 | `CoreVpnService` + Xray 核心（`libv2ray.aar`）+ hev tun（`libhev-socks5-tunnel.so`） |
| 订阅与节点 | 拉取订阅 URL、解析协议、MMKV 持久化、随机选节点 |
| Geo 资源 | `geoip.dat` / `geosite.dat` 等资产准备 |
| 后台能力 | 通知、Widget、Boot、Tasker 等（Manifest 中已裁剪注册） |

**不做的事**：登录、下单、会员 API——这些在 `app/` 模块。

---

## 2. 目录结构

```
vpn-engine/
├── build.gradle.kts          # 本仓库维护（library 配置、jniLibs）
├── consumer-rules.pro        # 本仓库维护（app Release R8 时合并）
├── libs/                     # 原生依赖（脚本更新，勿手改二进制）
│   ├── libv2ray.aar
│   └── <abi>/libhev-socks5-tunnel.so
└── src/main/
    ├── AndroidManifest.xml   # 本仓库裁剪维护（仅保留引擎所需 Service/Receiver）
    ├── java/com/v2ray/ang/
    │   ├── core/ … receiver/   # 从 v2rayNG 同步（见 §3）
    │   ├── AppConfig.kt        # 同步 + 补丁
    │   └── AngApplication.kt   # 同步脚本生成（非 v2rayNG 原版）
    ├── res/                    # 从 v2rayNG 整目录同步
    └── assets/                 # 从 v2rayNG 整目录同步
```

---

## 3. 从 v2rayNG 拷贝的内容

同步脚本：`../scripts/sync-engine-from-v2rayng.sh`  
源路径默认：仓库根目录 **git submodule** `v2rayNG/` → `V2rayNG/app/src/main/`

首次克隆后需初始化子模块：

```bash
git submodule update --init --recursive v2rayNG
```

### 3.1 Kotlin/Java（白名单）

每次同步只 **删除并重建** 下列子目录（**不会** `rm -rf` 整个 `com/v2ray/ang/`），再拷贝：

| 目录 | 作用 |
|------|------|
| `core/` | Xray 封装、配置生成、出站构建 |
| `service/` | VPN 服务、TProxy、测速服务 |
| `handler/` | 配置/订阅/MMKV/通知/Geo 等 |
| `fmt/` | vmess / vless / trojan / ss 等协议解析 |
| `dto/` | 数据模型 |
| `enums/` | 路由、语言等枚举 |
| `util/` | HttpUtil、JsonUtil 等 |
| `contracts/` | 接口契约 |
| `extension/` | Kotlin 扩展 |
| `receiver/` | 广播接收器 |

另拷贝：`AppConfig.kt`

### 3.2 资源与资产（整目录 rsync）

- `res/` → 布局、图标、多语言 strings、themes、xml 等
- `assets/` → `v2ray_config.json`、路由规则、`proxy_package_name`、许可证 HTML 等

### 3.3 刻意排除

| 排除项 | 原因 |
|--------|------|
| **`ui/` 包** | v2rayNG 完整界面不进入本仓库；由 `app/` 自研 |
| **v2rayNG 原版 Application** | 由 `local-overlay/` 提供精简版 `AngApplication.kt` |

### 3.4 同步后自动补丁

脚本 `apply_patches()` 会修改：

| 文件 | 改动 |
|------|------|
| `handler/NotificationManager.kt` | 去掉 `MainActivity` 依赖；通知点击 `getLaunchIntentForPackage` |
| `AppConfig.kt` | `hostPackageName` / `ANG_PACKAGE` 由宿主 `OpenVpnApplication` 注入 |

脚本 `apply_local_overlay()` 会从 `vpn-engine/local-overlay/java/` 覆盖拷贝 OpenVPN 专用文件：

| 文件 | 说明 |
|------|------|
| `AngApplication.kt` | 最小 Application，仅保存 `application` 引用 |
| `handler/GeoAssetsManager.kt` | 启动前确保 geo 路由数据库就绪 |
| `core/CoreServiceManager.kt` | 注入 `measureCurrentDelay()`（供 `VpnEngineBridge` 测延迟） |

---

## 4. 原生库（非源码同步）

脚本：`../scripts/update-vpn-engine-libs.sh`

| 文件 | 来源 |
|------|------|
| `libs/libv2ray.aar` | [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) GitHub Release |
| `libs/<abi>/libhev-socks5-tunnel.so` | 从 v2rayNG APK 解包，或 NDK 编译（`compile-hevtun.sh`） |

更新后请同步修改 `../vendor/UPSTREAM.md` 中的版本与日期（脚本可自动写入）。

---

## 5. 本仓库自维护、不同步覆盖的文件

以下文件 **不在** `sync-engine-from-v2rayng.sh` 源码拷贝范围内，改完后不会因同步丢失：

| 文件 | 说明 |
|------|------|
| `consumer-rules.pro` | Release R8 keep 规则（Gson DTO、JNI、handler 等） |
| `AndroidManifest.xml` | 裁剪后的 Service / Receiver / 权限 |
| `MAINTENANCE.md` | 本文档 |
| `local-overlay/` | 同步后 overlay 的 OpenVPN 专用 Kotlin 源（见 §3.4） |

`build.gradle.kts` 会被同步脚本 **仅更新** `VERSION_NAME` 一行（与 v2rayNG 子模块 `versionName` 一致）；其余 Gradle 配置需手动维护：

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` | Library 依赖、`APPLICATION_ID` BuildConfig 等 |

以下在 `res/` 内，**会被同步覆盖**，若需定制要在同步后重做或扩展同步脚本：

| 文件 | 当前策略 |
|------|----------|
| `res/xml/network_security_config.xml` | Release 仅系统 CA；实际生效以 `app` 模块 Manifest 引用的配置为准（见 §8） |

---

## 6. app 模块实际使用的引擎能力

`app` 通过 `com.openvpn.client.bridge.VpnEngineBridge` 调用，主要涉及：

```
VpnEngineBridge
  ├── GeoAssetsManager.ensureReady()
  ├── AngConfigManager.importBatchConfig / updateConfigViaSub
  ├── MmkvManager（订阅、节点、选中 server）
  ├── CoreServiceManager / CoreVpnService（启停 VPN）
  └── MessageUtil（VPN 状态广播）
```

会员 API、登录、订单在 `app` 的 `PortalApi`，与引擎网络栈分离（`PortalApi` 使用 `Proxy.NO_PROXY` 直连）。

---

## 7. 冗余资源说明

`res/` 中仍保留大量 v2rayNG UI 资源（如 `activity_settings.xml`、`activity_main.xml`、about/logcat 等），但 **`ui/` 代码未同步**，这些布局当前 **无 Activity 使用**。

- 不影响运行与包体积（Release 已 `shrinkResources`）
- 同步引擎时仍会一并更新，属预期行为
- 若需瘦身，应在 v2rayNG 侧无法单独剔除，只能后续做资源白名单同步（未实现）

---

## 8. 网络安全配置

Android 以 **Application 级** `networkSecurityConfig` 为准，当前由 **`app` 模块** 声明：

| 构建类型 | 配置文件 | 策略 |
|----------|----------|------|
| Release | `app/src/main/res/xml/network_security_config.xml` | 仅系统 CA，禁止明文 HTTP |
| Debug | `app/src/debug/res/xml/network_security_config.xml` | 系统 + 用户 CA，允许明文（局域网调试） |

`vpn-engine/src/main/res/xml/network_security_config.xml` **未被** `vpn-engine/AndroidManifest.xml` 引用，仅作与上游对齐的遗留参考；同步 v2rayNG 后若被改回「信任用户 CA」，需按 Release 策略改回或依赖 app 侧配置。

引擎内 HTTP（如 `HttpUtil` 拉订阅）走 OkHttp，受上述 app 级配置约束。

---

## 9. 日常维护流程

### 9.1 仅更新 Kotlin / res / assets（跟踪 v2rayNG）

```bash
cd openvpn-android

# 可选：指定 v2rayNG commit / tag
./scripts/sync-engine-from-v2rayng.sh --ref <tag-or-commit>

./gradlew :app:assembleDebug
```

**同步后检查清单：**

- [ ] `../vendor/UPSTREAM.md` 中 v2rayNG commit 是否已更新
- [ ] `AppConfig.kt` / `NotificationManager.kt` 补丁是否仍生效（脚本应自动打）
- [ ] `res/xml/network_security_config.xml` 是否需按 §8 改回
- [ ] 真机：导入订阅 → 连接 VPN → 断连
- [ ] Release：`./gradlew :app:assembleRelease` + R8 无运行时崩溃

### 9.2 更新 Xray / hev 原生库

```bash
cd openvpn-android

# 默认：拉最新 libv2ray.aar + 从 v2rayNG APK 解 hev
./scripts/update-vpn-engine-libs.sh

# 指定版本
./scripts/update-vpn-engine-libs.sh --tag v26.6.2 --hev apk
```

**更新后检查清单：**

- [ ] `libs/libv2ray.aar` 与各 ABI `.so` 齐全
- [ ] `../vendor/UPSTREAM.md` 版本行已更新
- [ ] 全 ABI 或目标机型 VPN 连接正常

### 9.3 推荐顺序（大版本升级）

1. `update-vpn-engine-libs.sh`（原生库）
2. `sync-engine-from-v2rayng.sh`（Kotlin + 资源）
3. 跑通 Debug + Release 构建
4. 真机回归 VPN 全流程

---

## 10. 宿主 App 集成要点

`OpenVpnApplication`（`app` 模块）在 `onCreate` 中：

```kotlin
AppConfig.hostPackageName = packageName
```

并初始化 MMKV、WorkManager、`GeoAssetsManager` 等，与 v2rayNG 原版 `Application` 职责对齐。

`vpn-engine/AndroidManifest.xml` 中 VPN 服务运行在独立进程 `:RunSoLibV2RayDaemon`；判断 VPN 是否连接时，主进程 `CoreServiceManager.isRunning()` 可能为 false，需像 `VpnEngineBridge.isRunning()` 一样回退查 `ActivityManager` 服务状态。

---

## 11. Release 混淆

`app` Release 开启 R8 时，会合并 `consumer-rules.pro`，保留：

- `com.v2ray.ang.core.**`
- `dto` / `handler` / `service` / `fmt` 等 Gson 与 JNI 相关类

同步大量改 `dto` 后若 Release 崩溃，先查 `mapping.txt` 与是否需补充 keep 规则。

---

## 12. 相关文件索引

| 路径 | 用途 |
|------|------|
| `../scripts/sync-engine-from-v2rayng.sh` | 从 v2rayNG 同步引擎源码与资源 |
| `../scripts/update-vpn-engine-libs.sh` | 更新 `libs/` 原生依赖 |
| `../vendor/UPSTREAM.md` | 上游版本溯源表 |
| `../app/src/main/java/com/openvpn/client/bridge/VpnEngineBridge.kt` | app ↔ 引擎桥接 |
| `../app/src/main/java/com/openvpn/client/OpenVpnApplication.kt` | 宿主 Application |

---

## 13. 常见问题

**Q：同步后编译失败，提示找不到 `MainActivity`？**  
A：检查 `NotificationManager.kt` 补丁是否被覆盖；重新运行同步脚本（会自动打补丁）或手动按 §3.4 修改。

**Q：VPN 能连但会员 API 失败？**  
A：会员 API 在 `app/PortalApi`，与引擎无关；检查 `API_BASE_URL` 与网络安全配置（Debug 明文 / Release HTTPS）。

**Q：能否只更新 `libv2ray.aar` 不同步 Kotlin？**  
A：可以，但大版本 Xray 常与 Java/Kotlin 封装不匹配；建议库与源码同批次升级并做回归。

**Q：`res/values/strings.xml` 仍显示 v2rayNG？**  
A：引擎模块保留上游字符串；用户可见 App 名称以 `app` 模块 `strings.xml` 为准。
