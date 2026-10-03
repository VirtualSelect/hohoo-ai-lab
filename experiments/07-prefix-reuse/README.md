# L6 / 历史编辑后的前缀复用

复用未训练教学 Decoder；27 个条件运行，保存 81 个数组。比较完整重算、最长相同前缀裁剪、故意保留旧 next_position。短查询至少重算最后一个 Token；滑窗部分命中保守回退，跨作用域拒绝共享。不是生产 prefix cache、没有 tokenizer、GPU、速度或语言质量测量。

```sh
python -m unittest discover -s experiments/07-prefix-reuse -p "test_*.py"
python experiments/07-prefix-reuse/run.py --out experiments/07-prefix-reuse/evidence/MY-RUN
python experiments/07-prefix-reuse/audit.py experiments/07-prefix-reuse/evidence/MY-RUN
```

运行计数不包含预填充、整权重签名与查找成本。作用域字符串不是身份认证，局部 NumPy 复制也不是分布式缓存所有权。
