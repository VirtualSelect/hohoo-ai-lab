# K/V 量化轴的局部机制实验

```sh
python -m pip install -r experiments/11-kv-quantization-axes/requirements.txt
python -m unittest discover -s experiments/11-kv-quantization-axes -p 'test_*.py'
python experiments/11-kv-quantization-axes/run.py
python experiments/11-kv-quantization-axes/audit.py
```

固定三随机种子、两种序列长度、三种输入条件，每种输入分别量化 K、V、K/V；比较全张量、逐 token、K 逐通道 + V 逐 token 三种规则，共 162 个输出。保存输入、整数码、float32 缩放因子、参考输出和各量化输出，可独立重算。

协议先于运行提交。人工设置的离群维度是可解释的机制对照，不能当作真实模型的统计分布。这里没有训练权重、在线请求或性能测量；payload_bytes 只统计归档的缓存数值数组，排除容器、工作区和解量化临时数组。代码受 KIVI 的非对称量化动机启发，但不是 KIVI 实现。AI 辅助实现和整理，结果以随附证据为准。
