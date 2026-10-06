# 2026-10-07 · 历史实现边界修复

此目录记录代码修复及其离线回归，不是新的模型评测或新的研究结论。原始 evidence 目录不覆盖；复现历史文章需 checkout 文中固定提交。

## Java JSON 与 SSE

Demo08、09、10 在 Gson 2.10.1 结构解析前增加 JSON 词法检查：拒绝字符串内未转义的 U+0000–001F、非法转义、大小写错误的字面量和非法数字。原有嵌套深度、重复键、UTF-8 和 SSE 完成条件仍保留。合法转义、多行 SSE 的结构换行不被误拒。

三个示例各自的 `src/test/java/.../JsonLexicalRegression.java` 覆盖 53 项离线断言（包含一条经过真实 SSE reader 的多行字符串反例）。从工程根目录运行，将目录替换为另外两个 Demo 即可：

```powershell
mvn -f demos/08-streaming-boundary/pom.xml test-compile exec:exec '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-classpath %classpath com.hohoo.ailab.stream.JsonLexicalRegression'
```

这里不把有限用例宣称为完整 JSON 标准认证。`mvn test` 仅编译这套不依赖 JUnit 的主函数检查，需要上面的 `exec:exec` 才实际执行。

## 复现脚本与源码一致性

Demo10 默认从 PATH 查找 Maven；可传 `--maven` 或设置 `MAVEN_CMD`，仅显式 `--offline` 时离线运行。输出目录支持空格，必须使用新目录。

```powershell
python demos/10-transport-cancellation/run.py --out 'outputs/cancellation review'
python demos/10-transport-cancellation/audit.py 'outputs/cancellation review'
python -m unittest discover -s demos/10-transport-cancellation -p test_audit.py -v
python -m unittest discover -s experiments/09-cache-admission -p test_audit.py -v
```

Demo10 和缓存准入审计现在核对 manifest.sources 中每个文件的 SHA-256（CRLF 归一化为 LF），拒绝缺失清单、缺失文件、越界路径与修改后的源码。它证明的是清单所列文件与当前工作区一致，不是清单必然完整。旧证据应在旧固定提交上使用匹配的审计版本，不能把源码不符强行改成通过。
