# v3：N03单例探索复测

experimentId：`notes-exploration-003` · 日期：2026-11-13 · 状态：**已执行一次，交付1份PARTIAL笔记；观察到真实引用ID纠正，内容仍不完整。**

[方法论](../../METHODOLOGY.md) · [当前计划](../../PLAN.md) · [002旧版结果](../notes-exploration-002/REPORT.md) · [两份旧笔记的离线诊断](../notes-exploration-002/CONTENT_AUDIT.md)。原始记录和旧成果不覆盖，不称正式Baseline。

## 冻结条件与授权

- 用户明确要求执行内容诊断及N03单例验证；仅本题一次、不重跑择优、不执行其余七例。
- methodologyVersion `1.1.0`；冻结planVersion `0.7.0-draft`；datasetVersion `0.1.0-draft`；rubricVersion `NONE_EXPLORATORY`；Ground Truth `UNVERIFIED`。当前PLAN之后追加结果不改变本次标准。
- Agent SHA `db79e998f8af7ae0b7687405c828993ed2ea4d16`，运行 `bounded-study-v3`，生产代码dirty diff为空。与已推送代码一致，本次没有修改核心实现。
- 与002相同N03用户任务、范围43／44、Skill、工具、模型请求参数及单任务硬预算；仅使用v3的可引用ID清单及精确纠错反馈。新数据库、新私人课程、空学习状态；既有向量索引只读。
- 材料SQL SHA256 `eaa21de6b85e1ece78ccad63e41bd9c691390214489dbe0507412622b3bf9ab0`，与001／002相同。任务、工具、Skill及材料的预检比较通过；未传入诊断、评分规则、参考解答或旧笔记。
- 单例保守预留上限1元，承接前批完整预留3.087243元，计入整轮10元；UNKNOWN／供应商异常／超限停止。
- 预先声明观察是否尝试保存、是否全部引用已读证据、是否实际交付；若发生错误，观察是否在有限机会内纠正引用及内容。正文质量与宿主状态分开评价。

## 实际结果

| 项目 | 记录 |
| --- | --- |
| Run ID | `12cb8391-9fb8-4cc6-9344-ef56d9846434` |
| 执行时间 | 20:59:55～21:00:13，约18秒，不含准备和预检 |
| 终态 | `PARTIAL / TASK_INCOMPLETE` |
| 模型／Embedding尝试 | 5／2，共7次；全部HTTP 200、usage KNOWN |
| 工具尝试 | 7次：Skill 1、结构1、搜索2、原文读取1、保存2 |
| 原文读取 | 3条：43:OCR985、43:OCR990、44:OCR1090，均 `t1-v1-p2` |
| 正式成果 | 1份DELIVERED笔记，不是只生成聊天文本或未交付候选 |
| 完整任务 | 尚未完成：主要只展开BST一侧，Heap比较仍缺原文 |

本次请求 `deepseek-v4-flash`、关闭thinking，响应 `deepseek-flash`；Embedding `text-embedding-v4`、1024维。供应商当前将旧别名映射至DeepSeek-V4.1-Flash-1017，精确后端快照无法由客户端锁定；不宣称版本控制消除了供应商漂移或能逐字复现。

## 纠错经过：哪些有效、哪些没有完成

1. 第3轮读取三条BST相关OCR，又执行Heap搜索。第4轮由保守Token余量策略进入FINALIZE，禁止继续读取。此时账本剩余86621 Token、约112秒；不因看见账本余量就认定可以安全容纳额外大请求。
2. 第4轮保存的Heap知识点引用了搜索线索中的 `43:OCR:1046:t1-v1-p2`、`44:OCR:1156:t1-v1-p2`，它们没有经过原文读取。宿主拒绝，并明确列出错误ID、允许ID，要求移除无据结论而非只换引用。
3. 第5轮模型删除该Heap知识点，移除非法ID，保存成功。最终所有引用均属于已读集合；唯一成果原子交付。
4. **正文仍残留无本次已读原文支持的Heap表述**：核心结论仍称Heap用于Priority Queue且定义包含complete binary tree，还声称本轮已读证据支持这些；实际三条原文全是BST定义与相关代码。不能把引用ID合法等同于语义支持成立。

因此本次观察到真实错误后的ID修正及交付改善，但不能声称完整内容纠错已通过。单次Trial也不能证明稳定性提升。002的失败保留原样，不被新结果替换。

## 内容诊断边界

- 笔记对BST整棵左右子树顺序的表述可对应OCR985／990；这两条来自相同定义页，三个ID不等于三个独立知识片段。
- Min Heap的大小关系与主要操作仍是原题必需项，未读到就列入gaps，属于真实任务遗漏；与N02超出题目的额外gaps不同。
- OCR1090里函数名与递归调用存在混杂，插入／查找和重复键的解释仍需人工核对代码画面，不直接当已确认事实。
- 本次没有正式事实正确率、知识覆盖率或内容合格评分。原文比对由Codex整理，独立人工确认仍待完成。

## 费用与停止检查

生成输入21671 Token、输出2287 Token，Embedding输入15 Token。沿用保守费率：DeepSeek高峰未缓存输入2元／百万、输出8元／百万，Embedding北京输入0.5元／百万，复核来源：[DeepSeek官方](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)、[阿里云官方](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/)，日期2026-11-13。

- 本次用量估算 **0.0616455元**；加上前两批，累计 **0.4102725元**。这是估算，不是供应商账单。
- 本次保守预留 **0.475491元**，低于1元上限；累计预留 **3.562734元**，低于整轮10元。预留不与估算费用相加，没有退款扩批。
- 没有UNKNOWN或供应商失败，未触发货币上限。没有追加调用或Trial。

## 验证与产物

本次新增／执行：跨批货币预留承接数学检查、负数／超限／不一致拒绝、003额外案例和超1元配置在启动前拒绝、后端编译、无付费预检、原任务／模型／工具／Skill比较，以及真实结果的引用集合与唯一交付核验。没有改核心代码，因此未重复执行上一轮235项后端回归；不把旧测试计作本次新执行。

私有目录：`/Users/yumestk/Documents/vm/eval-results/notes-exploration-003/`：

- `NOTES.md`：原样交付笔记、gaps、引用及原文；`REVIEW.md`：纠错与残留内容问题。
- `summary.json`、五张业务表与请求账本、实际请求、预算／事件／检查点、`run.log`：全部实际记录。
- `manifest.json`、材料SQL、方法论／冻结计划／Runner／Java快照、`resources.json`、`tasks-run.json`、`preflight-checks.json`。
- `parent-budget.json`、`budget.json`：跨批累计预留；`candidate-freeze.json`：v3工程回归候选，不是正式质量Baseline。

当前保留v3为后续对照版本，已知限制不隐藏。下一项应聚焦必要子目标的证据覆盖和原文条件完整性，独立比较有边界的邻接读取或目标覆盖策略；不同时调检索、Prompt、收尾预算和Rubric，也不靠删除gaps让状态变绿。本次不自动扩批或继续修改Agent。
