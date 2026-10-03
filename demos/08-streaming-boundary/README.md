# A6 / 流式文本与完整提交

Java 8 + Gson 2.10.1，22 个冻结案例通过真实回环 HTTP 连接发送 SSE。客户端读取上限用于确定性地切开 UTF-8，不称为 TCP 包边界。只支持文本、stop 与 [DONE]；拒绝工具增量，未实测 Agnes 在线流式协议。

```sh
python demos/08-streaming-boundary/run.py --out demos/08-streaming-boundary/evidence/MY-RUN
python demos/08-streaming-boundary/audit.py demos/08-streaming-boundary/evidence/MY-RUN
```

设置 JAVA_HOME 到 Java 8，Maven 不在 PATH 时传 --maven。失败与取消仅保留预览、不追加历史；单请求教学模型不提供并发会话锁。读超时与字符处理期限不是严格可抢占的总墙钟截止时间。不自动重连、不调用付费模型、不需要密钥。

实测记录：`evidence/20261003-r2`。最初运行器等待未固定的 dependency 插件解析，未开始 HTTP 案例即停止；改为沿用已有固定版本 exec 插件后完成。可加 `--offline` 使用本机缓存。
