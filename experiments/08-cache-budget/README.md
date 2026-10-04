# L7 / 按真实数组字节限制前缀检查点

固定双层、16维、float64 未训练模型。每4个 Token 可以缓存完整前缀检查点，LRU 按 NumPy K/V 与绝对位置数组的 nbytes 控制持久驻留量；不同检查点不共享底层页，重复前缀重复收费。

3种子 × 3预算 × 3访问轨迹 × 12请求，全部从冷缓存开始。每个请求与完整重算比较，保存648个数组。统计整个轨迹的投影行数、命中、淘汰和显式 checkpoint 复制字节。

```text
python -m unittest discover -s experiments/08-cache-budget -p test_cache.py
python experiments/08-cache-budget/run.py --out experiments/08-cache-budget/evidence/my-run
python experiments/08-cache-budget/audit.py experiments/08-cache-budget/evidence/my-run
```

这不是 PagedAttention 或生产 KV 引擎。不含 Python 对象、token key、活跃请求临时缓存、NumPy内部复制、模型权重；驻留上限不是RSS上限。没有计时，不用算术计数推断加速倍数。scope 字符串不是身份认证。
