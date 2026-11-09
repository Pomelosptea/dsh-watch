# 安全说明

## 信任模型

- 手表 → 主机桥接：**HTTP + Bearer token**，没有 TLS。只应在 ZeroTier（或同等私有网络）内使用。
- 桥接以启动它的系统用户权限执行 DSH 指令；`autoApprove` 默认 `false`，需要审批的工具调用不会自动放行。
- 令牌等价于主机控制口令：拿到它的人可以让主机执行 DSH 指令。

## 令牌规则

1. 令牌只来自 `bridge/config.json` 的 `token` 字段（首次使用从 `config.example.json` 复制）。
2. 桥接**不接受**任何硬编码的备用令牌；`token` 为空表示关闭鉴权（仅限完全隔离的调试环境）。
3. 换令牌流程：
   ```powershell
   # 1) 改 config.json 的 token
   # 2) 重启桥接
   # 3) 验证旧令牌必须返回 401，新令牌返回 200
   curl -H "Authorization: Bearer <旧令牌>" http://127.0.0.1:18787/api/status   # 期望 401
   curl -H "Authorization: Bearer <新令牌>" http://127.0.0.1:18787/api/status   # 期望 200
   ```
   **只验证新令牌能用是不够的**：必须确认旧令牌失效。
4. 不要把真实令牌写进 README、截图、APK 默认值或提交历史。`bridge/config.json`、`dist/host/config.json` 已在 `.gitignore` 中排除。
5. 发布安装包前检查构建产物：默认令牌必须是无意义的占位值（如 `change-me`），并确认 `classes*.dex` 里不含真实令牌。

## 网络暴露面

- 默认监听 `0.0.0.0:18787`。只想走 ZeroTier 时，把 `bind` 改成主机在 ZeroTier 里的地址。
- 不要把 `18787` 映射到公网；需要外网访问时用 ZeroTier / Tailscale 之类的组网，而不是端口转发。
- 防火墙只放行需要的来源：`18787/TCP`，Private 配置文件。

## 本仓库的约定

- `dist/*.apk` 通过 [Releases](https://github.com/Pomelosptea/dsh-watch/releases) 分发，不提交进仓库；提交前用 `SHA256SUMS.txt` 核对。
- `zerotier-watch/` 只提供源码；改版 APK 不公开分发（GPL-3.0 + 签名冲突）。
- 仓库历史里不应出现真实 IP、ZeroTier 网络 ID、本机路径或令牌。

## 报告问题

发现漏洞或误提交的敏感信息，请通过 GitHub 的 Security  advisories 或 Issues 私下说明，不要直接公开利用细节。
