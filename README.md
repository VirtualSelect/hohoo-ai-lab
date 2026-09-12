# hohoo-ai-lab
Learning, experimenting and building with AI — Java, LLMs, RAG, Agents and beyond.

## Demos

- `demos/01-first-llm-call`：用 Java 8 标准库完成第一次 LLM HTTP 调用，打印原始 JSON。
- `demos/02-parse-llm-response`：用 Gson 构造请求并解析 `choices[0].message.content` 和 token 用量。
- `demos/03-multi-turn-chat`：在内存中维护 `messages` 历史，实现最小多轮对话。
