package com.example.starlight.download.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只单线程 host 黑名单（对标 PCL §2.1「多线程源黑名单」）。
 *
 * <p>PCL 对 {@code bmclapi / github.com / optifine.net / meloong.com / momot.rs / pcl2-server}
 * 这类源只开一个线程——这些源单连接已够快，多连反而触发风控/限流。
 * 即便源本身可用，{@link DownloadCoordinator} 的播种循环也不为其追加分裂线程。
 *
 * <p>与 {@link com.example.starlight.util.MirrorCircuitBreaker}（域名级熔断）解耦：
 * 熔断决定「这个 host 现在还能不能试」，本类决定「这个 host 允不允许挂多个分块线程」。
 *
 * <p>静态配置 + 运行时可增删。内置片段不可移除；用户自加片段（如自定义镜像）由
 * {@link #USER_FRAGMENTS} 单独跟踪，开发者选项页据此渲染可删列表并持久化到
 * {@code starlight-client.ini} 的 {@code SingleThreadHosts} 键。匹配方式是 host 包含域名片段。
 */
public final class HostPolicy {

    private static final Set<String> SINGLE_THREAD_HOST_FRAGMENTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 用户自加的片段（区别于内置默认，开发者选项页可移除；持久化由调用方负责） */
    private static final Set<String> USER_FRAGMENTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        // PCL 默认黑名单 + modrinth CDN（modrinth cdn 单连接速度已足够，多连易被风控）
        SINGLE_THREAD_HOST_FRAGMENTS.add("bmclapi");
        SINGLE_THREAD_HOST_FRAGMENTS.add("github.com");
        SINGLE_THREAD_HOST_FRAGMENTS.add("githubusercontent");
        SINGLE_THREAD_HOST_FRAGMENTS.add("modrinth");
        SINGLE_THREAD_HOST_FRAGMENTS.add("mcbbs");
        SINGLE_THREAD_HOST_FRAGMENTS.add("optifine");
        SINGLE_THREAD_HOST_FRAGMENTS.add("meloong");
        SINGLE_THREAD_HOST_FRAGMENTS.add("momot");
        SINGLE_THREAD_HOST_FRAGMENTS.add("pcl2-server");
    }

    private HostPolicy() {
    }

    /** host 是否只允许单线程（命中黑名单片段即返回 true） */
    public static boolean isSingleThreadHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase();
        for (String frag : SINGLE_THREAD_HOST_FRAGMENTS) {
            if (lower.contains(frag)) return true;
        }
        return false;
    }

    /** 动态追加一个只单线程的 host 片段（如用户自定义镜像） */
    public static void addSingleThreadHost(String fragment) {
        if (fragment != null && !fragment.isBlank()) {
            String frag = fragment.toLowerCase().trim();
            SINGLE_THREAD_HOST_FRAGMENTS.add(frag);
            USER_FRAGMENTS.add(frag);
        }
    }

    /** 动态移除一个 host 片段（内置默认片段不受影响，仅用户片段可真正移除） */
    public static void removeSingleThreadHost(String fragment) {
        if (fragment != null) {
            String frag = fragment.toLowerCase().trim();
            SINGLE_THREAD_HOST_FRAGMENTS.remove(frag);
            USER_FRAGMENTS.remove(frag);
        }
    }

    /** 用户自加片段（排序快照；开发者选项页渲染可删列表用） */
    public static List<String> userFragments() {
        return new ArrayList<>(new TreeSet<>(USER_FRAGMENTS));
    }
}
