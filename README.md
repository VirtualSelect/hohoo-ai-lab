# hohoo-ai-lab
Learning, experimenting and building with AI — Java, LLMs, RAG, Agents and beyond.

## 后续模型约定 · 2026-09-29

后续 Java LLM 新示例与新实验统一使用 `agnes-3.0-flash`，不再以 `agnes-2.5-flash` 发起新的研究调用。

历史文章、原始响应、实验清单和固定版本保留实际使用的模型型号。已有冻结协议（包括 L1v2）切换模型时，先更新并重新冻结协议与请求清单，新结果单独归档，不与旧型号结果混作同一组对照。

## Demos

- `demos/01-first-llm-call`：用 Java 8 标准库完成第一次 LLM HTTP 调用，打印原始 JSON。
- `demos/02-parse-llm-response`：用 Gson 构造请求并解析 `choices[0].message.content` 和 token 用量。
- `demos/03-multi-turn-chat`：在内存中维护 `messages` 历史，实现最小多轮对话。

- `demos/04-structured-output`：严格验收文章分类结果，包含真实失败记录、受限格式适配与35项离线检查。

## Research experiments

- [L1：上下文位置试验](experiments/01-context-position/README.md)：冻结材料、92项离线断言、29次尝试和提前停止记录；不把请求失败计作模型答错。

- [L1v2：成组对照与节流](experiments/02-context-position-paced/README.md)：60/240行材料、80项离线检查；Agnes 3.0完成24次实测，6组成对照，18个编号正确与6个正确拒答。固定材料全对不等于位置无关。

- [A1：事务式对话历史](demos/05-transactional-chat/README.md)：41项离线检查，整轮提交与UTF-8请求预算，无新增API请求。
- [TypeScript 输出边界](experiments/03-typescript-boundary/README.md)：32组Java/TypeScript对照，37项测试，历史响应离线重放。

## 下一轮：相似干扰与上下文位置（离线就绪）

[L1v3](experiments/04-context-position-similar/README.md)：8题×两种长度×两种相似度×四条件，128项冻结计划。本机Java 8/Maven编译、2515项自检与60项独立测试通过，真实请求为0；没有新增模型结论。线上请求需单独确认费率与金额预算。
