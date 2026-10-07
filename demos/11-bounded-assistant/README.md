# 有边界的 Java 助手组件

ContextBudget 精确约束 JSON UTF-8 请求字节；RetryBudget 对显式可重试操作设总期限；TurnJournal 在单写者文件中只记录完整问答。没有接在线模型，不是完整生产助手。本地fixture结果不描述为模型能力。

需要 Java8、Python3 与 Gson2.10.1。优先复用现有 Maven 缓存。
~~~sh
python demos/11-bounded-assistant/run.py --java-home /path/to/jdk8 --gson /path/to/gson-2.10.1.jar --out outputs/bounded-assistant
python demos/11-bounded-assistant/audit.py outputs/bounded-assistant
~~~
替换成自己的JDK/Gson路径，输出目录必须不存在。results.json记录决策，crashes.json记录真正子进程非正常退出；manifest保存源码与文件哈希。

消息裁剪不改持久历史；重试不把超时当服务器没执行；日志限4MiB并拒绝第二写者；完整损坏行拒绝打开，未完成尾行截断。force(true)依赖本地存储，不等于断电/网络文件系统证明，完成日志不保证外部副作用恰好一次。同步调用只在同一写者线程使用；不实现服务器并发框架。

## 完整历史与请求窗口分离

额外集成场景把旧事实裁出请求，提交一个本地固定回答，再关闭重开日志并扩大窗口。旧事实会重新进入请求，不说明模型在缺少事实时仍会记住它。

~~~sh
python demos/11-bounded-assistant/projection.py --java-home /path/to/jdk8 --gson /path/to/gson-2.10.1.jar --out outputs/projection
python demos/11-bounded-assistant/projection.py --audit --out outputs/projection
~~~
