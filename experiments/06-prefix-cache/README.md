# L5 / 前缀缓存失效

复用上一轮未训练注意力模型。固定3个种子×6种前缀/参数/窗口/作用域条件，比较无条件复用和带键校验的复用，与新鲜计算的后缀表示比较。

```sh
python -m pip install -r experiments/05-attention-lab/requirements.txt
python -m unittest discover -s experiments/06-prefix-cache -p "test_*.py"
python experiments/06-prefix-cache/run.py --out experiments/06-prefix-cache/evidence/NEW-RUN
python experiments/06-prefix-cache/audit.py experiments/06-prefix-cache/evidence/NEW-RUN
```

scope由调用方提供，仅用于缓存分区，不是登录或授权系统。整份权重哈希用于小模型的可检查性，不建议生产每次对大模型做相同计算；生产应使用可信且不可变的模型修订ID。本例无Tokenizer，比较精确Token ID；不报告缓存命中延迟或GPU速度。
