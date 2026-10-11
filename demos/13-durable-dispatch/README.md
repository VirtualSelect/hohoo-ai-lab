# 持久幂等、投递与历史恢复

这是三个可单独阅读、共用一份协议的 Java 8 工程案例。固定材料 `add-one` 代表一次工具副作用；没有模型、扣款或真实业务调用。

| 问题 | 实现入口 | 证据分组 |
| --- | --- | --- |
| 重启后如何重放同一意图？ | `Ledger.java`：带CRC帧、完整记录校验、文件锁 | `results.json` / ledger |
| 本地落盘为何仍不能保证远端只执行一次？ | `Workflow.java`：intent → HTTP → receipt | outbox |
| 工具成功却来不及写历史，重启如何对账？ | `Workflow.resume`：receipt → complete turn | history |

实际运行17种场景，保存193条子进程记录、9次退出码23的进程崩溃、99个截断前缀与二进制日志。无下游幂等时重复执行2次，是保留的反例，不是测试失败。八个进程并发同key只提交一个记录；八个dispatcher通过独立锁串行恢复。

## 从干净输出复现（PowerShell）

```powershell
python demos/13-durable-dispatch/run.py --java-home "E:/Java/jdk1.8.0_171" --out demos/13-durable-dispatch/target/my-run
python demos/13-durable-dispatch/audit.py demos/13-durable-dispatch/target/my-run
```

修改JDK路径；`--out`必须尚不存在，避免覆盖归档。要求JDK8（不是JRE）和Python3.12，无Maven与第三方Python依赖。已验证Windows/JDK1.8.0_171/Python3.12.14。审计独立解码ASCII测试材料，核对哈希、CRC、日志序号、效果次数、回执和整轮历史，输出 `PASS: 17 scenarios...`。

## 设计边界

- 业务状态就是同一个追加日志里的记录数，未把独立计数文件和幂等表伪装成原子事务。
- 完整记录CRC失败直接拒绝，不截断重试；仅缺少完整尾帧时截断到已确认前缀。CRC不是防篡改签名。
- 文件锁只面向遵守约定的本地进程。同一JVM的并发调用应额外串行化；HTTP服务使用默认串行处理，未承诺跨主机/NFS锁语义。
- `force(true)`和halt覆盖指定进程故障，不证明断电、磁盘控制器、目录同步或文件系统损坏下的耐久性。
- 回执和完整问答按同一稳定key幂等保存；结果是固定工具计数，并非模型生成。没有联网聊天产品、流式UI、认证、租户隔离、日志压缩或有界保留。
- HTTP只绑定回环地址；CLI故障参数不能作为公开接口上线。接收端必须持续保留key；过早清理会重新打开重复执行窗口。

源代码/协议冻结版本见manifest.source_commit；证据hash按原字节，源码hash统一LF。文章与代码由AI辅助整理，具体结论以可审计记录为限。
