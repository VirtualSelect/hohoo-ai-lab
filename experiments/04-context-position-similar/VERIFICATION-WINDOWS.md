# 2026-09-30 · Windows / Java 8 接入验证

基于交付包原始协议和源码，未修改L1v3参数、题材或旧L1/L1v2证据。原交付验证历史保留在 VERIFICATION.md。

- Maven 3.6.3 实际使用 Oracle JDK 1.8.0_171；`mvn -q compile` 通过。
- Java `--self-test`：2515项通过。
- Node 26.8.2 独立测试：60/60通过。
- `--verify-prepared evidence/20260930-offline-prepared` 通过：请求和当前源码字节指纹与冻结快照一致。
- Node 独立 prepared 审计通过：128项、8题、32块、16对；requestsSent=0。
- `--dry-run`：请求为1825–6325 UTF-16字符；字符数不是token数，不能据此报实际费用。

本次没有读取API密钥、没有发送真实请求。线上结果、账单和模型正确率仍未验证；新实验的请求预算128不代表已授权的费用预算。在线执行前需确定账户费率与本轮金额上限；此前24次Agnes授权已用于L1v2，不能复用。

Windows PowerShell直接运行README中的Maven和Node命令即可。新增文件固定LF，原始manifest保留交付时的基线commit，不伪装成新代码commit。
