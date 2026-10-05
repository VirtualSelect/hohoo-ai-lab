# L8 · 缓存准入与实测请求耗时

复用 L7 的完整前缀检查点，比较不缓存 / 保存全部 / 只保存最长三个策略。不训练模型、不调用在线模型。

`python -m unittest discover -s . -p test_cache.py`
`python run.py --out evidence/your-run`
`python audit.py evidence/your-run`

3种子 × 3预算 × 3访问轨迹 × 3策略。每配置一次预热（不计入结果），5次冷缓存测量；每轮随机交错配置。计时只包住 query（包含哈希、拷贝、计算和准入），不含模型创建、参考计算、文件读写。不是在线端到端延迟或 GPU benchmark。
