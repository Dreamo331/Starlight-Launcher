package com.example.starlight.download.mod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 文件校验器（对标 PCL FileChecker）。
 *
 * <p>三要素：
 * <ul>
 *   <li>期望大小 {@code expectedSize}：== -1 表示不校验大小；</li>
 *   <li>最小大小 {@code minSize}：小于此值直接判损坏（首线程探测 Content-Length 时先用它切源）；</li>
 *   <li>期望 Hash：自动按十六进制长度识别——32 位 = MD5，40 位 = SHA-1；其它长度跳过哈希校验。</li>
 * </ul>
 *
 * <p>复用点：
 * <ul>
 *   <li>下载完成合并后做一次完整 Hash 校验（失败 → 删成品 → 当合并失败处理）；</li>
 *   <li>本地复用阶段（{@link LocalFileReuse}）判定候选文件是否真的一致；</li>
 *   <li>UI「文件已损坏」自检。</li>
 * </ul>
 *
 * <p>本类不引入 Gson：{@link #isJson(Path)} 仅做轻量结构性判断（首字符 + 括号配平），
 * 不做真正的 JSON 解析——真 JSON 解析在上层 VersionDownloadService。
 */
public final class FileVerifier {

    private static final int BUFFER_SIZE = 64 * 1024;

    private FileVerifier() {
    }

    /**
     * 校验文件大小 + Hash。
     *
     * @param file         待校验文件
     * @param expectedSize 期望字节数；-1 表示不校验大小
     * @param minSize      最小字节数（&lt; 此值判损坏）；0 表示不卡下限
     * @param expectedHash 期望哈希（MD5 32 位 / SHA1 40 位十六进制，自动识别）；null/空白表示不校验哈希
     * @return 空 = 通过；非空 = 失败原因文案
     */
    public static Optional<String> verify(Path file, long expectedSize, long minSize, String expectedHash) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.of("文件不存在: " + file);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return Optional.of("无法读取文件大小: " + e.getMessage());
        }
        if (expectedSize >= 0 && size != expectedSize) {
            return Optional.of("大小不符: 期望 " + expectedSize + " 字节, 实际 " + size);
        }
        if (minSize > 0 && size < minSize) {
            return Optional.of("文件过小: " + size + " < " + minSize);
        }
        if (expectedHash != null && !expectedHash.isBlank()) {
            String algo = detectHashAlgo(expectedHash);
            if (algo != null) {
                String actual;
                try {
                    actual = hashHex(file, algo);
                } catch (Exception e) {
                    return Optional.of("哈希计算失败: " + e.getMessage());
                }
                if (!actual.equalsIgnoreCase(expectedHash.trim())) {
                    return Optional.of("哈希不符(" + algo + "): 期望 " + expectedHash + " 实际 " + actual);
                }
            }
        }
        return Optional.empty();
    }

    /** 按十六进制长度识别算法：32=MD5，40=SHA-1，其它=null（跳过） */
    private static String detectHashAlgo(String expectedHash) {
        int len = expectedHash.trim().length();
        if (len == 32) return "MD5";
        if (len == 40) return "SHA-1";
        return null;
    }

    /** 计算文件哈希并输出小写十六进制 */
    private static String hashHex(Path file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 是否「看起来是合法 JSON」（轻量结构判断，非完整解析）。
     * 规则：trim 后首字符必须是 '{' 或 '['，且 {} / [] 括号配平。
     * 用于 PCL IsJson 语义：不校验 schema，只挡掉 HTML 错误页。
     */
    public static boolean isJson(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        try {
            byte[] all = Files.readAllBytes(file);
            int i = 0;
            while (i < all.length && Character.isWhitespace(all[i])) i++;
            if (i >= all.length) return false;
            byte first = all[i];
            if (first != '{' && first != '[') return false;
            // 括号配平（忽略字符串内括号，粗粒度：遇引号跳到下一个引号）
            int curly = 0, square = 0;
            boolean inString = false;
            for (; i < all.length; i++) {
                byte b = all[i];
                if (b == '"') {
                    inString = !inString;
                } else if (!inString) {
                    if (b == '{') curly++;
                    else if (b == '}') curly--;
                    else if (b == '[') square++;
                    else if (b == ']') square--;
                    if (curly < 0 || square < 0) return false;
                }
            }
            return curly == 0 && square == 0 && !inString;
        } catch (IOException e) {
            return false;
        }
    }
}
