/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;


import com.example.starlight.util.I18N;
import com.example.starlight.config.AppDirs;
import com.example.starlight.config.Endpoints;
import com.example.starlight.download.engine.HttpDownloadEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 披风 URL 补丁 agent（{@code cape-url-patch-agent.jar}）的落地安装器。
 *
 * <h3>为什么需要它</h3>
 * {@code -javaagent:} 只能指向一个**真实存在的 jar 文件**，JVM 要在启动游戏前把它读进
 * {@code Instrumentation}。开发/打包脚本形态下这个 jar 位于工作目录的 {@code lib/}，
 * 但启动器发布形态是 GraalVM Native Image **单 EXE**：既没有 {@code lib/} 目录，
 * 进程也不是「jar + classpath」，而是整个程序编进 exe。因此必须把 agent jar
 * 内置成 <b>classpath 资源</b>，运行期再释放到用户数据目录：
 *
 * <pre>
 * lib/cape-url-patch-agent.jar          （唯一真源，随仓库分发）
 *   └─ 构建期 stage 到 target/classes/assets/agent/   （pom 的 &lt;resource&gt;）
 *        └─ 运行期释放到 ~/.starlight/cape-url-patch-agent.jar
 *             └─ 游戏 JVM 参数 -javaagent:&lt;该路径&gt;=&lt;本地披风端口&gt;
 * </pre>
 *
 * <h3>两条获取路径</h3>
 * <ol>
 *   <li><b>内置资源释放</b>（首选）：完全离线，校验和取自「内置资源自身」，
 *       所以 agent jar 重建后，磁盘上的旧副本会被自动覆盖，不存在版本错配；</li>
 *   <li><b>联网下载</b>（兜底）：仅当 exe 里没打到该资源时（构建配置遗漏等）才走，
 *       下载目标必须与 {@link #EXPECTED_SHA256} 完全一致，否则丢弃。</li>
 * </ol>
 *
 * <p><b>为什么下载必须强校验</b>：这个文件会被当作 {@code -javaagent} 载入游戏 JVM，
 * 等同于让其中的字节码在主类之前执行。校验和不符时宁可放弃披风，也不加载来源不明的 jar。</p>
 *
 * <p>开发形态（{@code mvn javafx:run} / IDEA 运行）不经过本类：
 * {@link LocalCapeProxy} 优先直接使用工作目录下的 {@code lib/cape-url-patch-agent.jar}，
 * 本地重新编译 agent 后无需改这里的常量。</p>
 */
public final class CapeAgentInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(CapeAgentInstaller.class);

    /** classpath 资源路径（pom 把 {@code lib/} 以 targetPath=assets/agent 打进来） */
    private static final String RESOURCE = "/assets/agent/cape-url-patch-agent.jar";

    /**
     * 内置副本的 SHA-256，仅用于「exe 里没有内置资源、改为联网下载」这条兜底路径。
     *
     * <p><b>重建 {@code lib/cape-url-patch-agent.jar} 后必须同步本常量</b>，否则联网兜底会
     * 因校验失败而拒绝安装（此时仍会走内置资源释放，功能不受影响，但请保持两者一致）。
     * 取当前值：{@code certutil -hashfile lib\cape-url-patch-agent.jar SHA256}。</p>
     */
    private static final String EXPECTED_SHA256 =
            "cb4af6390b85788ce6db6b2a8567860a4a7b667d8445aae0aa256baf1d940991";

    /** 低于此大小视为不完整（真实 agent jar 约 3.8KB，留足余量） */
    private static final long MIN_SIZE = 1024L;

    /** 高于此大小视为异常文件（防止把错误页面/压缩包当成 agent） */
    private static final long MAX_SIZE = 512L * 1024L;

    private CapeAgentInstaller() {
    }

    /**
     * 确保 agent jar 已就位，返回可直接写进 {@code -javaagent:} 的绝对路径。
     *
     * <p>幂等、线程安全；已就位时只做一次哈希比对，不产生任何 IO 写入。
     * 可能阻塞（兜底下载），调用方应位于启动游戏的后台线程。</p>
     *
     * @return agent jar 路径；获取失败返回 null（调用方应跳过披风注入，不能阻断启动）
     */
    public static synchronized Path ensure() {
        Path target = AppDirs.capeAgentJar();
        byte[] bundled = readBundled();
        String bundledSha = bundled == null ? null : sha256Hex(bundled);

        // 1) 磁盘上已有一份可用副本
        if (Files.isRegularFile(target) && isUsable(target, bundledSha)) {
            return target;
        }

        // 2) 内置资源释放（离线可用；agent 重建后哈希变化会覆盖旧副本）
        if (bundled != null) {
            if (writeAtomically(target, bundled)) {
                LOG.info(I18N.get("offline.t0001"), target, bundled.length);
                return target;
            }
        } else {
            LOG.warn(I18N.get("offline.t0002"), RESOURCE);
        }

        // 3) 兜底：联网下载（强校验）
        if (!isUsable(target, null)) {
            try {
                download(target);
            } catch (Exception e) {
                LOG.warn(I18N.get("offline.t0003"), e.getMessage());
            }
        }
        if (Files.isRegularFile(target) && isUsable(target, bundledSha)) {
            return target;
        }
        LOG.warn(I18N.get("offline.t0004"), target);
        return null;
    }

    /**
     * 查询已就位的 agent jar，<b>不</b>触发释放或下载。
     *
     * <p>供开发者选项页展示状态使用：查看状态不应该产生副作用。</p>
     *
     * @return 可用副本路径；不可用返回 null
     */
    public static Path installedPath() {
        Path target = AppDirs.capeAgentJar();
        byte[] bundled = readBundled();
        String bundledSha = bundled == null ? null : sha256Hex(bundled);
        return Files.isRegularFile(target) && isUsable(target, bundledSha) ? target : null;
    }

    /** 是否由本安装器负责（true=磁盘副本与内置资源一致或已通过校验） */
    public static boolean isBundled() {
        return readBundled() != null;
    }

    // 内部实现 //

    /** 读取内置资源；资源缺失或被裁剪时返回 null（不抛异常，交给下载兜底） */
    private static byte[] readBundled() {
        try (InputStream in = CapeAgentInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            byte[] data = in.readAllBytes();
            if (data.length < MIN_SIZE || data.length > MAX_SIZE) {
                LOG.warn(I18N.get("offline.t0005"), data.length);
                return null;
            }
            return data;
        } catch (IOException e) {
            LOG.warn(I18N.get("offline.t0006"), e.getMessage());
            return null;
        }
    }

    /**
     * 判断磁盘文件能否直接用。
     *
     * @param bundledSha 内置资源哈希；非 null 时要求磁盘副本与之完全一致（版本跟随 exe）
     */
    private static boolean isUsable(Path file, String bundledSha) {
        try {
            long size = Files.size(file);
            if (size < MIN_SIZE || size > MAX_SIZE) {
                return false;
            }
            String want = bundledSha != null ? bundledSha : EXPECTED_SHA256;
            if (want == null || want.isBlank()) {
                return false;
            }
            return want.equalsIgnoreCase(sha256Hex(file));
        } catch (IOException e) {
            return false;
        }
    }

    /** 先写临时文件再原子替换：半截 jar 被 JVM 当 -javaagent 读会直接启动失败 */
    private static boolean writeAtomically(Path target, byte[] data) {
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "cape-agent-", ".jar.tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            return true;
        } catch (Exception e) {
            LOG.warn(I18N.get("offline.t0007"), e.getMessage());
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /** 兜底下载：先下到临时文件，校验通过才替换正式文件 */
    private static void download(Path target) throws IOException {
        String url = Endpoints.capeAgentUrl();
        if (url == null || url.isBlank()) {
            throw new IOException(I18N.get("offline.t0008"));
        }
        if (EXPECTED_SHA256 == null || EXPECTED_SHA256.isBlank()) {
            throw new IOException(I18N.get("offline.t0009"));
        }
        LOG.info(I18N.get("offline.t0010"), url);
        Path tmp = Files.createTempFile(target.getParent(), "cape-agent-dl-", ".jar.tmp");
        try {
            HttpDownloadEngine.downloadFileWithProgress(url, tmp, null);
            String actual = sha256Hex(tmp);
            if (!EXPECTED_SHA256.equalsIgnoreCase(actual)) {
                throw new IOException(I18N.get("cape.r0001", EXPECTED_SHA256, actual));
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            LOG.info(I18N.get("offline.t0011"), target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        String hex = sha256Hex(data);
        if (hex.isEmpty()) {
            throw new IOException(I18N.get("offline.t0012"));
        }
        return hex;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            // & 0xff 是防御性写法：Byte 自动装箱后 %x 本已按无符号输出，
            // 显式掩码让「补码 → 两字符十六进制」的意图不依赖装箱规则
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
