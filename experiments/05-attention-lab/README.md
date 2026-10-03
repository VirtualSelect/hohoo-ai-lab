# L2–L4 / Attention 机制实验台

三个相连的本地数值实验：因果掩码防止未来信息泄漏；完整前缀与 KV Cache 的逐位置等价；滑动窗口、位置编号与上层缓存历史的区别。

模型是未训练的双层单头 attention + tanh 残差，16维、32个离散ID、float64；无 tokenizer、FFN、归一化、语言任务或 Agnes 调用。随机权重仅用于构造非退化计算，不是模型效果或性能基准。固定种子7/19/41，全部矩阵与反例归档。

```sh
python -m pip install -r experiments/05-attention-lab/requirements.txt
python -m unittest discover -s experiments/05-attention-lab -p "test_*.py"
python experiments/05-attention-lab/run.py --out experiments/05-attention-lab/evidence/NEW-RUN
python experiments/05-attention-lab/audit.py experiments/05-attention-lab/evidence/NEW-RUN
```

从仓库根目录运行；输出必须为新目录。`attention.py` 的 wrong_mask/reset_position 只用于明确标记的故障对照，默认关闭。计数是投影行数、实际分配的分数元素数与K/V数组有效负载字节数，不是硬件速度或显存峰值。

设计参照：[Attention Is All You Need](https://arxiv.org/abs/1706.03762)、[Hugging Face caching explanation](https://huggingface.co/docs/transformers/main/en/cache_explanation)。这些来源说明通用机制，本文数值来自独立本地实现。
