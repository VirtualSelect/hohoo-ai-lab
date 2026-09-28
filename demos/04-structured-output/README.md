# 04 / 让 Java 验收模型输出

第二篇实践文章的代码：文章分类 → 结构化结果 → 严格校验。沿用 Java 8、Gson 2.10.1、HttpURLConnection，不引入应用框架。API 响应 JSON 与模型正文 JSON 是两层不同的数据。

## 先离线运行

在 IDEA 中将本目录 pom.xml 添加为 Maven Project，使用 JDK 8；主类为 com.hohoo.ailab.structured.StructuredOutput。默认参数仅显示帮助，不调用模型。

```powershell
cd demos/04-structured-output
mvn -q compile exec:java '-Dexec.args=--self-test'
```

已在 Java 1.8.0_171 实际运行 35 项检查，含 localhost HTTP 故障注入；不访问模型。fixture 是人工测试输入，不能用来统计模型格式通过率。测试报告写入 evidence/offline-*.json，每次独立保存。

## 真实 API（按需，有调用成本）

仅使用已有 Agnes 服务。将 AGNES_API_KEY 放入 IDEA 的 Environment variables 或当前进程环境，不粘贴到源文件。

```powershell
$agnesSecret = Read-Host "AGNES_API_KEY" -AsSecureString
$env:AGNES_API_KEY = [System.Net.NetworkCredential]::new('', $agnesSecret).Password
mvn -q compile exec:java '-Dexec.args=--live evidence/my-run'
```

--live 固定发送三个公开教学输入（Java、做饭、MuJoCo），无自动重试。密钥不会保存进证据，不记录 reasoning_content 或响应头。目录必须为空。客户端连接等待10秒、单次读取等待90秒；不是整个请求的严格总时限。

另有显式能力探测，**仅一次请求**：

```powershell
mvn -q compile exec:java '-Dexec.args=--live-json evidence/my-json-probe'
```

它发送 response_format: {"type":"json_object"}。本次探测超时，因此未确认该端点/模型支持此参数；不要宣称该服务已提供严格 JSON Schema 输出。contract.schema.json 只是本地契约说明，没有发送给服务商。支持合法 JSON 和满足业务 Schema 也不是同一件事。

## 本次真实观察（北京时间 2026-09-28）

| 调用 | 观察 | 程序决定 |
| --- | --- | --- |
| prompt-only / Java | HTTP200、stop，但正文有 json 代码围栏 | 拒绝，MalformedJsonException |
| prompt-only / 做饭 | SocketTimeoutException | 拒绝；服务端执行状态未知 |
| prompt-only / MuJoCo | SocketTimeoutException | 拒绝；没有模型回答 |
| json_object / Java | SocketTimeoutException | 参数支持情况未验证 |

首次 API 返回 usage 为 prompt=431、completion=79、total=510，仅适用于这一次记录。没有对超时请求编造 Token、费用或模型回答。三次超时不用于判断模型能力或服务总体可靠性。

## 一个明确的适配，而不是偷偷修数据

严格入口 Classification.parse 不移除围栏、不过滤前后文字、不把数字强转成字符串。若产品明确允许模型返回单个完整的 json 代码块，可以选择 ContentFormat.unwrapOneJsonFence，再进入同一个严格校验器。

```powershell
mvn -q compile exec:java '-Dexec.args=--replay evidence/live-20260928/live-1.json'
```

这会用保存的真实首次响应**离线重放**，无新 API 请求。它仅接受覆盖整个内容的一对 json 围栏；有前言、两个代码块、尾随文本，或移除后分类为 java，仍拒绝。重放记录保存源文件 SHA256、转换名、解析结果。它不证明在线服务后来成功了。

## 代码地图

- StructuredOutput：固定教学请求、CLI、证据写入、显式在线探测/离线重放。
- ChatClient：HTTPS、超时、1MB响应上限、拒绝重定向、不自动重试。
- Classification：检查 envelope 的 assistant / stop / content，再严格检查内部对象的字段、枚举与 tags。
- ContentFormat：可选的单一代码围栏适配。
- ContractTests：35个离线契约、协议与 HTTP 行为检查。
- contract.schema.json：核心 JSON 契约；Java 额外拒绝重复键、标签控制字符和首尾空白。

为什么不用 Gson.fromJson 直接转 DTO？反序列化不等于业务验证。用 JsonReader 检查实际 token 类型，避免将数字变成标签字符串；拒绝重复键避免“后面的值覆盖前面”的歧义。

格式合法也不等于语义正确。例如给“炒饭”填 category=llm、tags=["炒饭"] 仍能通过结构校验。分类评估需要独立标注与语义检查，不能用这些测试替代。

## 证据版本

- 初始 strict 实现与首轮记录：提交 8e16a83。
- json_object 探测入口：提交 0c0ad4d。
- evidence/live-20260928/：三次真实尝试的请求和选取后的响应字段。
- evidence/json-mode-20260928/：一次真实参数探测（超时）。
- evidence/replay-*.json：首个真实响应的离线适配，不是新响应。
- evidence/offline-*.json：独立人工输入和 localhost 服务测试。

manifest 的 at 为 UTC，所以日期显示9月27日；运行发生在北京时间9月28日。不把 author 执行与 AI 执行混为一谈：本轮由 Codex 在作者授权下运行，未宣称作者已经亲手学习或人工审校。

## 当前边界

没有接入博客自动发布、数据库或机器人控制。分类结果不能直接当作可信指令执行。需要人工检查内容含义；默认严格拒绝，异常时不发布不合格对象。本例不是完整 JSON Schema 引擎，也没有实现自动修复或长期对话记忆。
