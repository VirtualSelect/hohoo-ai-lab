# A7 / 流式回调的会话所有权

同一个进程内，取消或替换请求不能阻止已经排队的回调。每个请求持有不可伪造的 owner/ticket 和 epoch；预览、完成、错误都必须核对当前所有者。锁内只更新短状态，不读取网络。

9 种固定交错 × naive/owned 两种策略。真实 Java 工作线程 + 主线程，以 CountDownLatch 固定先后顺序，不用随机 sleep 假装覆盖竞态。输入是合成内存 SSE，解析器按原样复制自 A6，不重新声称测量网络。

```text
python run.py --out evidence/my-run --maven /path/to/mvn --offline
python audit.py evidence/my-run
```

`complete` 只接受调用方已经验证完整的文本；本例调用方必须通过 A6 stop + DONE 检查。8 对历史、8 个已用 ID 的保留窗；被拒绝的重复 ID 不发起请求。清空保留 ID 窗，进程重启不保留。取消是本地失效，不保证停止计费、关闭网络或跨进程幂等。naive 仅为负对照。
