# TypeScript 的类型边界：同一批输入在 Java 与 TypeScript 中如何验收

独立实验目录，不迁移博客或Java主线。TypeScript只作为开发依赖，运行代码只使用原生语言能力。Node >=22.18可运行本例可擦除类型语法；仍需单独执行typecheck，直接运行.ts不是类型检查。

四组入口：
- unsafeCast：故意保留的反例，仅JSON.parse后类型断言。
- validateClassification：从unknown验收已解码对象，无法恢复重复键信息。
- parseClassification：长度/语法检查、原始JSON根对象重复键扫描，然后验收同一业务契约。
- 既有Java Classification.parse：原实现，不改写旧代码或旧证据。

根字段扫描器不是通用JSON Schema/JSON解析器，仅处理本例扁平对象的重复属性；语法由JSON.parse先验证，嵌套业务对象不支持。未知字段、错误枚举、控制字符、重复标签、超长输出默认拒绝。标签长度按Unicode码点；ASCII首尾空白规则与既有Java String.trim对齐。

```powershell
# 从仓库根目录
mvn -q -f demos/04-structured-output/pom.xml compile
cd experiments/03-typescript-boundary
npm ci
npm run typecheck
npm test
npm run experiment -- evidence/my-run
```

Java探针依赖JAVA_HOME和Maven默认本地仓库里的Gson2.10.1；若使用自定义Maven仓库，通过进程环境变量GSON_JAR指定现有jar文件，不复制依赖或读取凭证。产物保留输入、四路接受结果、版本和哈希。模拟输入不代表模型输出分布，全部通过也不能证明语义正确或端到端安全。历史真实响应只离线重放，不重新调用旧模型。

[配套文章](https://huhohoo.com/docs/ai-apps/typescript-output-boundary)

## 2026-09-30 的结果

32个刻意构造的边界样本：类型断言误接收22个无效输入，仅对象校验误接收4个，原始字符串校验与Java判定32/32一致。不是模型准确率。37项单元测试与类型检查通过。历史真实围栏响应严格拒绝、显式适配后接受；本轮网络请求为0。记录在 evidence/20260930-boundary。

运行 node audit.mjs evidence/20260930-boundary 复核输入、判定及哈希。旧Java文件/历史JSON可能因Git的autocrlf改变换行；审计只允许LF/CRLF归一化，不忽略其他字节差异。
