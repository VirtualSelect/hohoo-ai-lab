# L8 · 缓存准入与实测请求耗时

复用 L7 的完整前缀检查点，比较不缓存 / 保存全部 / 只保存最长三个策略。不训练模型、不调用在线模型。

`python -m unittest discover -s . -p test_cache.py`
`python run.py --out evidence/your-run`
`python audit.py evidence/your-run`

3种子 × 3预算 × 3访问轨迹 × 3策略。每配置一次预热（不计入结果），5次冷缓存测量；每轮随机交错配置。计时只包住 query（包含哈希、拷贝、计算和准入），不含模型创建、参考计算、结果写盘；已有签名函数中的源文件读取计入查询。不是在线端到端延迟或 GPU benchmark。

## 2026-10-05 实测归档

4,860次正式查询、972次预热、1,944个数组；最大误差4.163336342344337e-16。16KiB三scope最长准入减少计算，但耗时仍高于无缓存。

[配套文章](https://huhohoo.com/docs/llm/prefix-cache-admission)。图表脚本为 plot.py；原始记录和独立审计见 evidence 目录。
