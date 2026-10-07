# 开销、分块 Softmax 与 KV 量化

三篇配套机制实验共用可读的 NumPy 内核。不是预训练模型、FlashAttention GPU 实现或 KIVI 复现。仅比较固定合成数组，无模型 API。

1. profile：八Token前缀永远命中，原始源码/权重签名与冻结只读模型签名对照，单独记录签名、查找、复制、计算及未归属开销。101轮×3种子，随机交错；不是前轮冷缓存混合负载。
2. tile：8行query、64/256/1024行KV、维度32。在线Softmax累加max/sum/output；错误对照将每块Softmax平均。31轮，预热5次。
3. quant：tensor/row对称int8，含scale开销；正常/40倍单元素扰动；重新反量化成float64计算，所以不节省计算峰值或证明模型质量。

```sh
python -m pip install numpy==2.2.6
python -m unittest discover -s experiments/10-attention-costs -p "test_*.py"
python experiments/10-attention-costs/run.py --out outputs/attention-costs
python experiments/10-attention-costs/audit.py outputs/attention-costs
```

输出目录需不存在。manifest记录运行环境、线程环境、源文件哈希、原始数组及计时文件；审计独立重算dense参考和反量化结果。计时不承诺跨机器一致，score缓冲估算不是进程RSS峰值。冻结模型只读标志是本例约束，不是对恶意调用者的安全隔离；换权重必须创建新实例/签名。
