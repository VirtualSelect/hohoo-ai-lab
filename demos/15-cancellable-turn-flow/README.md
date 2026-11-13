# 把连接取消接入完整轮提交

2026-11-13质量修订中的离线整合参考；不是新线上服务或真实模型测评。复用Demo 14的`TurnFlow`、Demo 11的预算/日志，以及Demo 10经JSON词法修订后的SSE解析器（7fb37e8）。原30次连接测量和Demo 14的16个场景不改。

`CancellationLease`属于一轮请求：先设置不可复位的取消标记，再取出自己拥有的Socket，在同步区外关闭。取消早于注册时，迟到的Socket立即关闭；每轮使用新lease，旧lease不能操作下一轮的连接。

`SocketFixtureReplyClient`只连接`127.0.0.1`，显式无代理。它只处理受控HTTP/1.1、Connection: close与SSE，不支持TLS、chunked、鉴权、跳转、重连或任意提供商协议，不能直接当作公网HTTP客户端。

## 执行

需要Java 8、Python 3、原有Gson 2.10.1，没有新依赖或模型密钥。

```sh
python demos/15-cancellable-turn-flow/run.py --java-home /path/to/jdk8 --gson /path/to/gson-2.10.1.jar --out demos/15-cancellable-turn-flow/out/my-run
python demos/15-cancellable-turn-flow/audit.py demos/15-cancellable-turn-flow/out/my-run --verify-sources
```

输出目录必须不存在。运行器先复跑原解析器的离线词法回归，再执行八种受控交接。查看results.json、各场景完整轮日志和manifest；首次运行的空字段序列化问题保留在out/local-v1，不能将它当作通过结果。

## 最值得对照的场景

- `flag-only`：只标记取消，服务端继续门保持关闭。100ms观察窗口内读取者未退出；随后显式清理关闭Socket，读取者才在服务端放行之前退出。这是一个受控观察，不是延迟分布。
- `close-reading`：首段到达后撤销资格并关闭所拥有连接，独立finally信号和join确认线程退出；服务端放行后仍执行剩余一步，最终记录两步固定工作。
- `before-register`、`after-register`：取消与连接公布交错时，实际创建的Socket最终关闭，服务端没有收到请求。
- `before-commit`：完整回答已经解析，提交检查前取消后不新增问答。
- `old-lease`：A提交后开始B，取消旧A凭据不关闭B连接；B继续完成。

每个场景预置一轮历史。预置记录、实际收到的请求、新增完整轮分别核对，不相加当成功率。

## 边界

只有拥有者线程读写日志；没有叠加另一个Session内存历史。Socket关闭、读取退出、提交资格及远端工作是不同观察。取消与最后检查后的写盘仍可能竞争，不能撤销已提交记录；关闭失败也必须向调用者报告。

这里验证了连接注册前后、阻塞读取与完整回答后的受控交错；没有测试真实DNS、阻塞建连、TLS握手、写阻塞、连接池、多用户或计费。未扩展成硬实时总期限，也不据客户端关闭推断远端确认取消。
