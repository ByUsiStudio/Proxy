-- ============================================================================
-- v16.1 增量迁移脚本（仅用于**已存在**的 db.db / sqlite 库）
--
-- 说明：
--   db/init.sql 只在数据库文件不存在时执行（见 config/SqlConfig.java），
--   因此已有部署升级时不会自动获得新表。本脚本可安全重复执行
--   （全部使用 IF NOT EXISTS，不删除任何数据）。
--
-- 用法（SQLite）：
--   sqlite3 db.db < db/migrate-v16.1.sql
-- 或在任意 SQLite 客户端中打开 db.db 后执行本文件内容。
--
-- 涉及新增：审计日志、用量配额、隧道模板。
-- ============================================================================

-- ----------------------------
-- 审计日志：后台与开放接口的可追责留痕
-- ----------------------------
CREATE TABLE IF NOT EXISTS "sys_audit_log" (
  "id" text NOT NULL,
  "actor" TEXT,
  "actor_type" TEXT,
  "action" TEXT,
  "target" TEXT,
  "detail" TEXT,
  "result" TEXT,
  "ip" TEXT,
  "user_agent" TEXT,
  "create_time" TEXT,
  PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "idx_audit_create_time" ON "sys_audit_log" ("create_time");
CREATE INDEX IF NOT EXISTS "idx_audit_action" ON "sys_audit_log" ("action");

-- ----------------------------
-- 用量配额：按账号限制隧道数 / 端口数 / 并发连接 / 月度流量
-- ----------------------------
CREATE TABLE IF NOT EXISTS "sys_quota" (
  "id" text NOT NULL,
  "user_id" text NOT NULL,
  "username" TEXT,
  "max_tunnels" Integer,
  "max_ports" Integer,
  "max_conns" Integer,
  "monthly_receive" TEXT,
  "monthly_send" TEXT,
  "enabled" Integer,
  "over_limit" TEXT,
  "over_reason" TEXT,
  "note" TEXT,
  "create_time" TEXT,
  "update_time" TEXT,
  PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "idx_quota_user" ON "sys_quota" ("user_id");

-- ----------------------------
-- 隧道模板：一组隧道配置，可对指定账号一键下发（模板不含口令）
-- ----------------------------
CREATE TABLE IF NOT EXISTS "sys_template" (
  "id" text NOT NULL,
  "name" TEXT NOT NULL,
  "description" TEXT,
  "items" TEXT,
  "created_by" TEXT,
  "apply_count" Integer,
  "create_time" TEXT,
  "update_time" TEXT,
  PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "idx_template_name" ON "sys_template" ("name");
