/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.example.starlight.newui.AppConfig;
import org.starlight.terracotta.provider.AbstractTerracottaProvider;
import org.starlight.terracotta.provider.GeneralProvider;
import org.starlight.terracotta.provider.MacOSProvider;
import org.starlight.terracotta.provider.TerracottaBundleStub;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TerracottaMetadata {
    private TerracottaMetadata() {
    }

    /** Terracotta 组件的安装根目录（用户工作目录下） */
    private static final Path DEPENDENCIES_DIR = Path.of(System.getProperty("user.dir", "."), "Starlight-Launcher", "terracotta");

    private record Options(String version, String classifier) {
        public String replace(String value) {
            return value.replace("${version}", version).replace("${classifier}", classifier);
        }
    }

    public record Link(
            @SerializedName("desc") LocalizedText description,
            @SerializedName("link") String link
    ) {
    }

    private record Package(
            @SerializedName("hash") String hash,
            @SerializedName("files") Map<String, String> files
    ) {
    }

    private record Config(
            @SerializedName("version_latest") String latest,
            @SerializedName("packages") Map<String, Package> pkgs,
            @SerializedName("downloads") List<String> downloads,
            @SerializedName("downloads_CN") List<String> downloadsCN,
            @SerializedName("links") List<Link> links
    ) {
        private TerracottaBundleStub resolve(Options options) {
            Package pkg = pkgs.get(options.classifier);
            if (pkg == null) return null;

            boolean isChina = Locale.getDefault().getCountry().equalsIgnoreCase("CN");
            Stream<String> cnStream = downloadsCN.stream();
            Stream<String> intlStream = downloads.stream();
            List<URI> links = (isChina ? Stream.concat(cnStream, intlStream) : Stream.concat(intlStream, cnStream))
                    .map(link -> URI.create(options.replace(link)))
                    .toList();

            Map<String, TerracottaBundleStub.IntegrityCheck> files = pkg.files.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> new TerracottaBundleStub.IntegrityCheck("SHA-512", entry.getValue())
                    ));

            Path root = DEPENDENCIES_DIR.resolve(options.version).toAbsolutePath();

            return new TerracottaBundleStub(
                    root, links,
                    new TerracottaBundleStub.IntegrityCheck("SHA-512", pkg.hash),
                    files
            );
        }
    }

    public static final AbstractTerracottaProvider PROVIDER;
    public static final String PACKAGE_NAME;
    public static final List<Link> PACKAGE_LINKS;
    private static volatile String PACKAGE_HASH;
    public static final String FEEDBACK_LINK = "https://docs.hmcl.net/multiplayer/feedback.html?v=v1&launcher_version=StarlightLauncher-" + AppConfig.APP_VERSION;

    private static final String LATEST;

    static {
        Config config;
        try (InputStream is = TerracottaMetadata.class.getResourceAsStream("/assets/terracotta.json")) {
            if (is == null) {
                throw new ExceptionInInitializerError("Cannot find /assets/terracotta.json");
            }
            config = parseConfig(new String(is.readAllBytes()));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        if (config == null) {
            throw new ExceptionInInitializerError("Failed to parse terracotta.json");
        }

        LATEST = config.latest;

        // 获取系统架构
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        String osChecked;
        if (osName.contains("win")) {
            osChecked = "windows";
        } else if (osName.contains("mac")) {
            osChecked = "macos";
        } else if (osName.contains("linux")) {
            osChecked = "linux";
        } else {
            osChecked = osName.replaceAll("[^a-z0-9]", "");
        }

        // 检测架构，匹配 HMCL 的 classifier 命名约定
        String archChecked;
        if (osArch.contains("64") && !osArch.contains("aarch64")
                && !osArch.contains("loongarch") && !osArch.contains("riscv")) {
            archChecked = "x86_64";
        } else if (osArch.contains("aarch64")) {
            archChecked = "arm64";
        } else if (osArch.contains("loongarch64")) {
            archChecked = "loongarch64";
        } else if (osArch.contains("riscv64")) {
            archChecked = "riscv64";
        } else if (osArch.contains("86") || osArch.contains("32")) {
            archChecked = "i386";
        } else {
            archChecked = osArch.replaceAll("[^a-z0-9]", "");
        }

        Options options = new Options(config.latest, osChecked + "-" + archChecked);
        TerracottaBundleStub bundle = config.resolve(options);
        AbstractTerracottaProvider provider;
        if (bundle == null || (provider = locateProvider(bundle, options)) == null) {
            PROVIDER = null;
            PACKAGE_NAME = null;
            PACKAGE_LINKS = null;
        } else {
            PROVIDER = provider;
            PACKAGE_NAME = options.replace("terracotta-${version}-${classifier}-pkg.tar.gz");
            PACKAGE_HASH = config.pkgs.get(options.classifier).hash();

            List<Link> packageLinks = config.links.stream()
                    .map(link -> new Link(link.description(), options.replace(link.link())))
                    .collect(Collectors.toList());
            Collections.shuffle(packageLinks);
            PACKAGE_LINKS = Collections.unmodifiableList(packageLinks);
        }
    }

    /**
     * 手写解析 terracotta.json（Gson 树模型），避免对 record 类型做反射反序列化：
     * Native Image 下 record 的构造器/组件反射不可靠，直接按字段取值后编译期 new 最稳妥。
     */
    private static Config parseConfig(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String latest = root.get("version_latest").getAsString();

        Map<String, Package> pkgs = new HashMap<>();
        JsonObject packages = root.getAsJsonObject("packages");
        for (Map.Entry<String, JsonElement> e : packages.entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            Map<String, String> files = new HashMap<>();
            JsonObject fileObj = p.getAsJsonObject("files");
            for (Map.Entry<String, JsonElement> fe : fileObj.entrySet()) {
                files.put(fe.getKey(), fe.getValue().getAsString());
            }
            pkgs.put(e.getKey(), new Package(p.get("hash").getAsString(), files));
        }

        List<String> downloads = jsonStringList(root.getAsJsonArray("downloads"));
        List<String> downloadsCN = root.has("downloads_CN") && !root.get("downloads_CN").isJsonNull()
                ? jsonStringList(root.getAsJsonArray("downloads_CN")) : List.of();

        List<Link> links = new ArrayList<>();
        JsonArray linkArr = root.getAsJsonArray("links");
        for (JsonElement el : linkArr) {
            JsonObject o = el.getAsJsonObject();
            links.add(new Link(parseLocalizedText(o.get("desc")), o.get("link").getAsString()));
        }

        return new Config(latest, pkgs, downloads, downloadsCN, links);
    }

    private static List<String> jsonStringList(JsonArray arr) {
        List<String> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonNull()) list.add(el.getAsString());
        }
        return list;
    }

    /** LocalizedText 兼容 String 与 {"zh_CN": "...", "default": "..."} 两种 JSON 格式 */
    private static LocalizedText parseLocalizedText(JsonElement el) {
        if (el == null || el.isJsonNull()) return new LocalizedText("");
        if (el.isJsonPrimitive()) return new LocalizedText(el.getAsString());
        if (el.isJsonObject()) {
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonNull()) map.put(e.getKey(), e.getValue().getAsString());
            }
            return new LocalizedText(map);
        }
        return new LocalizedText("");
    }

    private static AbstractTerracottaProvider locateProvider(TerracottaBundleStub bundle, Options options) {
        String prefix = options.replace("terracotta-${version}-${classifier}");

        // Windows requires at least Windows 10 (version 10.0+)
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            double version = parseWindowsVersion(System.getProperty("os.version", ""));
            if (version < 10.0) return null;
            return new GeneralProvider(bundle, bundle.locate(prefix + ".exe"));
        } else if (osName.contains("linux") || osName.contains("nix")) {
            return new GeneralProvider(bundle, bundle.locate(prefix));
        } else if (osName.contains("mac")) {
            return new MacOSProvider(
                    bundle, bundle.locate(prefix), bundle.locate(prefix + ".pkg")
            );
        } else {
            return null;
        }
    }

    private static double parseWindowsVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length >= 2) {
                return Double.parseDouble(parts[0] + "." + parts[1]);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    public static void removeLegacyVersionFiles() {
        try (DirectoryStream<Path> terracotta = collectLegacyVersionFiles()) {
            if (terracotta == null) return;
            for (Path path : terracotta) {
                try {
                    deleteDirectory(path);
                } catch (IOException e) {
                    System.err.println("Unable to remove legacy terracotta files: " + path + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to remove legacy terracotta files: " + e.getMessage());
        }
    }

    public static String getPackageHash() {
        return PACKAGE_HASH;
    }

    public static boolean hasLegacyVersionFiles() throws IOException {
        try (DirectoryStream<Path> terracotta = collectLegacyVersionFiles()) {
            return terracotta != null && terracotta.iterator().hasNext();
        }
    }

    private static DirectoryStream<Path> collectLegacyVersionFiles() throws IOException {
        Path terracottaDir = DEPENDENCIES_DIR;
        if (Files.notExists(terracottaDir)) return null;

        return Files.newDirectoryStream(terracottaDir, path -> {
            String name = path.getFileName().toString();
            return !LATEST.equals(name) && compareVersion(name, LATEST) < 0;
        });
    }

    private static int compareVersion(String a, String b) {
        String[] partsA = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] partsB = b.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int na = i < partsA.length ? parseIntSafe(partsA[i]) : 0;
            int nb = i < partsB.length ? parseIntSafe(partsB[i]) : 0;
            if (na != nb) return na - nb;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    deleteDirectory(entry);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
