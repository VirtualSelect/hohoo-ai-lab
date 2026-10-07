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

## 2026-10-03 / 可靠性与注意力机制

- [A2–A4](demos/06-reliable-workflows/README.md)：并发提交、工具执行边界、BM25引用链路；33项实际Java8验收，14题自有合成语料。12道可回答题中11题进入top1/top3，2道不可回答题中1题仍有命中，不宣称端到端RAG正确率。
- [L2–L4](experiments/05-attention-lab/README.md)：因果掩码、KV缓存、滑动窗口位置；3种固定种子、48组存档数组、27项误差复核。未训练教学网络，零在线请求，不替代L1v3待授权的模型对照。

## 新一轮：流式提交与历史编辑

- [A5 引用与声明契约](demos/07-grounded-claims/README.md)：22 个闭合配置案例。
- [A6 SSE 文本流与历史提交](demos/08-streaming-boundary/README.md)：22 个回环 HTTP 案例；6 个完成、16 个拒绝或取消，不调用模型。
- [L6 最长相同前缀复用](experiments/07-prefix-reuse/README.md)：27 个条件、81 个原始数组；局部裁剪必须同步位置编号。

## 请求归属与缓存预算

- [A7 流式会话所有权](demos/09-stream-session/README.md)：18种固定线程交错；旧回调不能更新当前会话。
- [L7 字节预算缓存](experiments/08-cache-budget/README.md)：324请求、648数组，冷缓存开始计数，保留零收益的缓存抖动案例。

## 连接取消与缓存准入

- [A8 真实连接取消](demos/10-transport-cancellation)：30次本地HTTP对照，分别记录会话、工作线程与服务端工作。
- [L8 缓存准入和实测查询耗时](experiments/09-cache-admission)：4,860次查询，保留计算减少但仍慢于重算的结果。

## 2026-10-07 / 请求边界与注意力开销

- [有边界的助手组件](demos/11-bounded-assistant/README.md)：完整日志与请求窗口分离、总期限重试、异常退出后的完整轮恢复；22个边界案例、3个halt进程，另有裁剪→提交→重启→扩窗的连续实验。全部使用本地固定材料，不调用付费模型。
- [注意力成本实验](experiments/10-attention-costs/README.md)：拆分909次缓存查询开销；对照在线分块Softmax与稠密参考的误差和558次计时；保存36组KV int8量化对照与原始数组。保留“计算更少但仍更慢”和离群值导致大误差的结果。

它们是可组合的教学组件，不宣称已经交付完整在线助手、GPU内核或真实模型质量评测。
