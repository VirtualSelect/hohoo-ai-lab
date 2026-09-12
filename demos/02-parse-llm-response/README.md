# 02 解析大语言模型响应

这个 Demo 在第一课“能发出请求”的基础上，加入一个成熟 JSON 库 Gson，用来构造请求 JSON，并从接口响应里提取模型回答和 token 用量。

本示例仍然保持单次请求：

- 不做多轮对话
- 不做流式响应
- 不接入 Spring AI
- 不输出 `reasoning_content`
- API Key 只从环境变量 `AGNES_API_KEY` 读取

## 为什么开始引入 JSON 库

第一课用 Java 字符串手写 JSON，是为了看清楚 HTTP 请求本身。但真实项目里不建议长期这么做，因为 JSON 里会遇到转义、嵌套对象、数组、中文、换行符等细节。

JDK 8 没有通用内置 JSON 解析器，所以本 Demo 使用 Gson：

```xml
<dependency>
  <groupId>com.google.code.gson</groupId>
  <artifactId>gson</artifactId>
  <version>2.10.1</version>
</dependency>
```

Gson 官方 README 的最低 Java 版本说明中，`2.9.0` 到 `2.11.0` 支持 Java 7，因此 `2.10.1` 可以用于 Java 8。

## 解析目标

接口返回是一个 JSON 字符串。我们关心的路径是：

```text
String -> JSON 对象 -> choices 数组第 0 项 -> message -> content
```

也就是：

```text
choices[0].message.content
```

同时，如果响应提供了 `usage.total_tokens`，程序会显示 token 用量；如果接口没有提供 usage，程序会明确显示“未提供”，不会伪造为 0。

## 在 IDEA 中运行

这个 Demo 是一个独立 Maven 小项目。

1. 在 IDEA 中右键 `demos/02-parse-llm-response/pom.xml`
2. 选择 `Add as Maven Project` 或 `Import Maven Project`
3. 确认 Maven 使用 JDK 8
4. 打开 `ParseLlmResponse.java`
5. 创建 Java Application 或 Maven 运行配置
6. 在 Environment variables 中配置 `AGNES_API_KEY`
7. 运行 `com.hohoo.ailab.response.ParseLlmResponse`
8. 在 IDEA 控制台看到 `请输入问题，然后按回车：` 后，输入你的问题并回车

如果你是从外部 PowerShell 设置的环境变量，已经打开的 IDEA 不会自动继承。IDEA 里运行时，建议直接在运行配置的 Environment variables 中设置 `AGNES_API_KEY`。

## 在 PowerShell 中运行

进入本 Demo 目录：

```powershell
cd E:\huyuhao\IDEA\Project\hohoo-ai-lab\demos\02-parse-llm-response
```

离线 fixture 自检，不需要 API Key，也不会发送请求：

```powershell
mvn -q compile exec:java '-Dexec.args=--self-test'
```

真实调用前，先在当前 PowerShell 中配置环境变量：

```powershell
$agnesSecret = Read-Host "AGNES_API_KEY" -AsSecureString
$env:AGNES_API_KEY = [System.Net.NetworkCredential]::new('', $agnesSecret).Password
```

然后运行：

```powershell
mvn -q compile exec:java
```

看到 `请输入问题，然后按回车：` 后，输入你的问题并回车。程序只读取这一行，并发起一次请求。

## 输出说明

成功时会输出：

```text
HTTP 状态码: 200
模型回答:
...
Token 用量:
  prompt_tokens: ...
  completion_tokens: ...
  total_tokens: ...
```

如果没有配置 `AGNES_API_KEY`，程序会清晰报错并退出，不会发送请求。

如果没有输入问题、直接结束输入，或者只输入空白字符，程序会清晰报错并退出，不会发送请求。

如果出现 `Read timed out` 或类似超时提示，表示程序在等待网络连接或响应数据时超过了设置的上限。当前连接超时上限是 10 秒，读取等待上限是 90 秒；这里的读取等待不是“整个请求的绝对截止时间”，而是 socket 等待读取数据的超时设置。超时可能来自网络慢、代理慢或服务端响应慢，不能只凭这个错误判断服务端是否已经处理了请求。程序不会自动重试；如果你手动重试，请注意可能再次产生调用计费。

如果响应结构异常，例如缺少 `choices`、`choices` 为空、`message.content` 不是字符串，程序会显示明确的解析错误。

## 本地验证状态

本 Demo 已验证：

- 使用 Java `1.8.0_171`、本地已缓存的 `gson-2.10.1.jar` 通过 `javac -source 1.8 -target 1.8` 编译
- fixture 自检可以提取 `choices[0].message.content`
- fixture 自检可以提取 `usage.prompt_tokens`、`usage.completion_tokens`、`usage.total_tokens`
- fixture 自检可以识别缺失或空 `choices` 并报错
- fixture 自检可以证明包含中文、双引号和反斜杠的问题经过 Gson 构造 JSON 后，解析回来的 `content` 与原输入一致

本机 Maven 3.6.3 可用，但当前环境执行 `mvn -q compile exec:java '-Dexec.args=--self-test'` 时停在已配置仓库的插件解析阶段，因此本轮没有把 Maven 执行结果记为通过。IDEA 或 Maven 在依赖解析完成后，可按上面的 Maven 步骤运行。

本轮没有配置 `AGNES_API_KEY`，因此没有进行真实 API 调用；fixture 自检只验证解析逻辑，不代表真实联调成功。
