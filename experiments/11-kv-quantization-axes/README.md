# K/V 量化轴的局部机制实验

```sh
python -m pip install -r experiments/11-kv-quantization-axes/requirements.txt
python -m unittest discover -s experiments/11-kv-quantization-axes -p 'test_*.py'
python experiments/11-kv-quantization-axes/run.py --out experiments/11-kv-quantization-axes/target/my-run
python experiments/11-kv-quantization-axes/audit.py experiments/11-kv-quantization-axes/target/my-run
```

`--out`必填，输出目录必须尚不存在；已有目录（即使为空）会拒绝，不覆盖原始证据。当前6项单测包含缺失参数、已有空目录和已有证据的保护检查。2026-11-12复核发现旧README的无参数命令会默认覆盖`evidence`，当前本地修订已去除此默认行为；已发布固定提交仍是旧实现，复现时必须显式指定新的输出目录。

核对原始归档时不需要重新生成：先检出文章固定的历史提交，再运行`python experiments/11-kv-quantization-axes/audit.py`。旧manifest的源码哈希属于旧版本；当前修订后的源码不会匹配，不要改写旧manifest来强行通过。用当前源码重跑应审计上方的新目录，原始记录保持不变。

固定三随机种子、两种序列长度、三种输入条件，每种输入分别量化 K、V、K/V；比较全张量、逐 token、K 逐通道 + V 逐 token 三种规则，共 162 个输出。保存输入、整数码、float32 缩放因子、参考输出和各量化输出，可独立重算。

协议先于运行提交。人工设置的离群维度是可解释的机制对照，不能当作真实模型的统计分布。这里没有训练权重、在线请求或性能测量；payload_bytes 只统计归档的缓存数值数组，排除容器、工作区和解量化临时数组。代码受 KIVI 的非对称量化动机启发，但不是 KIVI 实现。AI 辅助实现和整理，结果以随附证据为准。
