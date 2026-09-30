# L1v3 相似干扰与上下文位置

本目录是独立新协议 `L1v3-similar-agnes3`，只包含合成材料、执行器与离线验证。**没有新的模型实测结果。** 旧 L1/L1v2 文件及证据不变。

## 冻结设计

- 默认128次：8题 × 60/240条干扰 × low/high × beginning/middle/end/absent。固定精确项目名检索，不混入新题型
- 所有记录统一句式、八字符项目名、同一随机编号池。low/high仅替换六条记录的项目名；六条分布于背景索引 `floor((2*k+1)*N/12)`，避免全部集中在开头
- high包含一字差、词序差、不同限定词各两条；low为无关名称。60与240条均有六条近似干扰，数量固定
- 含答案的三个条件只移动目标，背景完全一致；absent在中间放入等长无关占位记录。问题在记录之后，缺失应输出UNKNOWN
- 题序由Java Random(orderSeed)洗牌；每题长度固定按60、240交替，每个case/length形成一个low/high配对；low/high顺序按题序交替
- 四条件Williams顺序按题序循环：B-M-A-E、M-E-B-A、E-A-M-B、A-B-E-M。每个length/similarity层内四顺序各出现两次。请求总数从矩阵推导，不硬编码24
- 测量的是这批材料上的位置表现，不隔离模型内部注意力机制；近似干扰与目标距离仍随目标移动改变。8题不足以承诺检测小效应的功效。八题共享同一模板家族，是词名/编号实例，不是八种独立任务结构；128请求更不是128个独立统计样本

协议、题材、完整请求与代码hash在 `--prepare` 时冻结。任何材料、模型、参数、runner或audit更改须重新准备新目录；不要覆盖旧证据或根据结果挑题。Java的orderSeed真正参与题序生成，无重复次数字段。若选32次探索档，可新版本使用4题及单长度240；384次档需24个独立题材。当前交付的冻结配置是128次，其他档位尚未冻结。

## 不调用模型的验证

常规环境：JDK8+、Maven、Node18+。

```bash
cd experiments/04-context-position-similar
mvn -q compile
mvn -q exec:java -Dexec.args=--self-test
mvn -q exec:java -Dexec.args=--dry-run
node --test audit.test.mjs
mvn -q exec:java "-Dexec.args=--prepare evidence/my-prepared"
node audit.mjs evidence/my-prepared --prepared
```

`--verify-prepared <directory>`可离线复核当前源文件和请求与准备快照一致。

`--prepare`不读取API密钥、不发送请求，输出目录必须不存在。`--simulate <new-directory>`用假Transport生成全对响应，无网络、无真实等待、usage未知；只能以`node audit.mjs <directory> --simulated`审计。模拟响应不能用于宣称模型正确率。不要把模拟目录与真实证据混放。

运行 `bash verify-offline.sh` 可编译并在临时目录完成自检、dry-run、准备、模拟、Node测试和审计。已预编译环境可传 `LAB_CLASSPATH`（包含classes和Gson jar）跳过Maven。该脚本不读取或调用线上密钥，不含付费请求命令。

## 正式执行的前置条件

此次交付**没有授权付费请求，也没有执行线上实验**。执行前须取得实际账户费率与总金额上限确认；请求数不是费用。确认后由执行者自行通过进程环境设置API密钥，不写入仓库：

- `LAB_APPROVED_MAX_REQUESTS`必须等于协议预算，当前128
- `LAB_PREPARED_DIR`必须指向已独立审计的准备目录
- `AGNES_API_KEY`必须存在
- `--run <new-evidence-directory>`才会发送真实请求；先核对准备目录原始hash、protocol/cases/plan结构及当前源文件hash。不同目录、无重试、无自动恢复

每次完成后至少等20秒。429/401/403立即停；其他请求/协议失败连续两次停。`Retry-After`只存合法值，不自动重试。连接10秒、读取90秒；读取超时不等于全请求总截止。1024输出token上限不保证最终正文长度。

## 评分和统计

精确使用Java trim（只去U+0000..U+0020），编号匹配`[A-Z]{2}-[0-9]{4}`，UNKNOWN区分正确拒答与错误拒答。格式错误是有效响应的错误；HTTP、解析、非stop、角色或返回型号异常单列。wrongDistractorCode诊断只匹配实际出现在该资料的编号。

四响应有效才是完整block，low/high均完整才是完整pair。主要分析进一步要求该题全部长度配对完整，保证题与长度等权；缺失题不填零，另报可用配对的探索性结果及排除原因。

主指标是每题、每长度 `[(B+E)/2-M]_high - [(B+E)/2-M]_low`，先平均长度再平均题。按题bootstrap，报告逐题差与95%区间；8题区间粗，不能视作事先功效保证。10个百分点为预定复验门槛：达到门槛且区间下界>0才描述为本次材料支持，其他均保留不确定性。若全对则存在天花板，不能证明位置无关。

usage未知保持null并计数；usage之和不是账单，失败请求账单未知。只存最终正文与必要元数据，不存reasoning_content、凭证或任意错误页。manifest保留材料原始与LF规范化SHA256、源文件hash和工作区状态。

## 与历史结果的关系

L1v2在2026-09-30的24次真实调用得到18份正确编号与6份正确拒答；本目录的离线或模拟检查不能扩充这24次样本。旧目标句式和编号前缀与干扰存在差异，这是下一轮排除的潜在线索，并非已经证实的模型策略。

新结果完成后可写博客《24次全对之后 如何设计更难的上下文位置实验》：旧证据与边界 → 相似干扰样例 → 冻结矩阵和配对评分 → 真实结果表与错误编号归因 → 不确定性。当前只能撰写设计与工程验证部分。

## 本次离线验证

2026-09-30：Java自检2515项、独立Node测试60项、128请求准备与模拟链路全部通过；没有模型实测。使用ECJ3.40按Java8目标编译，在OpenJDK21.0.12.1运行，Node24.19.0。此环境没有Maven/javac，未验证真正Java8+Maven环境。详见 [验证记录](VERIFICATION.md)。

2026-09-30后续已通过本机真实Java 8与Maven验证，详见 [Windows复核](VERIFICATION-WINDOWS.md)。原交付记录作为历史保留；线上实验仍未执行。
