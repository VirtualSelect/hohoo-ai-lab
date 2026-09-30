# 离线验证记录

基线提交：16a05f19c69ba4f00a20b41c80a4fe4da93dedb2，仓库 VirtualSelect/hohoo-ai-lab。

2026-09-30，仅在云端隔离工作目录验证，未推送或建立PR；没有真实模型调用，未读取API密钥。

## 已通过

- ECJ 3.40.0 `-8`编译全部新Java源码，无编译错误或警告；class major version 52
- Java自检2515项：材料唯一性、等长配对、动态预算、评分、429/401/403立即停止、500连续失败停止、有效响应重置失败计数、角色/型号/截断/缺失/空/非字符串正文异常等
- Node独立测试60/60：独立Java随机顺序重建、完整请求答案泄漏检查、字节/字符/位置与配对材料、精确Java trim、hash篡改、格式和评分、usage未知、停止节奏、模拟隔离、完整配对与按题bootstrap
- 128条准备材料：32个block、16个pair，独立审计通过，requestsSent=0
- Java `--verify-prepared`通过；改动准备协议后拒绝，未发送请求
- 未设置明确请求预算时`--run`被拒绝，输出目录也未创建
- 128条假Transport模拟请求端到端通过；无网络、无真实等待；未知usage不计作零成本。模拟文件未纳入交付证据，不能当模型效果
- 临时32请求配置：4题×240条×2相似度×4条件，自检1160项通过；矩阵预算错误配置被拒绝。交付正式配置仍为128条
- 旧L1v2独立Node测试5/5、原24次真实证据离线审计通过；旧文件和证据未修改

## 环境与未验证部分

运行时OpenJDK21.0.12.1，Node24.19.0。编译用Maven Central发布的ECJ3.40.0和Gson2.10.1；第三方jar不在交付包内。

本环境没有Maven或javac，因此未运行Maven构建、未在真实Java8运行时验证。Java8目标字节码不等同于完成Java8运行环境验证。没有线上接口、账单、延迟或模型正确率验证。线上执行仍需要费率和金额预算确认。

## 复核入口

标准JDK与Maven环境进入本目录运行 `bash verify-offline.sh`。审计冻结材料：

```bash
node audit.mjs evidence/20260930-offline-prepared --prepared
```

准备目录manifest保留基线commit、脏工作区状态、源文件hash及材料原始/LF规范化hash。新源码尚未提交，不把基线commit误称为新代码提交；跨系统换行变化后应重新冻结新的准备目录，不能修改旧manifest伪装一致。
