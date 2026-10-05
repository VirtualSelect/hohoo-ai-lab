# A8 · 取消会话、终止客户端读取与远端工作

Java 8 / Gson，真实 loopback HTTP/SSE，零外部模型请求。复用 A7 Session 和 A6 StreamReader，原文件不修改。

`python run.py --out evidence/your-run`；`python audit.py evidence/your-run`。
运行前设置 JAVA_HOME，run.py 的 Maven 路径可按本机调整。

5 条件 × 2 策略 × 3 次重复。协议见 protocol.json。Future 取消状态与工作线程退出分别观测；绝对截止从连接建立后的读取阶段开始。远端 fixture 故意在客户端断开后完成 8 个有界工作步骤，不能据此推断真实供应商计费。

## 2026-10-05 实测归档

30次本地连接，24次取消均未写入历史；仅取消Future仍等待读取超时或完整响应，关闭Socket后本机读取退出均小于1ms。

[配套文章](https://huhohoo.com/docs/ai-apps/java-transport-cancellation)。图表脚本为 plot.py；原始记录和独立审计见 evidence 目录。
