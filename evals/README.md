# Eval 入口

先读 [方法论](METHODOLOGY.md)，再读 [当前计划](PLAN.md)。正式评分与探索性试跑分开记录。

历史探索：[notes-exploration-001](runs/notes-exploration-001/REPORT.md)；预算内交付修复复测：[notes-exploration-002](runs/notes-exploration-002/REPORT.md)。候选事实仍未人工确认，不作正式 Baseline。

## 本地最小执行入口

`run_exploratory.py` 使用 Python 标准库编排，`ExploratoryTrial.java` 复用项目真实服务。没有新评测框架或模型评委。入口只适用于本机已解析的媒体 42／43／44、既有 MySQL／Redis／Qdrant 与本地 Java 21；类路径来自已存在的 `server/target/auth-classpath.txt`，运行前应编译当前后端。

002 已执行完成，以下命令是既有执行记录的用法；入口会拒绝覆盖它。当前代码为试跑后修正的 v3，与 002 冻结的 v2 不同，不能用当前代码再次冒充 002。

```sh
python3 evals/run_exploratory.py prepare --experiment notes-exploration-002
python3 evals/run_exploratory.py preflight --experiment notes-exploration-002
# 真实付费调用：必须已有相应材料、费用和范围授权
python3 evals/run_exploratory.py run --experiment notes-exploration-002
```

`prepare` 为 002 复用 001 已冻结材料快照并校验哈希，创建新数据库，拒绝覆盖已有数据库；前批货币预留继续计入整轮上限。执行前检查 Agent SHA／diff 与 Runner 哈希仍与准备时一致。`preflight` 检查实际任务策略、材料、空学习状态和费用数学／超限拒绝，不调用模型。`run` 单进程串行执行三例，逐次货币预留；未知用量停止。新调用需要预算，不能通过重启进程清空已有预留；入口拒绝已存在的预算文件或已污染的初始学习状态。

显式指定实验 ID，当前仅支持已定义的 001／002；历史 001 的原脚本保存在其私有快照内。数据库名、结果目录和当前环境绑定在脚本中，不是通用平台。新增一轮时应先按方法论定义新实验并调整其明确范围，不删除旧结果或重用身份来伪装成独立 Trial。原始课程材料、Trace 与供应商响应只保存在私有结果目录，不自动提交 GitHub。
