/*
 * FreeMarker 模板语法校验器
 *
 * 为什么需要它：
 *   proxy-server / proxy-proxy 的页面是 FreeMarker 模板，而 HServer 只在真正
 *   渲染页面时才会解析模板 —— 模板里的指令拼写错误、标签未闭合、<#if> 与 </#if>
 *   不匹配等问题在编译期完全发现不了，只有用户打开页面时才 500。
 *   本工具用 FreeMarker 官方解析器把模板逐个解析一遍，从而在提交前暴露语法错误。
 *
 * 只做「解析」（parse），不做渲染，因此不需要任何数据模型。
 *
 * 用法:
 *   java -cp <freemarker.jar> tools/template-check/TemplateCheck.java \
 *        proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template
 *
 * 退出码: 0 全部解析通过；1 存在语法错误。
 */
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TemplateCheck {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: TemplateCheck <template-dir> [<template-dir> ...]");
            System.exit(2);
        }

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLogTemplateExceptions(false);
        // 与 HServer 的实际行为保持一致：FreeMarker 默认不自动转义，
        // 因此模板必须自己写 ?html。这里只解析，不关心输出格式。
        cfg.setOutputEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(freemarker.template.TemplateExceptionHandler.RETHROW_HANDLER);

        List<TemplateLoader> loaders = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        for (String arg : args) {
            Path root = Paths.get(arg).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                System.err.println("跳过：不是目录 " + root);
                continue;
            }
            roots.add(root);
            loaders.add(new FileTemplateLoader(root.toFile()));
        }
        if (loaders.isEmpty()) {
            System.err.println("没有可用的模板目录");
            System.exit(2);
        }
        cfg.setTemplateLoader(new MultiTemplateLoader(loaders.toArray(new TemplateLoader[0])));

        int total = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

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
                try {
                    // 每个目录根都注册过 loader，所以相对路径即可解析（含 <#include>）
                    Template template = cfg.getTemplate(relative);
                    // getTemplate 已完成解析；再读一次源名以确认模板对象可用
                    template.getSourceName();
                    System.out.printf("  OK    %-28s %s%n", relative, root.getFileName());
                } catch (IOException e) {
                    // FreeMarker 的 ParseException 继承自 IOException，语法错误走这里
                    failed++;
                    failures.add(relative + " -> " + firstLines(e.getMessage()));
                    System.out.printf("  FAIL  %-28s %s%n", relative, root.getFileName());
                } catch (RuntimeException e) {
                    failed++;
                    failures.add(relative + " -> " + e.getClass().getSimpleName() + ": " + firstLines(e.getMessage()));
                    System.out.printf("  FAIL  %-28s %s%n", relative, root.getFileName());
                }
            }
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("全部模板解析通过，共 " + total + " 个。");
            return;
        }
        System.out.println("存在 " + failed + " 个语法错误（共 " + total + " 个模板）：");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        System.exit(1);
    }

    private static String firstLines(String message) {
        if (message == null) {
            return "(无错误信息)";
        }
        String[] lines = message.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 3); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }
}
