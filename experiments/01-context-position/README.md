# L1：上下文位置试验（首轮，提前停止）

用 6 个人工虚构项目核对：在同一组干扰材料中，只移动目标事实的位置，会发生什么？这不是长上下文榜单或 Lost in the Middle 的复现。

## 冻结协议

- Agnes agnes-2.5-flash，temperature 0，max_tokens 512；只有 system + 当前 user，不携带历史。
- 6 个事实 × beginning / middle / end / absent × 2 次，计划最多 48 次。
- 三个含答案条件是同一 61 行资料的重排，目标位置为 0 / 30 / 60；absent 替换为无关记录，不声称精确等长。
- 请求约 4298–4301 个 UTF-16 字符，不是 token 数。完整请求在 plan.json。
- 固定 seed 20260928 打乱顺序；顺序调用、无重试。连接 10 秒、读取 90 秒，连续 3 次请求/协议失败即停止。
- 全部虚构数据；密钥仅来自进程环境，不记录推理内容、请求头或密钥。

## 实际结果

2026-09-28 UTC：计划 48，尝试 29，正常返回 26，3 次 HTTP 429 后停止，19 次未执行。

| 条件 | 计划 | 尝试 | 正常返回 | 正确答案/拒答 | HTTP 429 |
| --- | ---: | ---: | ---: | ---: | ---: |
| beginning | 12 | 8 | 8 | 8 | 0 |
| middle | 12 | 8 | 6 | 6 | 2 |
| end | 12 | 7 | 6 | 6 | 1 |
| absent | 12 | 6 | 6 | 6 | 0 |

不能把 26/26 正常响应符合预期写成完整实验 100% 成功，更不能据此宣称位置不重要。样本少、重复相关、材料短而规则，提前停止造成分组不平衡。429 的具体限额原因未从记录确认。

## 复现

JDK 8 + Maven；首次 Maven 下载 Gson。进入本目录：

    mvn -q compile
    mvn -q exec:java -Dexec.args=--self-test
    mvn -q exec:java -Dexec.args=--dry-run

检查不发送请求。真实运行需自行设置 AGNES_API_KEY，并选择不存在的目录：

    mvn -q exec:java "-Dexec.args=--run evidence/my-run"

真实运行会调用计费 API，最多 48 次；不是自动补齐旧记录。不要覆盖或编辑既有证据。后续材料长度、节流与样本规模变化应新建协议版本。

## 离线审计（Node.js 18+，可选）

    node audit.mjs evidence/20260928-l1

独立检查事实移动、无答案对照、条件组合、请求 hash、逐条评分、失败停止条件和汇总。manifest 保存运行代码版本与输入文件 hash。transport_error 包括 HTTP 非 200、网络和解析异常，结合 errorCode 判断，不能视为模型答错。

读取超时约束单次阻塞读取，不是请求整体硬截止。temperature 0 不保证托管模型位级确定性。保存的 usage 仅覆盖有返回值的请求，不等于账户账单。
