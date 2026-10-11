# KV打包、近期残差与分块执行

三篇机制实验共用18份固定合成输入（3种子×2长度×3注意力分布）。不是训练模型质量评测，零在线请求。

1. **分组与实际4-bit打包**：72个对照，K按每通道的Token块量化，V按每Token的特征组量化；保存uint8双nibble和float32 scale。
2. **近期float32残差**：54个对照，保留0/16/64个最近Token；早期证据集中时，保留最近部分收益很小。
3. **分块反量化**：18个数值等价对照，810条原始计时。比较浮点缓存、全量解码、逐块解码。当前CPU负结果保留，不能视作FlashAttention实现或GPU结论。

```powershell
python -m venv .venv
$py = '.\.venv\Scripts\python.exe'
& $py -m pip install -r experiments/12-packed-kv/requirements.txt
& $py -m unittest discover -s experiments/12-packed-kv -p "test_*.py"
& $py experiments/12-packed-kv/run.py --out experiments/12-packed-kv/target/my-run
& $py experiments/12-packed-kv/audit.py experiments/12-packed-kv/target/my-run
```

已验证Windows/Python3.12.14/NumPy2.2.6。新输出目录必须不存在。预期4项测试、144个对照、810条计时和独立审计通过。审计以逐nibble解码及einsum重算，不导入实现的解码/注意力函数。

归档 `arrays.npz` 是无损压缩原始输入、整数码、scale、形状和输出；压缩归档大小不等于运行缓存大小。payload计入codes/scales/shape，排除Python对象、allocator、预打包副本和临时工作区。分块工作区是数组尺寸计算，不是实测进程RSS峰值。计时只含CPU反量化+注意力，预打包和初始化排除，轮换执行顺序，保留预热后的全部观测；未绑核、无显著性检验，不能因本机计时给出普适速度结论。

参照KIVI（https://arxiv.org/abs/2402.02750）的粒度/残差问题与FlashAttention（https://arxiv.org/abs/2205.14135）的分块思路；此处对称量化、合成输入和NumPy执行不复现两篇论文完整算法或性能。
源版本与环境见manifest；文章与代码由AI辅助整理，尚无真实模型质量结果。
