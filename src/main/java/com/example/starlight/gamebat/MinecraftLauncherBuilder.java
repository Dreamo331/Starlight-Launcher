/*
 * Minecraft 启动器命令生成器
 * 该程序读取指定版本的 JSON 配置文件，解析出启动所需的类路径、JVM 参数和游戏参数，
 * 并生成一个批处理文件用于启动 Minecraft。
 * 
 * 主要功能：
 * - 解析 JSON 文件中的 libraries，构建完整的类路径（classpath）
 * - 处理 JVM 参数中的占位符替换，如 ${classpath} 和 ${natives_directory}
 * - 处理游戏参数中的占位符替换，如 ${auth_player_name} 和 ${version_name}
 * - 根据 rules 字段判断是否包含某些库（如 natives）
 * - 支持用户自定义的 JVM 参数和游戏参数
 * 
 * 使用方法：
 * 1. 修改 main 方法中的配置项，如 jsonPath、gameDir、version、javaPath 等
 * 2. 运行程序后，会在当前目录下生成一个名为 launch_版本号.bat 的批处理文件
 * 3. 双击该批处理文件即可启动 Minecraft
 */
package com.example.starlight.gamebat;

import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.VersionUtils;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class MinecraftLauncherBuilder {

    public static void main(String[] args) throws Exception {
        // 配置项
        String jsonPath = "E:\\Thunder-Download\\PCL2\\PCL-Official-Version-2.8.13\\.minecraft\\versions\\1.21.1-123\\1.21.1-123.json"; // 这里填写版本 JSON 文件的路径
        String gameDir = "E:\\Thunder-Download\\PCL2\\PCL-Official-Version-2.8.13\\.minecraft"; // 这里填写 Minecraft 游戏目录的路径
        String version = "1.21.1-123";
        String javaPath = "D:\\java_8_or_17+\\java21\\JDK_21\\jdk-21.0.10+7\\bin\\java.exe"; // 这里填写 Java 可执行文件的路径
        String javaArgs = "-Xmx2G"; // 这里可以添加额外的 JVM 参数，例如：-Xmx2G -Xms1G  多个参数请用空格分隔，并且如果参数中包含空格，请用引号包裹起来，例如：-Xmx2G -Dexample=\"value with spaces\"
        String gameArgs = ""; // 这里可以添加额外的游戏参数，例如：--quickPlaySingleplayer "新的世界"  多个参数请用空格分隔，并且如果参数中包含空格，请用引号包裹起来，例如：--quickPlaySingleplayer "新的世界"
        
        //认证方式（littleSkin microsoft）
        String authType = "littleSkin";
        //账号信息（littleSkin和microsoft共用一个配置区域）
        String misname = "ding123_dream";//如果是littleSkin则填写littleSkin的账号信息，如果是microsoft则填写微软账号信息
        String msaUUID = "bf16c986ae6b45058aabf54046276e47";//如果是littleSkin则填写littleSkin的账号信息，如果是microsoft则填写微软账号信息
        String msaAccessToken = "eyJhbFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFBZ-g0";// 由于微软账号的访问令牌通常具有较短的有效期，建议在每次启动前通过微软认证流程获取最新的访问令牌，并将其填入此处。如果你使用的是 littleSkin 账号，则可以直接填写 littleSkin 提供的访问令牌。

        String command = buildLaunchCommand(jsonPath, gameDir, version, javaPath, misname, msaUUID, msaAccessToken, gameArgs, javaArgs, authType);
        
        // 输出批处理文件
        String programDir = System.getProperty("user.dir");
        String batchPath = Paths.get(programDir, "launch_" + version + ".bat").toString();
        try (java.io.PrintWriter writer = new java.io.PrintWriter(batchPath, StandardCharsets.UTF_8)) {
            writer.println("chcp 65001>nul");
            writer.println("@echo off");
            writer.println("title 启动 - " + version);
            writer.println("echo 游戏正在启动，请稍候。");
            writer.println("cd /D \"" + gameDir + "\"");
            writer.println();
            writer.println(command);
            writer.println();
            writer.println("pause");
        }
        System.out.println("Launch command generated: " + batchPath);
    }

    public static String buildLaunchCommand(String jsonPath, String gameDir, String version,
            String javaPath, String msaname, String msaUUID, String msaAccessToken, 
            String gameArgs, String extraJvmArgs, String authType) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
            // 先沿继承链合并为自包含视图，否则这里会构建出空 classpath、缺 assetIndex 参数
            com.google.gson.JsonObject merged = VersionUtils.resolveInheritedJson(
                    java.nio.file.Path.of(gameDir), version,
                    new Gson().fromJson(java.nio.file.Files.readString(java.nio.file.Path.of(jsonPath)), JsonObject.class));
            root = mapper.readTree(merged.toString());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 解析失败时回退到直接读原文件（与旧行为一致）
            root = mapper.readTree(new File(jsonPath));
        }

        // 构建 classpath
        List<String> classpathList = new ArrayList<>();
        JsonNode libraries = root.get("libraries"); // 获取 libraries 数组
        String nativeClassifier = getNativeClassifier(); // 动态获取 natives classifier

        // 同一 group:artifact[:classifier] 只保留最后出现的版本：合并型 JSON 里多版本并存时
        // （如 HNT 的 guava 15.0 与 17.0），按原顺序会让旧版先进 classpath，Forge 启动即 NoSuchMethodError
        java.util.LinkedHashMap<String, JsonNode> deduped = new java.util.LinkedHashMap<>();
        for (JsonNode lib : libraries) {
            deduped.put(libraryKey(lib), lib);
        }

        for (JsonNode lib : deduped.values()) {
            // 检查 rules，决定是否跳过该库
            if (lib.has("rules") && !allowOnWindows(lib.get("rules"))) continue;
            // 处理 downloads 字段，优先使用它提供的路径
            JsonNode downloads = lib.get("downloads");
            if (downloads != null) {
                // 常规 artifact
                if (downloads.has("artifact")) {
                    String path = downloads.get("artifact").get("path").asText();
                    classpathList.add(Paths.get(gameDir, "libraries", path).toString());
                }
                // natives classifier
                if (downloads.has("classifiers")) {
                    JsonNode nativeArtifact = downloads.get("classifiers").get(nativeClassifier);
                    if (nativeArtifact != null) {
                        String path = nativeArtifact.get("path").asText();
                        classpathList.add(Paths.get(gameDir, "libraries", path).toString());
                    }
                }
            } else {
                // 如果没有 downloads 字段，回退到旧的 maven 坐标方式
                String name = lib.get("name").asText();
                String path = mavenToPath(name);
                classpathList.add(Paths.get(gameDir, "libraries", path).toString());
            }
        }
        // 添加版本 jar
        classpathList.add(Paths.get(gameDir, "versions", version, version + ".jar").toString());
        String classpath = String.join(";", classpathList);

        //  natives 目录
        String nativesDir = Paths.get(gameDir, "versions", version, version + "-natives").toString();

        // 构建 JVM 参数
        List<String> jvmArgs = new ArrayList<>();
        JsonNode jvmNode = root.get("arguments").get("jvm");
        for (JsonNode arg : jvmNode) {
            if (arg.isTextual()) {
                String val = arg.asText()
                    .replace("${classpath}", "\"" + classpath + "\"")
                    .replace("${natives_directory}", "\"" + nativesDir + "\"")
                    .replace("${launcher_name}", "StarlightLauncher")
                    .replace("${launcher_version}", AppConfig.APP_VERSION);
        jvmArgs.add(val);
            }
        }
        // LittleSkin 外置登录 JVM 参数
        if ("littleSkin".equalsIgnoreCase(authType)) {
            jvmArgs.add("-Dminecraft.api.auth.host=https://littleskin.cn/api/yggdrasil");
        }
        // 添加额外 JVM 参数（按空格分割，支持引号包裹的含空格参数）
        if (extraJvmArgs != null && !extraJvmArgs.isEmpty()) {
            jvmArgs.addAll(splitArgsPreserveQuotes(extraJvmArgs));
        }

        // JNA native access (Java 16+ 消除 restricted method 警告)
        int javaVer = getJavaMajorVersion(javaPath);
        if (javaVer >= 16) {
            jvmArgs.add("--enable-native-access=ALL-UNNAMED");
        }

        // 构建游戏参数
        List<String> gameArgList = new ArrayList<>();

        if (gameArgs != null && !gameArgs.trim().isEmpty()) {
            gameArgList.addAll(splitArgsPreserveQuotes(gameArgs));
        }
        // 处理 gameArgs 中的占位符替换
        JsonNode gameNode = root.get("arguments").get("game");
        String assetsIndex = root.has("assets") ? root.get("assets").asText() : "legacy";
        for (JsonNode arg : gameNode) {
            if (arg.isTextual()) {
                String val = arg.asText();
                val = val.replace("${auth_player_name}", msaname.isEmpty() ? "Player" : msaname)// 这里替换玩家名称
                         .replace("${version_name}", version)// 这里替换版本名称
                         .replace("${game_directory}", gameDir)// 这里替换游戏目录
                         .replace("${assets_root}", Paths.get(gameDir, "assets").toString())// 这里替换资源根目录
                         .replace("${assets_index_name}", assetsIndex) // 动态获取
                         .replace("${auth_uuid}", msaUUID.isEmpty() ? "uuid" : msaUUID)// 这里替换玩家 UUID
                         .replace("${auth_access_token}", msaAccessToken.isEmpty() ? "token" : msaAccessToken)// 这里替换访问令牌
                         .replace("${user_type}", msaUUID.isEmpty() ? "legacy" : "msa")// 这里替换用户类型
                         .replace("${user_properties}", "{}")// 1.7.x/1.12.x 的 Main 要求该参数必填，留占位符会解析失败
                         .replace("${version_type}", "StarlightLauncher")// 这里可以替换版本类型
                         .replace("${clientid}", "00000000-0000-0000-0000-000000000000")// 客户端 ID
                         .replace("${auth_xuid}", "0");// Xbox 用户 ID
                gameArgList.add(val);// 这里添加引号以处理参数中的空格
            }
        }
        // 获取 mainClass
        String mainClass = root.get("mainClass").asText();
        //  构建完整命令
        StringBuilder cmd = new StringBuilder();
        cmd.append("\"").append(javaPath).append("\" ");
        for (String arg : jvmArgs) cmd.append(arg).append(" ");
        cmd.append(mainClass).append(" ");
        for (String arg : gameArgList) {
            if (arg.contains(" ") && !arg.startsWith("\"")) {
                cmd.append("\"").append(arg).append("\" ");
            } else {
                cmd.append(arg).append(" ");
            }
        }
        return cmd.toString().trim();
    }

    /**
     * 检测指定 Java 可执行文件的版本
     * @param javaPath Java 可执行文件路径
     * @return Java 主版本号（如 8, 11, 17, 21），检测失败返回 8
     */
    private static int getJavaMajorVersion(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    Pattern pattern = Pattern.compile("version +\"([^\"]+)\"");
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        String ver = m.group(1);
                        // Java 9+ 格式 "17.0.1"，取第一个数字
                        if (ver.startsWith("1.")) {
                            // Java 8 及以下格式 "1.8.0_321"
                            return Integer.parseInt(ver.substring(2, 3));
                        }
                        int dot = ver.indexOf('.');
                        if (dot > 0) {
                            return Integer.parseInt(ver.substring(0, dot));
                        }
                        return Integer.parseInt(ver);
                    }
                }
            }
            p.waitFor();
        } catch (IOException | InterruptedException | NumberFormatException e) {
            // 检测失败时默认返回 8，不使用 --enable-native-access
        }
        return 8;
    }
    // 处理 rules，判断在 Windows 上是否允许使用该库
    private static boolean allowOnWindows(JsonNode rules) {
        boolean allowed = false;// 默认不允许，除非有明确的 allow 规则
        for (JsonNode rule : rules) {
            String action = rule.get("action").asText();
            JsonNode os = rule.get("os");
            boolean isWindows = (os != null && os.has("name") && "windows".equals(os.get("name").asText()));
            if ("allow".equals(action) && isWindows) allowed = true;
            if ("disallow".equals(action) && isWindows) allowed = false;
        }
        return allowed;
    }
    // 根据当前操作系统动态获取 natives classifier
    private static String getNativeClassifier() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "natives-windows";
        if (os.contains("mac")) return "natives-macos";
        if (os.contains("nix") || os.contains("nux")) return "natives-linux";
        return "natives-windows";
    }
    /**
     * classpath 去重键：{@code group:artifact} + 可选 {@code :classifier}，<b>去掉版本号</b>。
     * 保留 classifier 是为了不把同一 artifact 的不同平台 natives 条目合并掉。
     */
    private static String libraryKey(JsonNode lib) {
        String name = lib.has("name") ? lib.get("name").asText() : lib.toString();
        String[] parts = name.split(":");
        if (parts.length < 3) return name;
        StringBuilder key = new StringBuilder(parts[0]).append(':').append(parts[1]);
        for (int i = 3; i < parts.length; i++) key.append(':').append(parts[i]);
        return key.toString();
    }
    // 旧的 maven 坐标转换为路径的方式，作为回退方案
    private static String mavenToPath(String name) {
        String[] parts = name.split(":");
        String group = parts[0].replace(".", "/");
        String artifact = parts[1];
        String version = parts[2];
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }
    // 简单的参数分割函数，保留引号内的内容不被分割
    private static List<String> splitArgsPreserveQuotes(String argsLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : argsLine.toCharArray()) {// 这里简单处理引号，支持双引号包裹的参数
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ' ' && !inQuotes) {// 遇到空格且不在引号内，分割参数
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {// 其他字符正常添加
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());// 添加最后一个参数
        return tokens;// 这里简单处理引号，支持双引号包裹的参数
    }
}