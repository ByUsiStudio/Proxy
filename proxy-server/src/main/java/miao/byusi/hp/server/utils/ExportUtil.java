package miao.byusi.hp.server.utils;

import cn.hserver.plugin.web.interfaces.HttpResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 数据导出工具：CSV / JSON 附件下载。
 * <p>
 * 安全要点：
 * 1) <b>CSV 公式注入</b>：Excel / WPS 会把以 {@code = + - @ Tab CR} 开头的单元格当作公式执行
 * （可触发 DDE、外链请求甚至任意命令），因此所有以这些字符开头的值都加前导单引号并按文本处理；
 * 2) 只在包含分隔符/引号/换行时加引号，引号内部的双引号翻倍，保证结构不被字段内容破坏；
 * 3) 文件名完全由服务端生成（固定前缀 + 时间戳），不接受任何客户端输入，
 * 因此不存在响应头注入（CRLF）风险；
 * 4) 导出为 UTF-8 BOM 前缀，避免 Excel 打开中文乱码。
 */
public final class ExportUtil {

    /** UTF-8 BOM，保证 Excel 正确识别编码 */
    private static final String BOM = "\uFEFF";

    private static final String[] FORMULA_PREFIX = {"=", "+", "-", "@", "\t", "\r"};

    private ExportUtil() {
    }

    /**
     * 单个 CSV 单元格的转义。
     */
    public static String cell(String value) {
        String v = value == null ? "" : value;
        // 公式注入防护：加前导单引号，Excel 会按纯文本处理
        for (String prefix : FORMULA_PREFIX) {
            if (v.startsWith(prefix)) {
                v = "'" + v;
                break;
            }
        }
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.startsWith(" ") || v.endsWith(" ");
        if (needQuote) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /**
     * 组装 CSV 文本（自动带 BOM）。
     *
     * @param headers 表头，可为 null
     * @param rows    数据行，每行长度应与表头一致
     */
    public static String csv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder(BOM);
        if (headers != null && !headers.isEmpty()) {
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(headers.get(i)));
            }
            sb.append("\r\n");
        }
        if (rows != null) {
            for (List<String> row : rows) {
                if (row == null) {
                    continue;
                }
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(cell(row.get(i)));
                }
                sb.append("\r\n");
            }
        }
        return sb.toString();
    }

    /**
     * 以附件形式写出文本响应。
     * <p>
     * 说明：HServer 的 {@code sendText/sendJson} 仅在 Content-Type 缺失时才写入默认值，
     * 因此先 setHeader 再 sendText 可以保留我们声明的类型。
     */
    public static void attachment(HttpResponse response, String filename, String contentType, String body) {
        response.setHeader("Content-Type", contentType);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeFilename(filename) + "\"");
        // 避免浏览器嗅探类型，同时避免导出内容被当作页面渲染
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.sendText(body);
    }

    /**
     * 文件名兜底：只保留字母数字、点、下划线、横线，杜绝响应头注入。
     */
    public static String safeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "export.txt";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /**
     * 生成导出文件名用的时间戳。
     */
    public static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    /**
     * 解析逗号分隔的 id 列表，去重、去空白并限制数量，避免超长请求拖垮数据库。
     *
     * @param raw 原始字符串
     * @param max 最大条数
     */
    public static java.util.List<String> parseIds(String raw, int max) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return ids;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : raw.split("[,\\s]+")) {
            String id = part.trim();
            if (id.isEmpty() || id.length() > 64) {
                continue;
            }
            // id 为 UUID / 数字，做一次白名单校验，杜绝把任意字符串带进 SQL
            boolean ok = true;
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_';
                if (!valid) {
                    ok = false;
                    break;
                }
            }
            if (!ok || !seen.add(id)) {
                continue;
            }
            ids.add(id);
            if (ids.size() >= max) {
                break;
            }
        }
        return ids;
    }
}
