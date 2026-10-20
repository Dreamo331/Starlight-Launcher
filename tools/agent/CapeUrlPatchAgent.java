/**
 * CapeUrlPatchAgent：轻量 javaagent，把 OptiFine CapeUtils 里的披风下载 URL
 * 从 "http://s.optifine.net/capes/..." 重写为 "http://127.0.0.1:<端口>/capes/..."，
 * 使 1.17.1 纯 OptiFine 客户端的披风下载直连启动器本地披风服务器。
 *
 * 原理：premain 注册 ClassFileTransformer，对加载的类做常量池 CONSTANT_Utf8
 * 二进制替换（类文件规范允许 Utf8 条目变长，常量池按 tag 顺序扫描解析）。
 * 仅替换包含 "http://s.optifine.net" 前缀的 Utf8 常量，其余字节原样保留。
 * 不依赖 ASM / 第三方库，纯 JDK。
 *
 * 2026-10-07 修复（E0001 启动失败）：
 *   本类必须位于命名包内（com.example.starlight.agent），禁止放在无包名根目录。
 *   原因：Java 要求同一包内类的签名信息一致。Minecraft 1.16/1.20.1 的混淆类
 *   （dut、ezz 等）位于带 Mojang 签名的游戏 jar 根目录（无包名）；若 agent 的
 *   未签名类也放在无包名根目录，会被 -javaagent 先加载进系统类加载器，随后
 *   JVM 加载签名游戏类时抛出：
 *   java.lang.SecurityException: class "dut"'s signer information does not
 *   match signer information of other classes in the same package
 *   （最小复现见 temp/signer-repro，Bad/Fixed 两场景）。
 */
package com.example.starlight.agent;

import java.io.ByteArrayOutputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CapeUrlPatchAgent {

    private static final byte[] TARGET = "http://s.optifine.net".getBytes(StandardCharsets.US_ASCII);
    private static volatile String prefix;

    public static void premain(String args, Instrumentation inst) {
        if (args == null || args.isBlank()) {
            System.err.println("[CapeUrlPatchAgent] 缺少披风端口参数，放弃注入");
            return;
        }
        prefix = "http://127.0.0.1:" + args.trim();
        inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    java.security.ProtectionDomain protectionDomain,
                                    byte[] bytes) {
                if (bytes == null || prefix == null) return null;
                return patchCapeUrl(bytes);
            }
        }, false);
        System.out.println("[CapeUrlPatchAgent] 已注册披风 URL 重写：" + prefix);
    }

    /** 测试钩子：直接设置替换前缀 */
    public static void setPrefixForTest(String p) {
        prefix = p;
    }

    private static byte[] patchCapeUrl(byte[] bytes) {
        if (!contains(bytes, TARGET)) return null;
        byte[] out = rewriteConstantPool(bytes);
        if (out != null) {
            System.out.println("[CapeUrlPatchAgent] 已重写披风 URL 常量（类 "
                    + Thread.currentThread().getContextClassLoader() + "）");
        }
        return out;
    }

    /** 扫描常量池，把含 TARGET 前缀的 Utf8 替换为 prefix + 剩余后缀；无法解析时返回 null（放弃本次替换） */
    private static byte[] rewriteConstantPool(byte[] b) {
        int count = ((b[8] & 0xff) << 8) | (b[9] & 0xff);
        int pos = 10;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 32);
        out.write(b, 0, pos);
        boolean changed = false;
        for (int i = 1; i < count; i++) {
            if (pos >= b.length) return null;
            int tag = b[pos] & 0xff;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = ((b[pos + 1] & 0xff) << 8) | (b[pos + 2] & 0xff);
                    if (pos + 3 + len > b.length) return null;
                    byte[] utf = Arrays.copyOfRange(b, pos + 3, pos + 3 + len);
                    if (startsWithAscii(utf, TARGET)) {
                        byte[] suffix = Arrays.copyOfRange(utf, TARGET.length, utf.length);
                        byte[] neu = concat(prefix.getBytes(StandardCharsets.US_ASCII), suffix);
                        out.write(1);
                        out.write((neu.length >>> 8) & 0xff);
                        out.write(neu.length & 0xff);
                        out.write(neu, 0, neu.length);
                        changed = true;
                    } else {
                        out.write(b, pos, 3 + len);
                    }
                    pos += 3 + len;
                    break;
                }
                case 3:  // int
                case 4:  // float
                    out.write(b, pos, 5);
                    pos += 5;
                    break;
                case 5:  // long（占 2 个槽）
                case 6:  // double（占 2 个槽）
                    out.write(b, pos, 9);
                    pos += 9;
                    i++;
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    out.write(b, pos, 3);
                    pos += 3;
                    break;
                case 15: // MethodHandle
                    out.write(b, pos, 4);
                    pos += 4;
                    break;
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    out.write(b, pos, 5);
                    pos += 5;
                    break;
                default:
                    return null; // 未知 tag，保守放弃
            }
        }
        if (!changed) return null;
        out.write(b, pos, b.length - pos);
        return out.toByteArray();
    }

    private static boolean startsWithAscii(byte[] data, byte[] prefixBytes) {
        if (data.length < prefixBytes.length) return false;
        for (int i = 0; i < prefixBytes.length; i++) {
            if (data[i] != prefixBytes[i]) return false;
        }
        return true;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
