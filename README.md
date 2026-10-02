# 布草洗涤厂 · 收送与洗涤批次管理

面向酒店 / 医院布草的收送、分拣、洗涤、烘干烫平、配送闭环管理系统。

## 技术栈

- 后端：Spring Boot 3.3.0 + Java 17 + **MyBatis 注解方式**（主）+ Spring Data JPA（仅操作日志辅助）+ MySQL 8 + Redis
- 前端：Vue 3 + Element Plus + Vite
- 端口：前端 8282 / 后端 8382 / MySQL 3582 / Redis 6582
- 库名：`linen_laundry`

## 启动

```bash
./start.sh            # 镜像已存在时直接起
./start.sh --build    # 首次或代码变更后构建并启动
./stop.sh             # 暂停（保留容器）
./resume.sh           # 恢复
./clean.sh            # 收摊（down -v，含匿名卷）
```

## 业务主线

客户网点 → 收送单 → 洗涤批次 → 工序流转

1. **客户网点**：网点名称、联系人、结算方式、月结额度、脏布草待收量预警
2. **布草品类台账**：床单 / 被套 / 枕套 / 浴巾 / 台布 / 工服 / 手术衣 / 病号服，含规格、单价、在库数、报废率、最低周转量
3. **收送单**：收发时间、脏件数、净件数、司机、车牌、异常短少记录与赔付
4. **洗涤批次与工序流转**：预洗 → 主洗 → 漂洗 → 中和 → 脱水 → 烘干 → 烫平 → 折叠，含洗涤剂用量与水温
5. **水洗机 / 烘干机设备**：型号、容量、状态（运行 / 待机 / 故障 / 污染隔离）、运行时长、故障报修与维修单
6. **医院布草污染闭环**：登记设备污染窗口 → 确认命中并隔离批次 → 发起可追溯复洗 → 全部复洗成功后复核放行

## 页面与操作范式（顶部双排 Tab）

| 分组 | 子功能 | 操作范式 |
| --- | --- | --- |
| 收送 | 网点地图热区 | SVG 平面示意图按网点摆色块，颜色深浅 = 当日待收脏布草量，点色块右侧抽屉登记收发件数 |
| 收送 | 收送单台账 | 表格 + 明细行展开 + 送净登记 |
| 分拣 | 布草品类盘点 | 折叠台账 + 卡片上 −/+ 步进器改在库数，底部即时显示差异数与损益金额 |
| 洗涤 | 批次隧道流转 | 批次卡片按工序横排成隧道，卡片底部唯一「下一工序」按钮，推进后整体左移动画；污染批次红框隔离并禁用推进 |
| 洗涤 | 医院污染追溯 | 事件表单 + 命中批次解释 + 隔离记录 + 复洗链路 + 复核放行；并发确认 / 复洗 / 放行由数据库锁和唯一约束兜底 |
| 后整理 | 设备状态灯墙 | 深底圆灯墙（运行绿 / 待机灰 / 故障红 / 污染隔离橙），点灯轮转状态，故障灯弹维修单表单 |
| 配送 | 配送调度看板 | 按司机 / 车牌聚合收送量与送达率 |

## 数据访问

- MyBatis 注解方式：`@Select` / `@Insert` / `@Update` / `@Delete` 手写 SQL（含 join、聚合统计、条件判断）
- 嵌套映射：`@Results` + `@ResultMap` + `@Many`（收送单 → 明细行、批次 → 工序流转记录、设备 → 维修单、网点 → 最近收送单、品类 → 近期批次）与 `@One`（收送单 → 网点、批次 → 品类 / 设备）
- 动态条件：`@SelectProvider`（网点检索、收送单检索、批次隧道视图）
- Spring Data JPA：`OperationLogRepository` 负责车间操作日志落库

## 业务规则（service 层抛 BizException）

- 净件数不得大于脏件数；短少必须登记赔付金额
- 批次推进工序必须按顺序，不能跳步；最后一道折叠完成后批次收口并回补在库
- 污染事件确认后按 `batch_step.machine_id` 与影响窗口求交集命中批次；`(event_id,batch_id)` 唯一，重复确认不会生成重复隔离记录
- 隔离批次后端锁定为 `QUARANTINED`，不能推进、正常完工或删除；已正常完工后被追认污染会先扣回在库
- 复洗必须生成 `batch_type=REWASH` 的新批次并写入 `rewash_link`；同一原批次同一时间只允许一个在产复洗
- 任一关联复洗批次失败、删除或仍在生产中，污染事件不能放行；全部复洗成功后原批次才转为 `DISPOSED`
- 设备故障状态不允许排产；已确认未放行污染设备不允许排产、切灯或被复洗继续占用；批次重量不得超过设备容量
- 在库数低于最低周转量给出缺口数；在库数不得为负数

## 接口

统一 `/api/xxx` 前缀，返回 `{ok, message, data}`。

- `GET /api/dashboard/overview`、`GET /api/dashboard/logs`
- `GET /api/outlets`、`GET /api/outlets/{id}`、`POST /api/outlets/{id}/pickup`、`GET /api/outlets/{id}/orders`
- `GET /api/categories`、`GET /api/categories/shortage`、`PUT /api/categories/{id}/stock?delta=10`、`POST /api/categories/count`
- `GET /api/orders`、`POST /api/orders`、`POST /api/orders/{id}/deliver`、`GET /api/orders/dispatch-board`
- `GET /api/batches`、`GET /api/batches/steps`、`GET /api/batches/step-stats`、`POST /api/batches`、`POST /api/batches/{id}/advance`
- `GET /api/contamination/events`、`POST /api/contamination/events`、`GET /api/contamination/events/{id}`、`POST /api/contamination/events/{id}/confirm`
- `POST /api/contamination/events/rewashes`、`POST /api/contamination/events/rewashes/{rewashBatchId}/fail`、`POST /api/contamination/events/{id}/release`
- `GET /api/machines`、`POST /api/machines/{id}/next-status`、`POST /api/machines/{id}/repairs`、`POST /api/machines/repairs/{id}/finish`
