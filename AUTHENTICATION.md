# 认证与资源权限

本项目使用 Spring Security Resource Server 验证短期 JWT，使用 MySQL 保存可撤销的续期会话。浏览器业务请求不再传递用户 ID；所有资源归属以已验证的 `sub` 为准。

## 已实现的契约

- 开放注册，用户名为 3–100 位字母、数字或下划线，新密码为 12–128 位。数据库唯一约束处理并发重名；账号响应不包含密码或哈希。密码采用带算法标识的 PBKDF2-HMAC-SHA256，16 字节随机盐、600000 次迭代。
- 访问凭据：HS256 JWT，默认 600 秒，校验签名、算法、issuer、audience、身份和过期时间；允许 30 秒时钟偏差。只在浏览器内存保存，验证过程不查询 Redis 或续期表。
- 长期凭据：随机 256 位 secret，服务端只长期保存 SHA-256 摘要；`vm_refresh` Cookie 为 host-only、HttpOnly、SameSite=Lax，默认 Secure，浏览器 Path 为 `/api/user`。刷新页面和重开浏览器时恢复登录。
- 不活跃期限：用户主动 API 操作触发的成功续期，将期限延长到服务端当前时间后 30 天；前端最多每 5 分钟记录一次活动。正常情况下与最后一次操作的偏差不超过这个间隔；故障期间未成功记录的活动不承诺保留。后台任务轮询、启动恢复和手动“恢复登录”不独立延长期限。已有 JWT 可用时，活动续期在后台执行，不阻塞业务请求。
- 续期：事务中锁定会话并轮换凭据。相同请求 UUID 可在 30 秒内恢复同一轮换结果；短期重发材料使用独立 AES-GCM 密钥加密。不同请求的旧凭据短期重用返回 409；超出窗口拒绝旧凭据，不自动撤销正常持有者的整条会话。每分钟清理过期记录。超出幂等窗口的响应丢失仍可能需要重新登录。
- 同标签页合并续期，多标签页使用 Web Locks 串行协调 Cookie；不支持 Web Locks 时使用服务端冲突检测及一次重试，极端并发仍可能要求恢复登录。仅在 localStorage 保存非凭据的刷新请求 UUID/时间和原有上传续传信息；不保存 JWT、密码或用户实体作为认证依据。
- 退出：撤销持久会话成功后清理 Cookie 和本地身份。存储失败返回 503，前端提示重试，不假报退出成功。旧 JWT 最长仍能使用到签发后 10 分钟加 30 秒偏差；已发出的预签名对象 URL 也有自己的有效期。
- 暂时故障：刷新 503 不清除已有身份或长期 Cookie；有效 JWT 仍可本地验证。JWT 过期后的新访问必须等待续期恢复。业务查库、资源归属检查及 AI 限流各自仍受 MySQL、Redis 的可用性影响。
- 收费操作只在进入 Controller 前明确被认证过期或 CSRF 拒绝时分别最多恢复重试一次；网络超时、供应商故障、普通 500/503 不重放。
- CSRF 采用无 HttpSession 的 Cookie repository；登录、注册、刷新、退出及业务写入均保护。登录/退出清理旧 CSRF Token，前端重新获取。CORS 仅允许明确配置的来源。
- 视频、字幕、章节、上传会话、问答、Agent、知识索引、分析任务均校验真实归属；他人资源返回 404。验证先于 MQ、AI、扣额度、对象签名和删除。已授权的后台任务不依赖 HTTP SecurityContext，退出不取消任务。
- MinIO 必须为私有桶。启动检查拒绝匿名 Allow 策略；应用不会在每次启动时自动修改桶策略。直接上传不携带应用 Authorization 或 CSRF Header。

## 首次配置与本地启动

```bash
# 在项目根目录执行，默认写到 ~/.vediomind/auth.key，权限 0600。
# 已存在的密钥绝不会覆盖。
./scripts/auth-admin.sh init-key
./scripts/auth-admin.sh audit

# 仅本机 HTTP 开发环境启用 dev；生产环境保持 Secure Cookie。
cd server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

前端默认 `/api`，Vite 转发到 `http://localhost:9090` 并移除 `/api`。前后端需一起发布。生产代理也须保留浏览器 `/api/user` Cookie 路径；API 不应直接公开在不受控来源下。

