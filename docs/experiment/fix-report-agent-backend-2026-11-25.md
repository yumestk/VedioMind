# Agent Loop 与后端定点修复及回归记录

日期 2026-11-25。范围为代码审查选定的 3 项 P1、5 项 P2，以及 `HEAD` 上既有的 3 项失败集成测试。回归使用本地测试替身与隔离 MySQL，全程零外部 LLM、Embedding、Rerank 付费调用。

## 定点修复

| 优先级 | 链路 | 修复与回归覆盖 |
|---|---|---|
| P1 | 本轮决策 → 引用验收 | 发出模型请求前冻结已读证据快照。同轮读取的新原文不能用于同轮保存，下一轮可以引用。 |
| P1 | 候选笔记 → 正式交付 | 候选落库、可见成果、Run 结果和终态在同一数据库事务内提交，保留权限及来源版本校验。MySQL 测试在插入候选后触发活动时间耗尽，验证笔记仍以部分成果交付。另验证终态写入失败时整笔事务回滚，重放复用模型响应。 |
| P1 | 解析与索引 → 恢复调度 | 扫描陈旧的普通解析和索引 `QUEUED` 任务及其执行中阶段。接管执行中任务时在任务锁内推进 `dispatch_version`，拒绝旧版本消息。 |
| P2 | 整批工具校验 → 修正计数 | 同一决策轮的整批校验拒绝只计一次修正。回归覆盖三项调用中只有一项参数错误的情况。 |
| P2 | 批量读取 → 下一轮保存 | 按下一次完整 `save_note` 决策请求的输入余量控制整批读取。回归覆盖同轮四个接近单窗口上限的读取，以及后续保存成功。 |
| P2 | 延后锚点与无新增 → 收尾 | 保留 `deferredWindowAnchors`。连续检索无新增且已有已读原文时，允许根据现有证据交付部分笔记。 |
| P2 | 补检索 → 单视频修订 | 优先保留已引用旧原文，再装入补检索新原文，最后填充旧可选原文。实际入包没有任何新引用时跳过修订生成。 |
| P2 | 上传完成 ↔ 取消 | 两个入口共用会话锁。取消持锁后重读数据库状态，拒绝任何非 `UPLOADING` 会话。并发回归验证已完成会话的状态不会被覆盖。 |

## 三项既有失败

| 集成测试 | 根因与处理 |
|---|---|
| `StudyTaskContextMySqlTest.fullOriginalsAndRetrievalUseCompositeIdentityAndKeepNeighborsWithinMaterial` | 旧断言要求检索线索包含完整窗口的邻文。当前检索按核心命中筛选线索，完整邻文由读取窗口返回。拆开断言，核对复合引用身份和邻域不跨材料。 |
| `StudyTaskContextMySqlTest.courseQuestionUsesBoundedRetrievalOnlyAfterFullContextDoesNotFit` | 旧断言要求检索回退传入非核心邻文，实际只返回所选材料的核心线索。改为验证两个材料的复合引用，并保留全文超预算才触发检索的断言。 |
| `StudyAuthorizationMySqlTest.cancelledLeaseCannotPersistAnyCheckpointPartialOrFinalResult` | 夹具用 MySQL 会话的 `NOW()` 设置截止时间，租约按 JVM 本地时钟判断。两者时区不同时，租约会在取消前提前过期。改为用 JVM 本地时间设置测试截止值，保留取消后禁止写入检查点、部分结果和最终结果的断言。 |

## 回归结果

从 `server/` 执行。两套命令均使用离线 Maven，并显式关闭 `eval.enabled`、`eval.paidLlm`、`eval.paidRerank`。隔离套件通过 `-Dtest='*MySqlTest'` 运行，数据库和备份测试均指向独立容器。

| 套件 | 通过 | 跳过 | 失败 | 错误 |
|---|---:|---:|---:|---:|
| 常规 `./mvnw test` | 250 | 82 | 0 | 0 |
| `StudyAgentRuntimeMySqlTest` | 37 | 0 | 0 | 0 |
| `StudyExecutionMySqlTest` | 19 | 0 | 0 | 0 |
| `StudyTaskContextMySqlTest` | 9 | 0 | 0 | 0 |
| `StudyAuthorizationMySqlTest` | 7 | 0 | 0 | 0 |
| 五套 MySQL 迁移测试 | 5 | 0 | 0 | 0 |
| **隔离 MySQL 合计** | **77** | **0** | **0** | **0** |

常规套件跳过全部 10 项评测用例。运行时集成测试使用连接池，避免测试夹具频繁新建 TCP 连接。退出清理删除了临时 schema 和容器。`git diff --check` 通过。

## 范围与部署影响

`VideoAgentSubmissionService.java` 的范围外索引阻塞和 `AiCallLedger.java` 的 Rerank 用量展示按约定排除。保留 `mediaId=43` 的 MySQL、Qdrant、MinIO 存量数据、`eval-questions.json` 与既有评测报告。

跨材料 Agent 运行版本升为 `bounded-study-v9`。部署后，尚未结束的 v8 跨材料任务在恢复时会提示重新提交。

## GitHub 提交快照复验

从当前分支基线单独提取本轮修复后再次运行。常规测试 240 项通过、79 项跳过，隔离 MySQL 76 项通过、0 项跳过。两套均为 0 失败、0 错误，测试结束后清理了临时 schema 和容器。上表保留完整本地工作区的原始回归结果。
