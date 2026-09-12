# 01 第一次调用大语言模型

这个 Demo 用 Java 8 标准库直接调用 Agnes Chat Completions API，目标是先把一次 LLM 请求跑通。

本示例刻意保持最小化：

- 不使用 Maven、Gradle、SDK 或第三方依赖
- 不解析 JSON，只打印接口返回的原始 JSON
- 不做多轮对话、不做流式响应、不接入 Spring AI
- API Key 只从环境变量 `AGNES_API_KEY` 读取，不写入代码、不打印密钥

## 请求内容

接口地址：

```text
https://apihub.agnes-ai.com/v1/chat/completions
```

请求模型：

```text
agnes-2.5-flash
```

请求消息：

```json
{
  "role": "user",
  "content": "请用两句话解释什么是大语言模型。"
}
```

源码中的 JSON 是用 Java 字符串拼出来的。因为 Java 字符串本身使用双引号，所以 JSON 里的双引号需要写成 `\"`，这就是你在代码里看到转义符的原因。

## 在 PowerShell 中运行

进入本 Demo 目录：

```powershell
cd E:\huyuhao\IDEA\Project\hohoo-ai-lab\demos\01-first-llm-call
```

编译：

```powershell
New-Item -ItemType Directory -Force -Path out
javac -encoding UTF-8 -d out src\main\java\com\hohoo\ailab\firstcall\FirstLlmCall.java
```

运行：

```powershell
java -cp out com.hohoo.ailab.firstcall.FirstLlmCall
```

如果没有配置 `AGNES_API_KEY`，程序会清晰报错并退出，不会发送请求。

## 配置环境变量

推荐只在当前 PowerShell 会话里配置，避免把密钥写进命令历史或仓库文件。可以用交互方式输入：

```powershell
$agnesSecret = Read-Host "AGNES_API_KEY" -AsSecureString
$env:AGNES_API_KEY = [System.Net.NetworkCredential]::new('', $agnesSecret).Password
```

环境变量会被同一个终端之后启动的子进程继承。因此请先在当前 PowerShell 配置 `AGNES_API_KEY`，再在同一个终端运行 `java` 命令。

已经打开的 IDEA 不会自动继承你在外部 PowerShell 后设置的环境变量。IDEA 中运行时，请在运行配置的 Environment variables 里直接设置 `AGNES_API_KEY`；或者先在 PowerShell 配好环境变量，再从这个 PowerShell 启动一个新的 IDEA 进程。

不要把真实密钥写进源码、README、截图、提交记录或聊天消息。

## 在 IDEA 中运行

1. 打开 `FirstLlmCall.java`
2. 创建 Java Application 运行配置
3. Main class 选择 `com.hohoo.ailab.firstcall.FirstLlmCall`
4. 如果 IDEA 没有识别源码目录，把 `src/main/java` 标记为 Sources Root
5. 如果项目还没有 Java 模块，先给当前 Demo 建一个普通 Java 模块，并选择 JDK 8
6. 在 Environment variables 中配置 `AGNES_API_KEY`
7. 运行后查看控制台输出的 HTTP 状态码和原始 JSON

## 错误排查

`缺少环境变量 AGNES_API_KEY`

说明程序没有拿到密钥。检查是否在同一个终端里配置后再运行，或者 IDEA 的运行配置是否填写了环境变量。

`HTTP 状态码: 401`

通常是密钥错误、密钥过期，或者请求头没有正确携带 `Authorization: Bearer ...`。

`HTTP 状态码: 4xx`

通常是请求参数、模型名、鉴权或额度问题。程序会打印接口返回的错误 JSON，排查时注意脱敏，不要公开密钥。

`HTTP 状态码: 5xx`

通常是服务端临时问题，可以稍后重试。

`请求失败: ...`

通常是网络、DNS、代理、TLS 或超时问题。当前示例设置了 10 秒连接超时和 30 秒读取超时。

## 本地验证状态

本 Demo 已验证：

- 使用 `javac 1.8.0_171` 编译通过
- 缺少 `AGNES_API_KEY` 时会报错并且不发送请求

如果当前环境中存在 `AGNES_API_KEY`，可以运行 `java -cp out com.hohoo.ailab.firstcall.FirstLlmCall` 做一次真实 API 验证。成功时会看到 `HTTP 状态码: 200` 和原始 JSON 响应。