配置示例见 `server/src/main/resources/application.example.properties`。`auth.key-file` / `AUTH_KEY_FILE` 指向外部持久密钥文件；普通重启、发布必须复用同一文件。MySQL 的 auth 表也必须持久保存。备份数据库时单独保护并备份密钥；丢失密钥会使 JWT 无法验证、短期回执及加密备份无法恢复。

生产环境设置实际的 `auth.allowed-origins`，通过 HTTPS 提供前端和对象存储；移除开发来源。后端端口只允许受控代理访问。需要使用客户端 IP 限流时，代理必须覆盖而非透传外来 Forwarded/X-Forwarded-* Header，再开启 `server.forward-headers-strategy=framework`；否则请求按直接连接地址限流。不能在后端直接暴露的情况下信任任意转发头。

登录保护复用 Redis：注册每 IP 每分钟 5 次，登录及续期每 IP 每分钟 60 次，登录另有每账号每分钟 10 次。Redis 故障拒绝这些认证入口并返回 503，有效 JWT 的验证不经过该限流。

## 从旧明文账号切换

首次切换时，原 localStorage 伪登录态不能转换为可信会话，需要用原密码重新登录一次。启用新认证后的正常发布重启会保留登录。

维护窗口中先停止旧后端，避免新旧密码格式并存。工具不会启动 HTTP 服务或 MQ 消费者，也不会自动改名、合并、删除重名账号。

```bash
# 1. 初始化持久密钥；audit 只输出数量。
./scripts/auth-admin.sh init-key  # 已初始化时跳过此命令
./scripts/auth-admin.sh audit

# 2. 使用自己的数据库备份工具生成 SQL，将标准输出管道给加密备份命令。
# 例如本地 Docker（不把密码放在命令参数中）：
docker exec mysql-media sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF media_db' \
  | ./scripts/auth-admin.sh backup-db /absolute/private/path/before-auth.sql.enc

# 3. 转换前另建一份加密账号备份；不覆盖已有文件。
./scripts/auth-admin.sh hash-passwords /absolute/private/path/passwords.enc
./scripts/auth-admin.sh audit
# 4. 启动新应用，Flyway 应用 V18，然后发布对应前端。
```

离线工具默认读取被 Git 忽略的 `server/src/main/resources/application.properties`。若配置使用环境占位符，显式提供 `AUTH_DB_URL`、`AUTH_DB_USER`、`AUTH_DB_PASSWORD`；也可用 `AUTH_DB_CONFIG` 指定配置文件。不要把这些值写进仓库。

密码转换保留用户 ID 和视频归属，逐一验证哈希后事务提交；重复运行会跳过已转换账号。存在重复用户名或未知编码时拒绝批量修改。不要回退到旧明文登录应用，也不要为回退恢复明文在线登录。

验证加密数据库备份：

```bash
./scripts/auth-admin.sh restore-backup-file \
  /absolute/private/path/before-auth.sql.enc /absolute/private/path/restore.sql
# 仅将生成的 0600 SQL 文件导入隔离数据库，验收后删除。
```

密钥不自动轮换。普通发布不生成新密钥；若因泄露必须紧急换钥，应在维护窗口撤销全部长期会话并要求重新登录，保留旧密钥的离线备份以恢复旧备份文件。本版不提供无感密钥轮换或多设备管理。

## 验证入口

```bash
cd server
./mvnw test
cd ../client
npm test
npm run build
```

`AuthSessionsTest` 默认使用隔离 H2；设置 `AUTH_TEST_MYSQL_URL`、`AUTH_TEST_MYSQL_USER`、`AUTH_TEST_MYSQL_PASSWORD` 可验证真实 MySQL 行锁与并发。测试会删除目标库中的 auth/users 表，强制数据库名以 `vediomind_auth_test_` 开头；使用一次性的专用库与只对该库授权的账号。`AuthMigrationMySqlTest` 额外读取 `AUTH_TEST_MIGRATION_URL`，应为另一个空的同前缀隔离库，用于执行 V1–V18 与重复迁移验证。普通测试未设置该变量时跳过此项，不能把跳过宣称为 MySQL 验证通过。

本次在独立 MySQL 8 库验证了迁移、唯一约束、并发续期、幂等和回滚；通过真实本地 API 验证原账号登录、两用户资源隔离、直传与私有播放、应用重启保留登录、退出撤销及加密备份恢复。故障注入使用测试桩或隔离表，不中断共享 Redis/MySQL，也没有调用付费模型。
