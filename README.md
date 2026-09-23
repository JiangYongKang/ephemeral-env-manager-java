# Ephemeral Environment Manager（本地临时环境租约与回收编排）

一套**完全本地可运行**的临时环境租约管理服务：按需占用（acquire）、续用（renew）、
归还（release）隔离环境，并自动回收到期租约。不依赖任何外部服务、真实凭据或付费资源——
“隔离环境”由进程内的内存集合（`LocalResourceBackend`）模拟，重启状态通过本地 JSON 快照恢复。

## 快速开始

```bash
mvn spring-boot:run          # 启动服务（默认端口 8080）
mvn test                     # 运行全部测试（并发/竞争/重试/恢复）
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/leases` | 创建/占用环境，body：`{"name","owner","ttlMillis"}` |
| POST | `/api/leases/{name}/renew` | 续租，body：`{"owner","ttlMillis"}` |
| POST | `/api/leases/{name}/release` | 释放，body：`{"owner"}`（body 可省略） |
| GET  | `/api/leases/{name}` | 查询单个租约 |
| GET  | `/api/leases` | 列出全部租约 |

`ttlMillis` 传 0 或缺省使用默认租期；超过 `ephemeral.max-ttl-millis` 以 `INVALID_TTL` 拒绝。

### curl 示例

```bash
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"name":"env-1","owner":"alice","ttlMillis":60000}'
curl -s -X POST localhost:8080/api/leases/env-1/renew -H 'Content-Type: application/json' \
  -d '{"owner":"alice","ttlMillis":120000}'
curl -s -X POST localhost:8080/api/leases/env-1/release -H 'Content-Type: application/json' \
  -d '{"owner":"alice"}'
```

## 生命周期状态机

```
                 acquire 成功
   (不存在/已释放) ───────────▶ ACTIVE
                                   │
             ┌─────────────────────┼──────────────────────┐
             │ renew               │ 到期(expiresAt<=now)  │ release
             ▼                     ▼                       ▼
          ACTIVE               EXPIRED ──(回收扫描)──▶ RELEASING
      (expiresAt 更新)                                  │
                                  ┌──────────────────────┘
                                  ▼
                            归还底层资源
                          成功 │      │ 失败（保持 RELEASING，
                              ▼      │  attempts+1，下轮重试）
                          RELEASED ◀─┘
```

- **ACTIVE**：在 `expiresAtMillis` 之前持有资源，可续租；到期后自动判定失效。
- **EXPIRED**：可解释的中间态。当续租请求与到期竞争失败时（锁内时钟判定 `expiresAtMillis <= now`），
  租约落入此态并以 `NOT_ACTIVE` 拒绝续租；后台扫描随即进入 `RELEASING`。
- **RELEASING**：正在归还资源。归还失败会停留在此态并累计 `releaseAttempts`，等待重试；
  该进度会写入快照，服务重启后继续。
- **RELEASED**：终结态。资源已归还、容量已释放；重复 release 幂等；
  同归属方/不同归属方的语义见下；同名可在 RELEASED 后被原子重建。

判定基准统一为**可注入时钟** `Clock`：`expiresAtMillis > now` 才视为有效租期。
时钟回拨不会误回收有效租约（有单测覆盖时钟异常场景）。

## 并发与幂等语义

- **并发同名创建**：存储基于 `ConcurrentHashMap.putIfAbsent`，只有一个请求获胜。
  - 同归属方的重复请求 → 幂等返回同一租约（不重复占用资源）。
  - 不同归属方 → `NAME_CONFLICT`（409），底层资源不增加占用。
  - 已释放名称的并发重建 → 引用级 CAS（`replace(expected, replacement)`），只有一个重建成功。
- **并发续租 / 续租与到期竞争**：续租的“查状态→查归属→查到期→写新到期时间/版本”
  在 `ConcurrentHashMap.compute` 的 bin 锁内原子完成，因此无丢失更新；
  每次续租 `leaseVersion + 1`。是否到期的结论在锁内一次判定，确定无歧义。
- **重复 release**：已是 RELEASED 时幂等返回，不重复归还（归还接口本身也幂等）。

## 拒绝原因（可区分）

响应体统一为 `{"reason": "...", "message": "..."}`：

