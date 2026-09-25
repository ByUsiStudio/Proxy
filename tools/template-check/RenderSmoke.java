/*
 * FreeMarker 模板「渲染」冒烟测试
 *
 * 为什么需要它：
 *   TemplateCheck 只做**解析**，能发现语法错误，但发现不了**求值期**错误——
 *   例如 `?c` 用在了字符串上、`?size` 用在了非序列上、缺少默认值的变量、
 *   拼错的 built-in 等。这些只有在真正渲染时才会抛异常，表现为「打开页面 500」。
 *   本工具用「宽松但类型合理」的桩模型把每个模板渲染一遍，从而在提交前拦下来。
 *
 * 桩模型策略（按变量名猜测类型，避免 undefined 直接炸）：
 *   - 名字像集合（list/files/rows/byXxx…）→ 空序列
 *   - 名字像数值（page/totalRow/count/days…）→ 数字 0（`?c` 可用）
 *   - 名字像布尔（hasXxx/isXxx/enabled…）→ false
 *   - 名字像哈希（summary/quota/data/…）→ 空哈希
 *   - 其余 → 空字符串
 * 因此本测试**不能**证明页面在有数据时一定正确，但能确定「空数据时不会 500」。
 *
 * 用法:
 *   java -cp <freemarker.jar> tools/template-check/RenderSmoke.java <template-dir> [<template-dir> ...]
 * 退出码: 0 全部渲染通过；1 存在渲染错误
 */
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.SimpleHash;
import freemarker.template.SimpleNumber;
import freemarker.template.SimpleScalar;
import freemarker.template.SimpleSequence;
import freemarker.template.Template;
import freemarker.template.TemplateBooleanModel;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RenderSmoke {

    /**
     * 宽松数据模型：按「学习到的类型提示」返回合适的空值。
     * <p>
     * 类型提示由 {@link #learn} 从 FreeMarker 的报错信息里自动提取——
     * 首次渲染时按名字粗猜，报错后记下该变量应该是什么类型再重试，
     * 因此不需要人工维护一张变量表。
     */
    static final class StubModel extends SimpleHash {
        /** key → seq | num | bool | hash | str */
        final java.util.Map<String, String> hints;

        StubModel(java.util.Map<String, String> hints) {
            this.hints = hints;
        }

        @Override
        public TemplateModel get(String key) throws TemplateModelException {
            TemplateModel direct = super.get(key);
            if (direct != null) {
                return direct;
            }
            return value(key, hints.get(key));
        }

        /** 返回该变量名对应的「空值」。嵌套哈希同样使用本类，从而支持任意深度的取值。 */
        TemplateModel value(String key, String hint) {
            String kind = hint != null ? hint : guess(key);
            switch (kind) {
                case "seq":
                    return new SimpleSequence();
                case "num":
                    return new SimpleNumber(0);
                case "bool":
                    return TemplateBooleanModel.FALSE;
                case "hash":
                    // 关键：嵌套哈希也用「宽松」实现，否则 usage.sampleLimit 这类
                    // 二级取值会因键不存在而报 null，属于桩模型的局限而非模板缺陷。
                    return new StubModel(hints);
                default:
                    return new SimpleScalar("");
            }
        }

        /** 初次猜测：只保留高置信度的规则，避免把 maxLines/nodeConnections 误判成序列。 */
        static String guess(String key) {
            if (key == null || key.isEmpty()) {
                return "str";
            }
            String k = key.toLowerCase();
            if (k.startsWith("has") || k.startsWith("is") || k.startsWith("need")
                    || k.equals("enabled") || k.equals("active") || k.equals("overlimit")) {
                return "bool";
            }
            if (k.equals("list") || k.equals("files") || k.equals("rows") || k.equals("items")
                    || k.equals("payer") || k.equals("recent") || k.equals("events")
                    || k.startsWith("by") || k.endsWith("list")) {
                return "seq";
            }
            if (k.equals("page") || k.equals("pagesize") || k.equals("totalrow")
                    || k.equals("totalpage") || k.equals("days") || k.equals("count")
                    || k.equals("size") || k.equals("time") || k.contains("limit")
                    || k.contains("bytes") || k.contains("num") || k.contains("hours")) {
                return "num";
            }
            if (k.contains("summary") || k.equals("data") || k.contains("quota")
                    || k.contains("template") || k.contains("stat") || k.contains("usage")) {
                return "hash";
            }
            return "str";
        }
    }

    /**
     * 从 FreeMarker 报错中学习「某个变量应该是什么类型」。
     *
     * @return 学到的 (变量名, 类型)，无法解析时返回 null
     */
    static String[] learn(String message) {
        if (message == null) {
            return null;
        }
        // 变量名出现在 "==> xxx [in template ..." 或 "==> (a.b.c!0) [in template ..."
        // 注意要保留点号：`totals.newUsers` 需要针对**末段**记类型，
        // 否则会把根哈希 `totals` 错误地改判成数字，反而破坏二级取值。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("==>\\s*\\(?\\s*([A-Za-z_$][\\w$.]*)")
                .matcher(message);
        if (!m.find()) {
            return null;
        }
        String path = m.group(1);
        int dot = path.lastIndexOf('.');
        String name = dot >= 0 ? path.substring(dot + 1) : path;
        String kind;
        if (message.contains("Expected an extended-hash or sequence")
                || message.contains("Expected a sequence")
                || message.contains("For \"#list\" list source")) {
            kind = "seq";
        } else if (message.contains("Expected a hash")) {
            kind = "hash";
        } else if (message.contains("Expected a boolean")) {
            kind = "bool";
        } else if (message.contains("Expected a number") || message.contains("Expected a number or boolean")) {
            kind = "num";
        } else if (message.contains("Can't compare values")) {
            // 比较错误：按「左操作数被描述成序列/字符串」推断左值类型
            kind = message.contains("Left hand operand is a sequence") ? "seq" : "num";
        } else if (message.contains("Expected a string")) {
            kind = "str";
        } else {
            return null;
        }
        return new String[]{name, kind};
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: RenderSmoke <template-dir> [<template-dir> ...]");
            System.exit(2);
        }

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setOutputEncoding("UTF-8");
        // 与生产一致：不自动转义（模板自己写 ?html）
        cfg.setTemplateExceptionHandler(freemarker.template.TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);

        List<TemplateLoader> loaders = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        for (String arg : args) {
            Path root = Paths.get(arg).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                continue;
            }
            roots.add(root);
            loaders.add(new FileTemplateLoader(root.toFile()));
        }
        cfg.setTemplateLoader(new MultiTemplateLoader(loaders.toArray(new TemplateLoader[0])));

        int total = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        // 跨模板共享的类型提示：同一个变量名在多个模板里的类型通常一致，
        // 学习一次即可让后续模板少走弯路。
        java.util.Map<String, String> typeHints = new java.util.HashMap<>();

        for (Path root : roots) {
            List<Path> files;
            try (Stream<Path> stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".ftl"))
                        .sorted(Comparator.comparing(Path::toString))
                        .collect(Collectors.toList());
            }
            for (Path file : files) {
                total++;
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                String lastError = null;
                // 学习式重试：每次报错都记下「这个变量该是什么类型」，最多 20 轮
                for (int attempt = 0; attempt < 20; attempt++) {
                    try {
                        Template template = cfg.getTemplate(relative);
                        StringWriter out = new StringWriter();
                        template.process(new StubModel(typeHints), out);
                        System.out.printf("  OK    %-30s %6d 字节%n", relative, out.toString().length());
                        lastError = null;
                        break;
                    } catch (Throwable e) {
                        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                        String[] learned = learn(lastError);
                        if (learned == null) {
                            break;
                        }
                        String previous = typeHints.put(learned[0], learned[1]);
                        // 已经学过同样结论 → 再试也不会变，直接判失败
                        if (learned[1].equals(previous)) {
                            break;
                        }
                    }
                }
                if (lastError != null) {
                    failed++;
                    failures.add(relative + " -> " + firstLines(lastError));
                    System.out.printf("  FAIL  %-30s %s%n", relative, firstLines(lastError));
                }
            }
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("全部模板渲染通过（空数据桩模型），共 " + total + " 个。");
            return;
        }
        System.out.println("存在 " + failed + " 个渲染错误（共 " + total + " 个模板）：");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        System.exit(1);
    }

    private static String firstLines(String message) {
        String[] lines = message.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 3); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.length() > 220 ? sb.substring(0, 220) + "…" : sb.toString();
    }
}
