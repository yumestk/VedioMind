# Eval 入口

先读 [方法论](METHODOLOGY.md)，再读 [当前计划](PLAN.md)。正式评分与探索性试跑分开记录。

历史探索：[notes-exploration-001](runs/notes-exploration-001/REPORT.md)；预算内交付修复复测：[notes-exploration-002](runs/notes-exploration-002/REPORT.md)。候选事实仍未人工确认，不作正式 Baseline。

最新工程修复：[004失败后的容量、部分覆盖与READ预算修复](runs/notes-recovery-20261115/REPORT.md)，当前候选为 `bounded-study-v8`／Skill 4。此前[v4～v7分阶段记录](runs/notes-hardening-20261115/REPORT.md)与004真实失败均保留；不能用当前代码重跑替换旧结果，当前尚无正式质量评分。

`ReplayRetrieval.java` 是无付费诊断入口：读取私有JSON中的冻结材料与已有查询向量，仅搜索本地Qdrant；回放分块命中、展开、旧排序、新线索和原读取锚点的窗口，不接触供应商。输出必须是新文件。输入与代码快照位置、编译／执行方法见上述分阶段记录。它不是新的Trial或内容评分Runner。

`ReplayReadFailures.java` 只回放冻结原文、004工具参数与预算历史，不调用供应商；输出是反事实工程检查，不是新的Trial。输入、运行方式与结果见[修复归档](runs/notes-recovery-20261115/REPORT.md)。

## 本地最小执行入口

`run_exploratory.py` 使用 Python 标准库编排，`ExploratoryTrial.java` 复用项目真实服务。没有新评测框架或模型评委。入口只适用于本机已解析的媒体 42／43／44、既有 MySQL／Redis／Qdrant 与本地 Java 21；类路径来自已存在的 `server/target/auth-classpath.txt`，运行前应编译当前后端。

002 已执行完成，以下命令是既有执行记录的用法；入口会拒绝覆盖它。当前代码与002冻结的v2不同，不能用当前代码再次冒充002。

```sh
python3 evals/run_exploratory.py prepare --experiment notes-exploration-002
python3 evals/run_exploratory.py preflight --experiment notes-exploration-002
# 真实付费调用：必须已有相应材料、费用和范围授权
python3 evals/run_exploratory.py run --experiment notes-exploration-002
```

`prepare` 为 002 复用 001 已冻结材料快照并校验哈希，创建新数据库，拒绝覆盖已有数据库；前批货币预留继续计入整轮上限。执行前检查 Agent SHA／diff 与 Runner 哈希仍与准备时一致。`preflight` 检查实际任务策略、材料、空学习状态和费用数学／超限拒绝，不调用模型。`run` 单进程串行执行三例，逐次货币预留；未知用量停止。新调用需要预算，不能通过重启进程清空已有预留；入口拒绝已存在的预算文件或已污染的初始学习状态。

显式指定实验 ID，当前仅支持已定义的001／002／003／004（003仅N03，004三题各一次）；历史 001 的原脚本保存在其私有快照内。数据库名、结果目录和当前环境绑定在脚本中，不是通用平台。新增一轮时应先按方法论定义新实验并调整其明确范围，不删除旧结果或重用身份来伪装成独立 Trial。原始课程材料、Trace 与供应商响应只保存在私有结果目录，不自动提交 GitHub。

## N03单例验证（003，已完成）

003已执行一次：纠正未读引用后交付PARTIAL，仍有正文引用支持问题。以下命令仅作执行记录，入口拒绝覆盖。单例预留上限1元并承接前两批3.087243元；见 [计划](PLAN.md) 与 [003记录](runs/notes-exploration-003/REPORT.md)。复用冻结材料、新数据库和空学习状态，不把内容诊断传给Agent。

```sh
python3 evals/run_exploratory.py prepare --experiment notes-exploration-003
python3 evals/run_exploratory.py preflight --experiment notes-exploration-003
python3 evals/run_exploratory.py run --experiment notes-exploration-003
```

Python的 `self_check()` 检查跨批预留只承接一次及负数／超限／不一致拒绝；Java预检验证003仅选N03、货币上限及实际运行版本。既有实验和结果不覆盖。

## v7真实三题复测（004，已完成）

[004记录](runs/notes-exploration-004/REPORT.md)：N01／N02失败无成果，N03交付SUCCEEDED，内容待核实。各一次，保留失败，无追加Trial。当前入口支持004，批次上限2元并承接3.562734元历史预留；已有数据库与预算文件会阻止重跑。运行快照在私有004目录，不用当前文档更新冒充当时冻结条件。
