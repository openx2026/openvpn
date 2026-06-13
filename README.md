# OpenVPN — 稳定高速，链上订阅

**会员低至 1.2 USDT/月** · 多区域节点 · App 内开通续费 · USDT 链上支付公开透明

---

## 立即下载

| | |
|---|---|
| **Android APK（v1.0.0）** | [https://www.openshopx.xyz/downloads/openvpn_1.0.0.apk](https://www.openshopx.xyz/downloads/openvpn_1.0.0.apk) |
| **官网** | [https://www.openshopx.xyz](https://www.openshopx.xyz) |

> 安装提示：若系统提示「未知来源」，请在设置中允许本应用安装；首次连接 VPN 需授予系统 VPN 权限。

---

## 为什么选择 OpenVPN

- **低价透明** — 年付折合约 **1.2 USDT/月**，套餐档位清晰，无隐藏扣费。
- **链上支付** — 使用 **USDT** 多链支付，订单与到账可在链上核对，流程公开可查。
- **一站完成** — 注册、选套餐、支付、查订单、连 VPN，均在 App 内完成。
- **多区域节点** — 优质线路，适合日常浏览、影音与稳定长连。
- **订阅即用** — 会员有效后自动获取节点订阅，一键连接，无需手动折腾配置。

---

## 三步开始使用

1. **下载并安装** 上方 APK，使用用户名注册 / 登录。
2. **订阅** — 在「订阅」页选择套餐与支付链，按页面金额向订单地址转账 USDT。
3. **连接** — 支付入账后，点击底部 **VPN** 按钮即可连接；套餐与订单可在 App 内随时查看。

---

## 价格参考

- 页面展示为 **套餐月费（USDT）**；实际应付 = 月价 × 订阅周期，以下单页为准。
- **年付档低至 1.2 USDT/月**，多档周期可选（月 / 季 / 年等）。

具体在售套餐以 App 首页与「订阅」页实时展示为准。

---

## 客服与支持

遇到问题、支付未到账或节点异常，欢迎加入 Telegram 群组：

**[https://t.me/+Ro9TM5EiwN82ZGMx](https://t.me/+Ro9TM5EiwN82ZGMx)**

建议在群内说明：用户名、订单号（如有）、支付链与大致时间，便于快速处理。

---

## 安全与隐私说明

- 登录态与敏感凭据在设备侧加密存储；请勿将账号借给他人使用。
- 链上支付请**核对订单页收款地址与金额**，仅向 App 内展示的地址转账。
- 仅从官网或本页提供的链接下载 APK，避免第三方篡改安装包。

---

## 开发者（构建与维护）

本仓库为 **独立 App + vpn-engine**（引擎同步自 v2rayNG，UI 自研）。本地构建、调试与发版说明见：

- [vpn-engine/MAINTENANCE.md](vpn-engine/MAINTENANCE.md)
- [vendor/UPSTREAM.md](vendor/UPSTREAM.md)

```bash
cd openvpn-android
./gradlew :app:releaseApk    # 产物：release-artifacts/openvpn_1.0.0.apk
```

Release API：`https://www.openshopx.xyz/api`（见 `app/build.gradle.kts`）。

---

**OpenVPN** — 链上订阅，公开透明，省心连接。
