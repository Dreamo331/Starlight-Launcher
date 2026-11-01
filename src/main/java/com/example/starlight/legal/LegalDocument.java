package com.example.starlight.legal;

import com.example.starlight.util.I18N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 启动器内展示的三份法律文档（与 {@code docs/} 下的正本一一对应）。
 *
 * <p><b>唯一真源仍是 {@code docs/*.md}</b>：本枚举只记录「打包后的 classpath 路径」，
 * 文件由 Maven 的 {@code copy-legal-docs} 在 process-resources 阶段原样复制到
 * {@code /legal/}（见 pom.xml）。这里不存副本、不改写正文，避免条款出现两份不一致的版本。
 *
 * <p>文档哈希（SHA-256）在运行期从资源字节算出：
 * <ul>
 *   <li>同意记录里存的是<b>当次用户同意的那一版</b>的哈希；</li>
 *   <li>条款一改，哈希随之变化，旧的同意记录自动失效 → 下次启动重新弹同意页。</li>
 * </ul>
 * 这样「同意过哪一版」是可验证的，不依赖人工维护版本号。
 */
public enum LegalDocument {

    /** 免责声明 */
    DISCLAIMER("disclaimer", "/legal/免责声明.md", "legal.doc.disclaimer"),

    /** 用户服务协议（EULA） */
    EULA("eula", "/legal/用户服务协议.md", "legal.doc.eula"),

    /** 隐私政策 */
    PRIVACY("privacy", "/legal/隐私政策.md", "legal.doc.privacy");

    private static final Logger log = LoggerFactory.getLogger(LegalDocument.class);

    /** 稳定 id：写进同意记录，永不翻译（标题可以换语言，id 不行） */
    private final String id;

    /** classpath 资源路径（构建期由 docs/ 复制而来） */
    private final String resource;

    /** 标题的 i18n 键 */
    private final String titleKey;

    /** 懒加载缓存：正文 / 哈希 / 字节数 */
    private String text;
    private String sha256;
    private int byteSize;

    LegalDocument(String id, String resource, String titleKey) {
        this.id = id;
        this.resource = resource;
        this.titleKey = titleKey;
    }

    public String id() {
        return id;
    }

    /** 当前语言下的文档标题 */
    public String title() {
        return I18N.get(titleKey);
    }

    /** 文档正文（Markdown 原文）；资源缺失时返回一条可读的提示而不是抛异常 */
    public synchronized String text() {
        ensureLoaded();
        return text;
    }

    /** 正文字节的 SHA-256（小写十六进制）；资源缺失时返回 {@code "missing"} */
    public synchronized String sha256() {
        ensureLoaded();
        return sha256;
    }

    /** 正文字节数（同意记录里附带，便于核对「同意的是哪一版」） */
    public synchronized int byteSize() {
        ensureLoaded();
        return byteSize;
    }

    /** classpath 资源路径（排障日志用） */
    public String resourcePath() {
        return resource;
    }

    private void ensureLoaded() {
        if (text != null) return;
        byte[] bytes = null;
        try (InputStream in = LegalDocument.class.getResourceAsStream(resource)) {
            if (in != null) bytes = in.readAllBytes();
        } catch (Exception ignored) {
            // 落到下面的「本地 docs/ 兜底」分支
        }
        if (bytes == null || bytes.length == 0) {
            // IDE 直接运行时不一定执行过 Maven 的 process-resources 复制，
            // 此时回退到工作目录的 docs/ 正本（与 classpath 副本逐字节一致）。
            bytes = readFromDocsDir();
            if (bytes != null) {
                log.info("classpath {} 缺失，改用工作目录 docs/ 下的正本", resource);
            }
        }
        if (bytes == null || bytes.length == 0) {
            // 打包漏了 /legal/：不阻断启动，正文位置给出一条明确提示
            text = I18N.get("legal.t0017", resource);
            sha256 = "missing";
            byteSize = 0;
            return;
        }
        text = new String(bytes, StandardCharsets.UTF_8);
        sha256 = sha256Hex(bytes);
        byteSize = bytes.length;
    }

    /** 开发期兜底：从工作目录（或上一级）的 docs/ 读取同名文件；找不到返回 null */
    private byte[] readFromDocsDir() {
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        for (Path dir : List.of(Path.of("docs"), Path.of("..", "docs"))) {
            try {
                Path candidate = dir.resolve(fileName);
                if (Files.isRegularFile(candidate)) return Files.readAllBytes(candidate);
            } catch (Exception ignored) {
                // 权限 / 编码问题都按「找不到」处理，继续试下一个候选目录
            }
        }
        return null;
    }

    /** 十六进制 SHA-256（JDK 自带，不引第三方依赖） */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 算法必然存在；真出现异常时退化为不可用标记，让同意记录自然失效
            return "unavailable";
        }
    }
}
