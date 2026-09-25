/*
 Navicat Premium Data Transfer

 Source Server         : docker
 Source Server Type    : SQLite
 Source Server Version : 3021000
 Source Schema         : main

 Target Server Type    : SQLite
 Target Server Version : 3021000
 File Encoding         : 65001

 Date: 10/12/2020 12:27:59


 demo：alter table sys_user add column has_close_check_photo TEXT

*/

PRAGMA foreign_keys = false;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS "sys_user";
CREATE TABLE "sys_user" (
  "id" text NOT NULL,
  "username" TEXT,
  "password" TEXT,
  "type" Integer,
  "level" Integer,
  "has_close_check_photo" TEXT,
  "login_ip" TEXT,
  "login_time" TEXT,
  "create_time" TEXT,
  PRIMARY KEY ("id")
);

DROP TABLE IF EXISTS "sys_pay";
CREATE TABLE "sys_pay" (
                            "id" text NOT NULL,
                            "username" TEXT,
                            "price" TEXT,
                            "create_time" TEXT,
                            PRIMARY KEY ("id")
);

-- ----------------------------
-- Table structure for sys_port
-- ----------------------------
DROP TABLE IF EXISTS "sys_port";
CREATE TABLE "sys_port" (
  "id" text NOT NULL,
  "user_id" text NOT NULL,
  "port" Integer,
  "create_time" TEXT,
  PRIMARY KEY ("id")
);

DROP TABLE IF EXISTS "sys_domain";
CREATE TABLE "sys_domain" (
    "id" text NOT NULL,
    "user_id" text NOT NULL,
    "domain" TEXT,
    "custom_domain" TEXT,
    "create_time" TEXT,
    PRIMARY KEY ("id")
);

DROP TABLE IF EXISTS "sys_app";
CREATE TABLE "sys_app" (
  "id" text NOT NULL,
  "version_code" text NOT NULL,
  "update_content" text NOT NULL,
  "create_time" TEXT,
  PRIMARY KEY ("id")
);

DROP TABLE IF EXISTS "sys_core";
CREATE TABLE "sys_core" (
                           "id" text NOT NULL,
                           "version_code" text NOT NULL,
                           "update_content" text NOT NULL,
                           "create_time" TEXT,
                           PRIMARY KEY ("id")
);


DROP TABLE IF EXISTS "sys_statistics";
CREATE TABLE "sys_statistics" (
  "id" text NOT NULL,
  "username" text NOT NULL,
  "receive" text NOT NULL,
  "send" text NOT NULL,
  "connect_num" text NOT NULL,
  "pack_num" text NOT NULL,
  "port" Integer NOT NULL,
  "create_time" TEXT,
  PRIMARY KEY ("id")
);


DROP TABLE IF EXISTS "sys_config";
CREATE TABLE "sys_config" (
                              "id" text NOT NULL,
                              "user_id" text NOT NULL,
                              "username" text NOT NULL,
                              "password" text NOT NULL,
                              "device_id" text NOT NULL,
                              "user_host" TEXT,
                              "server_host" TEXT,
                              "type" TEXT,
                              "domain" TEXT,
                              "port" TEXT,
                              "create_time" TEXT,
                              PRIMARY KEY ("id")
);


-- 添加管理员信息
-- 【安全修复】不再内置默认密码。原脚本写入 admin/123456（以及两个演示账号的 123456），
-- 任何人都可以用这套公开凭据登录，属于严重的默认口令漏洞，故全部改为空密码。
-- 登录路径对空密码一律拒绝（见 UserServiceImpl.login/domainLogin），因此这些账号在
-- 首次登录前不可用，必须由运维人员设置强密码后启用。
-- 首次登录步骤（二选一）：
--   1) 启动服务后进入后台“用户管理”，为 admin 设置强密码；
--   2) 直接执行 SQL 把 sys_user 中 username 为 admin 的 password 改成强密码。
-- 注意：后台管理面板的入口密码来自 app.properties 的 password 配置项，与此表密码相互独立，
-- 同样不允许留空（为空时 AuthFilter 会拒绝所有后台请求）。
INSERT INTO "sys_user"("id", "username", "password", "type","create_time") VALUES ('1', 'admin', '', '1','1609660694000');

INSERT INTO "sys_user"("id", "username", "password", "type","create_time") VALUES ('2', 'heixiaoma', '', '2','1609660694000');
INSERT INTO "sys_user"("id", "username", "password", "type","create_time") VALUES ('3', 'jishunan', '', '2','1609660694000');

INSERT INTO "sys_port"("id", "user_id", "port","create_time") VALUES ('1', '2', '8888','1609660694000');
INSERT INTO "sys_port"("id", "user_id", "port","create_time") VALUES ('2', '3', '9999','1609660694000');
INSERT INTO "sys_port"("id", "user_id", "port","create_time") VALUES ('3', '3', '10000','1609660694000');
INSERT INTO "sys_port"("id", "user_id", "port","create_time") VALUES ('4', '3', '12000','1609660694000');

-- ----------------------------
-- 审计日志（后台与开放接口的可追责留痕）
-- 记录「谁 / 何时 / 来源 IP / 做了什么 / 结果」，供后台「审计日志」页查询与导出。
-- 安全约定：detail 字段只写业务标识（用户名、配置 id、文件名等），
--           **绝不写入口令、令牌、会话 ID**。
-- ----------------------------
DROP TABLE IF EXISTS "sys_audit_log";
CREATE TABLE "sys_audit_log" (
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
-- 用量配额（按账号限制隧道数 / 并发连接 / 月度流量）
-- over_limit 由统计任务在每次汇总后刷新，后台可据此看到「已超限」账号。
-- ----------------------------
DROP TABLE IF EXISTS "sys_quota";
CREATE TABLE "sys_quota" (
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
-- 隧道模板（把一组隧道配置存成模板，对指定账号一键下发）
-- items 为 JSON 数组：[{"type":"TCP","userHost":"127.0.0.1:8080","serverHost":"1.2.3.4:9090","domain":"demo","port":"8080"}]
-- 模板**不含口令**，下发时由服务端按目标账号补齐。
-- ----------------------------
DROP TABLE IF EXISTS "sys_template";
CREATE TABLE "sys_template" (
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


PRAGMA foreign_keys = true;
