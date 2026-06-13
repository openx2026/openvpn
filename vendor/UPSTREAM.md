# vpn-engine 上游溯源

| OpenVPN Android | v2rayNG | AndroidLibXrayLite | hev-socks5-tunnel | 同步日期 |
|-----------------|---------|--------------------|-------------------|----------|
| libs-refresh | 2.2.3 | v26.6.2 | apk-extract | 2026-06-12 |

## 引擎白名单

Kotlin：`AppConfig.kt` + `core/` `service/` `handler/` `fmt/` `dto/` `enums/` `util/` `contracts/` `extension/` `receiver/`（排除 `ui/`）

资源：`res/`、`assets/`

## 本地补丁（同步后自动应用）

| 文件 | 说明 |
|------|------|
| `handler/NotificationManager.kt` | 通知点击使用 `getLaunchIntentForPackage` |
| `AppConfig.kt` | `ANG_PACKAGE` 由宿主 App 在 `Application.onCreate` 注入 |
