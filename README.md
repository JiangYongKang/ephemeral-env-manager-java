# Ephemeral Environment Manager（临时环境租约与回收编排）

一套**完全本地可运行**的临时隔离环境租约管理能力：按名称占用（创建）、续用（续租）、归还（释放）环境，
维护可解释的生命周期状态，自动回收到期环境，支持容量配额、归属校验、回收失败重试与重启恢复。
不依赖任何外部服务、真实凭据或付费资源（底层"隔离环境"由进程内的 `LocalResourceReclaimer` 模拟）。

## 1. 生命周期状态机

每个环境（租约）有 5 个状态，全部转换都在存储层的同一原子临界区内完成：

```
                         到期(now >= deadline)
  ┌────────┐  ─────────────────────────▶ ┌─────────┐
  │ ACTIVE │                                │ EXPIRED │
  └────────┘  ◀───────────────┐            └────┬────┘
       ▲    续租成功(deadline 顺延)              │ 回收开始
       │                                        ▼
       │                                  ┌────────────┐
       │                                  │ RECLAIMING │
       │                                  └─────┬──────┘
       │                       资源归还成功      │      资源归还失败
       │              ┌────────────────────────┴───────────────┐
       │              ▼                                        ▼
       │        ┌──────────┐   名称可被重新占用           ┌────────────────┐
       └────────│ RELEASED │◀────（历史记录保留）────────│ RECLAIM_FAILED │
                └──────────┘                             └────────────────┘
                    ▲                                            │ 重试
  ACTIVE ─主动释放─▶ RECLAIMING ...（同上）                      └──▶ RECLAIMING
```

| 状态 | 含义 | 是否占容量 | 可续租 |
|---|---|---|---|
| `ACTIVE` | 已占用且 `now < deadline`，处于有效租期 | 是 | 是 |
| `EXPIRED` | 已到期（惰性判定，续租/回收时推进到此状态），等待回收 | 是 | 否（拒绝原因 `LEASE_EXPIRED`） |
| `RECLAIMING` | 正在归还底层资源；也是进程崩溃后可重入的恢复入口 | 是 | 否 |
| `RECLAIM_FAILED` | 资源归还失败，记录尝试次数与原因，等待下轮重试 | 是 | 否 |
| `RELEASED` | 资源已归还；从活跃表移除，仅在"已释放历史"中留痕 | 否 | 否（拒绝原因 `ALREADY_RELEASED`） |

说明：

- **到期判定是惰性的**：不依赖后台线程翻转状态。任何续租/回收/扫描在临界区内以 `now >= deadline`
  当场判定，因此结论只取决于临界区内的时钟，不依赖扫描是否及时运行。
- **过期环境不长期残留**：可开启后台调度器周期扫描（默认关），或直接调用
  `POST /api/envs/{name}/reclaim` 的等价服务方法 `reclaimExpired()` 立即回收全部到期环境。
- **仍在租期内的环境不会被误回收**：`reclaim` 在临界区外、临界区内各做一次
  `status==ACTIVE && deadline > now` 防护。
- **回收幂等且可重复执行**：`LocalResourceReclaimer` 内部记录已归还的资源 ID，重复调用直接短路；
  状态已终结（`RELEASED`）后再次回收返回"空操作 + 幂等"说明，绝不重复归还。

## 2. 并发与一致性语义

- 所有"检查—判定—写入"（创建查重、配额计数、续租的到期比较与 deadline 顺延、回收的状态推进与资源归还）
  都在 `LeaseStore.mutate` 的**单一锁临界区**内原子执行：
  - 同名并发创建：**恰好一次**实际占用，其余同属主请求幂等返回既有租约；
  - 并发续租：每次都在当前 `deadline` 上顺延并 `version+1`，**无丢失更新**；
  - 续租与到期并发：临界区串行化，最终状态**唯一且确定**——要么续租先生效保持 `ACTIVE`，
    要么到期先生效转为 `EXPIRED/RELEASED`，二者恰居其一，不可能出现"续租成功又被回收"。
- **时钟异常安全**：续租基准取 `max(旧 deadline, now)` 后再加时长，墙钟回拨不会缩短既有租期，
  也不可能因回拨把环境误判到期。
- 每次状态提交后全量快照以"临时文件 + 原子 rename"落盘，崩溃不会留下半截状态文件。

## 3. 容量配额与归属规则

- 配额 `ephemeral-env.max-active-leases`（默认 2）：计数所有**非 `RELEASED`** 租约。
  特别地，`EXPIRED/RECLAIMING/RECLAIM_FAILED` 仍占容量——回收未完成意味着底层资源尚未归还，
  此时不得超额占用真实资源；回收成功后容量立即归还。
- 归属：创建时记录 `owner`；**续租与主动释放仅归属方可执行**，非归属方一律 `NOT_OWNER`。
- 名称冲突：同名环境被**其他属主**占用时创建被拒（`NAME_CONFLICT`）；
  同名同属主重复创建是幂等成功（HTTP 200 + `created=false`），不重建、不重置租期。
- 释放后的名称可被任意属主重新占用（分配新的 `resourceId`），但对旧名称的续租/释放/查询
  仍明确返回 `ALREADY_RELEASED`（HTTP 410），与"从未存在"的 `NOT_FOUND`（HTTP 404）区分。

### 可区分的拒绝原因（`RejectReason`）

