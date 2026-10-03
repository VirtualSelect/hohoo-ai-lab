# A2–A4 / 可靠应用边界

三个可运行Java 8工程案例：VersionedSession 并发提交、ReadOnlyTools 工具边界、Retrieval 本地BM25与引用验收。共享一个小Maven项目，Gson沿用已有2.10.1，无新服务。

```sh
cd demos/06-reliable-workflows
python run.py --out evidence/NEW-RUN
```

输出目录必须独立，不覆盖历史结果。Suite实际启动线程并注入超时工具，读取仓库内原创合成语料，记录所有断言与14题排名。无在线模型调用，不读取API Key。

会话锁仅保护快照与提交；回复生成在外部完成。STALE需调用方展示冲突并选择重试，不能悄悄把旧问题重发。回执有容量，跨进程、重启或回执淘汰后不保证幂等。

工具不是沙箱：只允许本地登记的只读函数。取消Future不能杀死不配合的线程，测试故意展示超时后仍发生本地计数更新。生产应隔离非可信代码，不能开放shell或任意URL。预算按成功提交给执行器的任务计数，INVALID/UNKNOWN/BUSY不消耗。

检索仅英文规则分词；12道有答案题和2道无答案题分开统计。引用存在不等于引用支持答案；本例不调用生成模型、不计算答案正确率。`pack`预算按Java UTF-16字符计，不是token计数。

`run.py`需要本机Java8/Maven（可用`--maven`指定路径），自动保存源码指纹和独立审计。直接运行Suite只导出结果，不单独补造来源清单。
