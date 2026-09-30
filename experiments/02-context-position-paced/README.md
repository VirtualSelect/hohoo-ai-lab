# L1v2：节流与成组对照

新协议独立于首轮 L1，不覆盖旧证据，也不把两个不同材料版本直接合并评分。

- 三个虚构事实 × 60/240 条干扰材料 × beginning/middle/end/absent = 最多 24 次。
- 四种归档句式固定生成。含答案的三个条件是同一组资料重排；目标在第 0 / N÷2 / N 行。问题永远在资料后面。
- 每个 case/length 是连续四次请求的一个 block。长度按 60/240 交替，条件按 block 轮换起点；六组不足以让四种顺序完全平衡，不声称消除了时间效应。
- 请求完成后等待至少 20 秒再发下一次；HTTP 429/401/403 立即停止；其他请求/协议失败连续两次停止。无自动重试。
- 只比较完整且四份响应都有效的 block；格式错误和答错是有效响应的评分，HTTP/协议错误另计。未完成组完整保留，但不充当位置比较证据。
- 1024 输出 token 上限；这不是保证内容正文有 1024 token。系统提示、参数、代码提交和请求摘要 hash 均留存。
- 正常响应只保存最终正文与必要元数据，不保存 reasoning_content、凭证或任意错误页。
- Retry-After 只保存合法秒数或日期，不据此自动重试。20 秒是本地保守节奏，不是平台确认的配额。

## 不调用模型的检查

进入本目录，JDK 8 + Maven：

    mvn -q compile
    mvn -q exec:java -Dexec.args=--self-test
    mvn -q exec:java -Dexec.args=--dry-run

自检包含材料一致性、缺失对照、分组完整性、精确评分，以及通过假 Transport / 假 Sleeper 注入 429、401、403、500、正常响应，检查实际 runner 的停止、次数和等待行为。测试生成的模拟响应不是模型实测证据。

## 实测

自行通过进程环境设置 AGNES_API_KEY。只有下面命令发送请求：

    mvn -q exec:java "-Dexec.args=--run evidence/my-run"
    node audit.mjs evidence/my-run

输出目录必须不存在，预算最多 24 次，不会恢复或重发旧目录。连接超时 10 秒、读取超时 90 秒；后者不是整个请求的总截止。失败请求的未知账单不计入 usage 合计。

研究边界：三组固定资料、单端点单模型、无重复；240 行也不等于模型上下文上限。这是小样本机制探查，不是排行榜、能力认证或论文复现。未完成时如实记录停止原因。

离线导出所有请求（不读密钥、不发送）：

    mvn -q exec:java "-Dexec.args=--prepare evidence/my-preparation"
    node audit.mjs evidence/my-preparation --prepared

该目录只有材料和离线检查记录，不包含模型响应。真实执行另选新目录。

## 本次已完成的验证

2026-09-29，Java 1.8.0_171 上实际通过80项离线检查，完整24项请求计划已导出，独立Node审计通过。证据目录：evidence/20260929-l1v2-offline。该目录没有 attempt 响应文件；未执行新的线上模型对照，不能据此报告位置效应或成功率。

[配套文章](https://huhohoo.com/docs/llm/context-position-paired-protocol)

## 2026-09-30 · Agnes 3.0 在线结果

在协议 L1v2-agnes3（version 3）下执行全部24次请求，无重试。18份含答案响应编号正确，6份缺失答案响应为 UNKNOWN；6个block完整，未发生HTTP或协议失败。每个长度的beginning/middle/end/absent各3份，均符合预期。

- 原始证据：`evidence/20260930-agnes3-live`。
- `audit.json`：独立评分、完整块、服务端用量及最短观察间隔（20001ms）。
- `verification.json`：与20260930-agnes3-prepared逐项比对，附30份JSON的LF规范化SHA256。
- 返回用量：prompt 134614、completion 144、total 134758 tokens；这是响应usage之和，不是账单或价格估算。
- UTC 05:06:52.476–05:17:12.120；Java 1.8.0_171；运行代码提交 b352a380eb04eba936867fa2e0b79aacb0dcc732。
- 原准备协议文件在Windows检出后仅LF/CRLF发生变化，规范化哈希、JSON内容与全部请求计划一致。manifest保存运行时实际字节哈希，旧准备材料不覆盖。

结论仅为本次固定材料中未观察到位置差异。三题、两种长度、每格一次且全对存在天花板；不能证明位置无关，也不能与Agnes 2.5旧试验拼接比较。下一轮应增加语义相近干扰或问题类型并预先冻结协议，不用这24个样本调整到得出预设结论。

离线复核（不读密钥、不调用模型）：

    node audit.mjs evidence/20260930-agnes3-live
    node --test audit.test.mjs
