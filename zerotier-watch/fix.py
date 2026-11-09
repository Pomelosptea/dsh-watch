from pathlib import Path
p = Path(__file__).with_name("fixed.java")
s = p.read_text(encoding="utf-8")
lines = s.splitlines()
lines[0] = lines[0][lines[0].find("package"):]
fix = {
139: '                view.setError("需要允许 VPN 才能加入虚拟局域网");',
174: '            view.setError("系统不支持 VPNService");',
185: '            view.setBusy("正在启动…");',
223: '        view.setBusy("正在断开…");',
248: '            view.setState("未连接", idText, "", false, false);',
255: '                view.setState("未连接", idText, "", false, false);',
298: '                label = "已连接";',
307: '                label = "访问被拒绝";',
310: '                label = "网络不存在";',
316: '                label = "连接中";',
321: '            view.setState("未连接", idText, "", false, false);',
328: '        input.setHint("16 位十六进制网络 ID");',
337: '                .setMessage("输入 Central 里的 16 位网络 ID")',
345: '                input.setError("必须是 16 位十六进制");',
368: '        private String state = "未连接";',
470: '            canvas.drawText(ip.isEmpty() ? (online ? "虚拟局域网已就绪" : "等待地址") : ip, cx, y(247), paint);',
478: '            canvas.drawText(networkIdText.isEmpty() ? "— — —" : networkIdText, cx, y(331), paint);',
491: '            String action = hasNetwork ? (online ? "断开" : (state.startsWith("正在") || state.startsWith("准备") ? "请稍候" : "连接")) : "加入网络";',
}
for n, text in fix.items():
    lines[n-1] = text
out = "\n".join(lines) + "\n"
Path(__file__).parent / "src" / "com" / "zerotier" / "one" / "ui" / "NetworkListActivity.java".write_text(out, encoding="utf-8")
print("wrote", len(lines), "lines")
