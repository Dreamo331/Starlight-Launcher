package com.example.starlight.MinecraftLanListenerV2;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 局域网房间自动发现服务
 */
public class MinecraftLanListenerV2 {

    // 组播地址 & 端口
    private static final String MULTICAST_ADDR = "224.0.2.60";
    private static final int PORT = 4445;

    // 用于提取端口和 MOTD 的正则
    private static final Pattern AD_PATTERN = Pattern.compile("\\[AD](\\d+)\\[/AD]");
    private static final Pattern MOTD_PATTERN = Pattern.compile("\\[MOTD](.*?)\\[/MOTD]");

    /**
     * 发现房间的回调接口
     */
    public interface Callback {
        /**
         * 当发现新房间时被调用 (已经过 IP+端口 去重)
         *
         * @param host 主机 IP 地址
         * @param port 游戏端口
         * @param motd 服务器名称/世界名称
         */
        void onGameDiscovered(String host, int port, String motd);
    }

    private Thread listenerThread;
    private volatile boolean running = false;
    private Callback callback;

    /**
     * 设置回调，建议在 start() 之前调用
     */
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 启动监听 (非阻塞，内部创建守护线程)
     */
    public void start() {
        if (running) return;
        running = true;
        listenerThread = new Thread(this::listen, "LanDiscovery");
        listenerThread.setDaemon(true);      // 守护线程，主程序退出时自动结束
        listenerThread.start();
    }

    /**
     * 停止监听
     */
    public void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }

    // 监听逻辑
    private void listen() {
        System.setProperty("java.net.preferIPv4Stack", "true");  // 强制 IPv4

        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDR);

            // 遍历所有网络接口并加入组播组 (解决多网卡、虚拟网卡问题)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                try {
                    socket.joinGroup(new InetSocketAddress(group, 0), ni);
                } catch (IOException ignored) {
                    // 某些接口可能不支持组播，忽略
                }
            }

            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Set<String> discovered = new HashSet<>();  // 用于去重

            while (running && !Thread.currentThread().isInterrupted()) {
                socket.receive(packet);                     // 阻塞直到收到广播
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String host = packet.getAddress().getHostAddress();

                // 提取端口 (必须存在)
                Matcher adMatcher = AD_PATTERN.matcher(message);
                if (!adMatcher.find()) continue;
                int port = Integer.parseInt(adMatcher.group(1));

                String key = host + ":" + port;
                if (discovered.add(key)) {                  // 首次发现才通知
                    // 提取 MOTD (可选)
                    String motd = "";
                    Matcher motdMatcher = MOTD_PATTERN.matcher(message);
                    if (motdMatcher.find()) {
                        motd = motdMatcher.group(1);
                    }
                    // 通知回调
                    if (callback != null) {
                        callback.onGameDiscovered(host, port, motd);
                    }
                }
            }
        } catch (IOException e) {
            // 如果是因为 stop() 导致的异常则忽略，否则打印堆栈
            if (running) {
                e.printStackTrace();
            }
        }
    }

    /*
    // ======================== 独立运行示例 ========================
    public static void main(String[] args) throws IOException, InterruptedException {
        MinecraftLanListenerV2 listener = new MinecraftLanListenerV2();

        // 1. 设置回调：发现房间后打印信息
        listener.setCallback((host, port, motd) -> {
            System.out.println("Room found: " + host + ":" + port + "  World: " + motd);
            System.out.println("Port: " +port);
        });

        // 2. 启动监听
        listener.start();
        System.out.println("Listening for Minecraft LAN rooms... (press Enter to stop)");

        // 3. 等待用户输入 Enter 后停止
        System.in.read();
        listener.stop();
        System.out.println("Listening stopped.");
    }
    **/
}