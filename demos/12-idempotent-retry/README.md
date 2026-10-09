# 本地 HTTP 幂等边界

Java 8 内置 HttpServer + Python 标准库。仅监听 127.0.0.1，不调用模型，不模拟付费副作用。

```powershell
python demos/12-idempotent-retry/run.py --java-home E:/Java/jdk1.8.0_171
python demos/12-idempotent-retry/audit.py
```

协议在 `protocol.json`。先提交协议、代码和审计，再运行；`evidence/manifest.json` 记录代码提交、版本、规范化源文件哈希和输出哈希。`results.json` 保存每次 HTTP 响应/连接异常和最终计数。运行不依赖 Gson/Maven 或网络。

同一进程中，注册表按 key 原子认领，并比较请求体 SHA-256；重复请求等待相同结果。注册表满时拒绝新 key，已有 key 仍能重放；不随意淘汰仍可能重试的记录。

**实验边界**：记录只在内存里。计数文件和幂等记录不是同一个事务。`force(true)` 后主动 halt(23) 是可复现的进程崩溃窗口，不是断电安全证明；文件 truncate/write 也不是通用事务实现。不能将本例称为持久化幂等或 exactly-once。服务中的 X-Test-* 故障头仅用于本地实验，不能直接暴露到生产环境。AI 辅助实现和整理，结论以随附运行证据为准。