| reason | HTTP | 触发条件 |
|--------|------|----------|
| `NAME_CONFLICT` | 409 | 名称被其他归属方的未终结租约占用 |
| `QUOTA_EXCEEDED` | 503 | 已占用资源数达到容量上限 |
| `NOT_OWNER` | 403 | 续租/释放方与租约 owner 不一致 |
| `NOT_FOUND` | 404 | 租约名称不存在 |
| `ALREADY_RELEASED` | 409 | 对已 RELEASED 租约续租 |
| `NOT_ACTIVE` | 409 | 租约已过期/回收中（含续租竞争失败） |
| `INVALID_TTL` | 400 | TTL 超过最大允许值 |

## 配额与归属规则

- 容量由 `ephemeral.max-active-environments`（默认 4）控制。容量判定与占用在
  `LocalResourceBackend` 的同步块内原子完成，并发创建不会击穿配额；配额不足时不残留占用。
- 归属由创建时的 `owner` 标识；续租必须带相同 owner，释放默认校验 owner
  （运维侧省略 body 的释放不校验）。
- 单个环境归还失败**只影响该环境**：其他到期/释放的环境照常回收，新环境也能继续创建，
  失败环境在后续扫描中重试。

## 回收与恢复语义

- 后台调度器按 `ephemeral.reclaim-interval-millis`（默认 2s）调用一次全量回收扫描；
  release 也会立即触发一次回收，失败不抛给调用方，由后台兜底重试。
- 回收步骤幂等且可重试：状态迁移幂等、`deallocate` 幂等（重复/不存在均无副作用）；
  失败计数 `releaseAttempts` 可观察、可持久化。
- 每次租约变更与每次回收结果都会写入本地快照文件（临时文件 + 原子 rename）。
- **重启恢复**（`RecoveryService`，启动时执行）：
  1. 读取快照（文件缺失/损坏按空状态启动，不阻断服务）；
  2. 对所有非 RELEASED 租约恢复底层资源占用，使容量视图与重启前一致；
  3. 立即跑一轮回收：到期租约、停留在 RELEASING 的租约继续完成归还；
  4. 恢复后状态完全可解释：有效 ACTIVE 保留，待回收进度续跑。

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
|----|--------|------|
| `ephemeral.max-active-environments` | 4 | 同时存活环境容量上限 |
| `ephemeral.default-ttl-millis` | 60000 | ttlMillis<=0 时使用的默认租期 |
| `ephemeral.max-ttl-millis` | 3600000 | 单次续租/创建允许的最大租期 |
| `ephemeral.reclaim-interval-millis` | 2000 | 后台回收扫描间隔 |
| `ephemeral.snapshot-path` | `data/leases-snapshot.json` | 本地快照文件路径 |

## 测试与本地验证

```bash
mvn test
```

测试均为本地完成，打印 `[input]`（关键输入）与 `[judge]`（判定依据）：

- `LeaseServiceUnitTest`
  - 16 线程并发同名**同归属方**创建：单占用、单记录、全部幂等成功；
  - 12 线程并发同名**不同归属方**创建：恰 1 成功、其余全部 `NAME_CONFLICT`；
  - 配额超限 `QUOTA_EXCEEDED`，且资源无残留；
  - `NOT_OWNER` / `NOT_FOUND` / `ALREADY_RELEASED` 原因可区分；
  - 10 线程并发续租无丢失更新（version 精确累加），到期后续租竞争失败转 `EXPIRED`；
  - 时钟异常回拨不误回收有效租约。
- `ReclaimRetryTest`：注入一次性归还失败 → RELEASING + attempts 计数 + 资源不丢失；
  重试成功后 RELEASED、容量归还；重复回收无副作用；单环境失败不阻塞其他环境，且失败环境
  重试成功后新环境可立即创建。
- `RestartRecoveryTest`：进程 1 落盘（含 RELEASING 失败计数与已到期未回收租约），
  进程 2 以空内存 + 同快照恢复：有效租约与占用恢复、待回收/到期租约立即回收、容量一致。
- `LeaseApiIntegrationTest`：REST 全链路，验证 6 类 reason 与 HTTP 状态码、幂等行为。
- `ApplicationTests`：原有 Spring 上下文启动测试保持通过。