| reason | HTTP | 触发场景 |
|---|---|---|
| `NAME_CONFLICT` | 409 | 同名已被其他属主占用 |
| `QUOTA_EXCEEDED` | 409 | 非终结租约数已达配额 |
| `NOT_OWNER` | 409 | 续租/释放操作方不是归属方 |
| `LEASE_EXPIRED` | 409 | 对已到期/回收中的环境续租（到期竞争的确定结论） |
| `RECLAIM_FAILED` | 409 | 主动释放时底层资源归还失败，租约进入失败队列待重试 |
| `NOT_FOUND` | 404 | 名称从未创建过 |
| `ALREADY_RELEASED` | 410 | 名称曾经存在但已释放 |
| `INVALID_REQUEST` | 400 | 参数缺失/非法（空名称、非正 TTL、坏报文等） |

## 4. 回收失败隔离与重试

- 单环境回收失败仅把**该租约**标记为 `RECLAIM_FAILED`，记录 `reclaimAttempts` 与
  `lastErrorMessage`，不向调度器抛出异常；`reclaimExpired()` 对每个候选环境单独 try/catch，
  **一个环境卡住不阻塞其他环境**的创建与回收。
- 回收可无限重试：下一轮扫描/手动 `reclaim` 会重新进入 `RECLAIMING`；底层归还器幂等，
  即使上次在"资源已归还、状态未落盘"之间崩溃，重试也不会重复归还。
- 测试可通过 `LocalResourceReclaimer.failNextAttempts(resourceId, n)` 注入前 n 次失败，
  在本地完整复现"失败 → 记录 → 重试 → 成功"。

## 5. 持久化与重启恢复

- 状态文件：`ephemeral-env.data-file`（默认 `${user.dir}/.ephemeral-env/leases.json`），
  包含活跃租约表（含状态、deadline、version、回收尝试次数、最近失败原因）与已释放历史。
- 构造 `JsonFileLeaseStore` 时即从文件装载，因此**重启后**：
  - `ACTIVE` 租约的 owner/resourceId/deadline/version 原样恢复，仍在租期内不会被误回收；
  - `EXPIRED/RECLAIMING/RECLAIM_FAILED` 待回收进度（含尝试次数与失败原因）可解释地恢复并可继续/重试；
  - `RELEASED` 历史保留，继续与 `NOT_FOUND` 区分。

## 6. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/envs` | body `{"name","owner","ttlMillis"?}`；创建或幂等命中（201/200） |
| `POST /api/envs/{name}/renew` | body `{"owner","extendMillis"?}`；仅归属方、仅有效租期内可续租 |
| `POST /api/envs/{name}/release` | body `{"owner"}`；归属方主动归还 |
| `GET  /api/envs/{name}` | 查询状态视图（含 `status/deadlineMillis/version/reclaimAttempts/lastErrorMessage/expired`） |

错误响应统一为 `{"reason":"<枚举名>","message":"<判定依据>"}`。

## 7. 本地验证方法

要求 JDK 17+（工程目标 Java 21，在本机 JDK 上亦可编译运行）与 Maven。

```bash
# 1) 全量测试（19 个用例：生命周期/并发/竞争/失败重试/重启恢复/HTTP/调度）
mvn test

# 只跑某一组
mvn test -Dtest=LeaseConcurrencyTests
mvn test -Dtest=LeaseRecoveryTests

# 2) 启动服务（短租期 + 每秒扫描，便于直接观察自动回收）
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--ephemeral-env.max-active-leases=2 \
  --ephemeral-env.default-ttl-millis=3000 --ephemeral-env.scheduler-enabled=true \
  --ephemeral-env.reclaim-interval-millis=1000 --server.port=8080"

# 3) 手动验证
curl -X POST localhost:8080/api/envs -H 'Content-Type: application/json' \
  -d '{"name":"dev-1","owner":"alice","ttlMillis":3000}'      # 201 CREATED
curl -X POST localhost:8080/api/envs -H 'Content-Type: application/json' \
  -d '{"name":"dev-1","owner":"alice"}'                        # 200 幂等
curl -X POST localhost:8080/api/envs -H 'Content-Type: application/json' \
  -d '{"name":"dev-1","owner":"bob"}'                          # 409 NAME_CONFLICT
# 等待 3 秒后，调度器自动回收；随后：
curl -i localhost:8080/api/envs/dev-1                          # 410 ALREADY_RELEASED
```

### 配置项（`src/main/resources/application.properties`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `ephemeral-env.max-active-leases` | 2 | 非终结租约容量配额 |
| `ephemeral-env.default-ttl-millis` | 60000 | 默认租期/续租时长（毫秒） |
| `ephemeral-env.data-file` | 空（工作目录 `.ephemeral-env/leases.json`） | JSON 状态快照路径 |
| `ephemeral-env.scheduler-enabled` | false | 是否启用后台到期回收 |
| `ephemeral-env.reclaim-interval-millis` | 5000 | 后台扫描间隔（毫秒） |

## 8. 代码结构

```
domain/   Lease, LeaseStatus, CreateResult, ReclaimOutcome, ReleasedRecord
error/    RejectReason, LeaseException
resource/ ResourceReclaimer（幂等归还接口）, LocalResourceReclaimer（本地模拟+故障注入）
store/    LeaseStore（原子读改写接口 + REMOVE 哨兵）, JsonFileLeaseStore（单锁串行化+原子落盘）
service/  LeaseService（状态机/配额/归属/回收编排）, ReclaimScheduler（周期扫描，可关）
config/   LeaseProperties, ServiceConfig（Clock/Store/Reclaimer/Service/Scheduler 装配）
web/      LeaseController, GlobalExceptionHandler, dto/*
```

核心设计要点：把所有并发正确性收敛到 `JsonFileLeaseStore` 的一把锁 + `LeaseService` 在临界区内的
状态判定；Clock 可注入（测试用 `MutableClock` 模拟时间流逝与回拨）；底层资源层纯本地且幂等。
