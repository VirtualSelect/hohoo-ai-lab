# 03 多轮对话

这个 Demo 展示多轮对话最核心的机制：每次请求都把当前会话的 `messages` 历史完整发送给模型。

本示例仍然保持教学用的最小范围：

- Java 8
- Gson `2.10.1`
- `HttpURLConnection`
- 单进程内存历史，重启后清空
- 不做流式响应
- 不做自动重试
- 不做历史压缩或持久化
- 不保存、不输出 `reasoning_content`

## 多轮是怎么工作的

第一轮你输入：

```text
我正在学习 Java
```

程序会发送：

```json
[
  {"role": "user", "content": "我正在学习 Java"}
]
```

模型成功回答后，程序把 assistant 回答加入内存历史。第二轮你输入：

```text
我正在学习什么？
```

程序会发送完整历史：

```json
[
  {"role": "user", "content": "我正在学习 Java"},
  {"role": "assistant", "content": "...上一轮回答..."},
  {"role": "user", "content": "我正在学习什么？"}
]
```

这就是第二轮能“记得前文”的原因。注意，模型上下文有长度上限；本 Demo 不做自动压缩、截断或持久化。

## 在 IDEA 中运行

这个 Demo 是一个独立 Maven 小项目。

1. 在 IDEA 中右键 `demos/03-multi-turn-chat/pom.xml`
2. 选择 `Add as Maven Project` 或 `Import Maven Project`
3. 确认 Maven 使用 JDK 8
4. 打开 `MultiTurnChat.java`
5. 创建 Java Application 或 Maven 运行配置
6. `Use classpath of module` 选择 `multi-turn-chat`
7. 在 Environment variables 中配置 `AGNES_API_KEY`
8. 运行 `com.hohoo.ailab.chat.MultiTurnChat`
9. 在控制台输入问题并回车；输入 `exit` 后回车退出

如果你是从外部 PowerShell 设置的环境变量，已经打开的 IDEA 不会自动继承。IDEA 里运行时，建议直接在运行配置的 Environment variables 中设置 `AGNES_API_KEY`。

## 建议验证

第一轮输入：

```text
我正在学习 Java
```

等模型回答后，第二轮输入：

```text
我正在学习什么？
```

如果历史传递成功，第二轮回答应该能结合上一轮内容，说出你正在学习 Java。

## 在 PowerShell 中运行

进入本 Demo 目录：

```powershell
cd E:\huyuhao\IDEA\Project\hohoo-ai-lab\demos\03-multi-turn-chat
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

## 错误与历史处理

如果本轮出现网络错误、非 2xx 状态码、JSON 结构错误或超时，程序会撤回本轮 user 消息，保留之前成功的历史，然后允许你继续输入下一轮。

如果输入空白，程序会提示重新输入，不会加入历史，也不会发送请求。

如果输入 `exit` 或控制台 EOF，程序会退出，不会发送请求。

如果响应中的 `finish_reason` 不是 `stop`，程序会显式提示模型可能没有完整停止在自然结尾。

Token 用量只在接口提供时显示；如果缺失，不会伪造为 0。

## 本地验证状态

本 Demo 已验证：

- 使用 Java `1.8.0_171`、本地已缓存的 `gson-2.10.1.jar` 通过 `javac -source 1.8 -target 1.8` 编译
- fixture 自检验证 user/assistant 历史顺序
- fixture 自检验证失败撤回本轮 user 消息且不影响旧历史
- fixture 自检验证第二次请求 JSON 带完整历史并正确处理转义
- fixture 自检验证空输入和 `exit` 不加入历史

本轮没有配置 `AGNES_API_KEY`，因此没有进行真实 API 调用；fixture 自检只验证本地多轮历史和 JSON 构造逻辑，不代表真实联调成功。
