package com.example.starlight.main;

import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.gamebat.MinecraftLauncherBuilder;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.installjava.JavaInstall;
import com.example.starlight.listjava.FindAllJavaWindows;
import com.example.starlight.listsaves.listsaves;

/**
 * 命令行参数处理器
 * 负责解析命令行参数并分发到对应的功能模块
 */
public class CliHandler {

    /**
     * 处理命令行参数
     */
    public static void processCommandArgs(String[] args) {
        switch (args[0].toLowerCase()) {
            case "launch"  -> MenuHandler.launchMinecraft(null);
            case "quicklaunch" -> MenuHandler.quickLaunchMinecraft();
            case "login" -> MenuHandler.accountManagement();
            case "accounts" -> {
                var accounts = AccountManager.listAccounts();
                if (accounts.isEmpty()) {
                    System.out.println("No saved accounts. Use 'account-add-offline <player-name>' to create an offline account.");
                } else {
                    Account current = AccountManager.getCurrentAccount();
                    System.out.println("Saved accounts (" + accounts.size() + "):");
                    for (Account a : accounts) {
                        String marker = current != null && a.id.equals(current.id) ? " ← 当前" : "";
                        System.out.println("  " + a.displayName() + marker);
                    }
                }
            }
            case "account-add-offline" -> {
                String name = args.length >= 2 ? args[1] : "Player";
                AccountManager.loginOffline(name);
                System.out.println("Offline account '" + name + "' created successfully!");
            }
            case "account-add-microsoft" -> {
                System.out.println("Starting Microsoft login (device code flow)...");
                try {
                    AccountManager.loginMicrosoftSync();
                    System.out.println("Microsoft login successful!");
                } catch (Exception e) {
                    System.err.println("Microsoft login failed: " + e.getMessage());
                }
            }
            case "account-add-thirdparty" -> {
                if (args.length < 4) {
                    System.err.println("Usage: account-add-thirdparty <server-url> <email> <password>");
                    return;
                }
                try {
                    AccountManager.loginThirdPartySync(args[1], args[2], args[3]);
                    System.out.println("Third-party login successful!");
                } catch (Exception e) {
                    System.err.println("Third-party login failed: " + e.getMessage());
                }
            }
            case "account-switch" -> {
                if (args.length < 2) {
                    System.err.println("Usage: account-switch <UUID>");
                    return;
                }
                if (AccountManager.setCurrentAccount(args[1])) {
                    Account acc = AccountManager.getCurrentAccount();
                    System.out.println("Switched to account: " + (acc != null ? acc.displayName() : args[1]));
                } else {
                    System.err.println("No account found with UUID '" + args[1] + "'. Use 'accounts' to list all accounts.");
                }
            }
            case "account-remove" -> {
                if (args.length < 2) {
                    System.err.println("Usage: account-remove <UUID>");
                    return;
                }
                if (AccountManager.removeAccount(args[1])) {
                    System.out.println("Removed account: " + args[1]);
                } else {
                    System.err.println("No account found with UUID '" + args[1] + "'.");
                }
            }
            case "listjava" -> FindAllJavaWindows.main(new String[0]);
            case "installjava" -> {
                String[] jArgs = Arrays.copyOfRange(args, 1, args.length);
                JavaInstall.main(jArgs);
            }
            case "listsaves" -> {
                if (args.length >= 3) {
                    listsaves.main(new String[]{args[1], args[2]});
                } else {
                    System.err.println("Usage: listsaves <saves-dir> <version-dir>");
                }
            }
            case "frp" -> {
                String addr = args.length >= 2 ? args[1] : "127.0.0.1:25565";
                MenuHandler.startFrpTunnel(addr);
            }
            case "sendlogs" -> {
                if (args.length >= 3) {
                    MenuHandler.sendLogToServer(args[1], args[2]);
                } else {
                    System.err.println("Usage: sendlogs <log-path> <server-url>");
                }
            }
            case "ailog" -> {
                if (args.length >= 2) {
                    MenuHandler.aiAnalyzeLogFile(args[1]);
                } else {
                    System.err.println("Usage: ailog <log-file-path>");
                }
            }
            case "genbat" -> {
                String[] bArgs = Arrays.copyOfRange(args, 1, args.length);
                try {
                    MinecraftLauncherBuilder.main(bArgs);
                } catch (Exception e) {
                    System.err.println("Failed to generate launch script: " + e.getMessage());
                }
            }
            case "configure" -> {
                Scanner scanner = ConfigManager.createScanner();
                Map<String, String> cfg = ConfigManager.readConfig();
                ConfigManager.interactiveConfig(scanner, cfg);
            }
            case "listmods" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                String version = cfg.getOrDefault("Version", "");
                var mods = UIGeneralControlClass.listMods(gameDir, version);
                System.out.println("Mod list (" + mods.size() + "):");
                for (var m : mods) {
                    System.out.println("  " + (m.enabled ? "[]" : "[]") + " " + m.name + " (" + m.origin + ")");
                }
            }
            case "togglemod" -> {
                if (args.length < 2) {
                    System.err.println("Usage: togglemod <mod-path>");
                    return;
                }
                var result = UIGeneralControlClass.toggleMod(args[1]);
                System.out.println(result.message);
            }
            case "listresourcepacks" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                var packs = UIGeneralControlClass.listResourcePacks(gameDir);
                System.out.println("Resource pack list (" + packs.size() + "):");
                for (var p : packs) {
                    System.out.println("  " + (p.active ? "[]" : "[ ]") + " " + p.name);
                }
            }
            case "listshaders" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                var packs = UIGeneralControlClass.listShaderPacks(gameDir);
                System.out.println("Shader pack list (" + packs.size() + "):");
                for (var p : packs) {
                    System.out.println("  " + (p.active ? "[]" : "[ ]") + " " + p.name);
                }
            }
            case "listconfigs" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                var files = UIGeneralControlClass.listConfigFiles(gameDir);
                System.out.println("Mod config files (" + files.size() + "):");
                for (var f : files) {
                    System.out.println("  " + f.name + " (" + f.size + " bytes)");
                }
            }
            case "viewconfig" -> {
                if (args.length < 2) {
                    System.err.println("Usage: viewconfig <file-path>");
                    return;
                }
                var result = UIGeneralControlClass.readConfigFileContent(args[1]);
                if (result.success) {
                    System.out.println(result.data);
                } else {
                    System.err.println(result.message);
                }
            }
            case "editoptions" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                var options = UIGeneralControlClass.readOptionsTxt(gameDir);
                System.out.println("options.txt current settings (" + options.size() + " entries):");
                options.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                if (args.length >= 3) {
                    // editoptions <key> <value>
                    var result = UIGeneralControlClass.updateOptionsTxt(gameDir, args[1], args[2]);
                    System.out.println(result.message);
                } else {
                    System.out.println("Usage: editoptions <key> <value> to modify an entry");
                }
            }
            case "profiles" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                var info = UIGeneralControlClass.readLauncherProfiles(gameDir);
                if (info != null) {
                    System.out.println("--- Launcher Profile Info ---");
                    System.out.println("Current account: " + info.username);
                    System.out.println("Account count: " + info.accountCount);
                    System.out.println("Version profiles (" + info.profiles.size() + "):");
                    info.profiles.forEach((name, ver) -> System.out.println("  " + name + " -> " + ver));
                } else {
                    System.out.println("launcher_profiles.json not found or unreadable");
                }
            }
            case "complementnatives" -> {
                Map<String, String> cfg = UIGeneralControlClass.readConfig();
                String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
                String version = cfg.getOrDefault("Version", "");
                var status = UIGeneralControlClass.checkNativesStatus(gameDir, version);
                System.out.println("Native library status: " + (status.ready ? "Ready ( " + status.fileCount + " files)" :
                    "Incomplete (currently " + status.fileCount + " files)"));
                if (!status.ready) {
                    System.out.println("Completing native libraries, please wait...");
                    UIGeneralControlClass.complementNativesAsync(gameDir, version,
                        (pct, msg) -> System.out.println("  [" + pct + "%] " + msg), null);
                }
            }
            case "help", "-h", "--help" -> printHelp();
            default -> {
                System.err.println("Unknown command: " + args[0]);
                printHelp();
            }
        }
    }

    /**
     * 打印帮助信息
     */
    public static void printHelp() {
        System.out.println("Starlight Launcher CLI - Command Line Launcher");
        System.out.println();
        System.out.println("Usage: java -jar starlight-launcher.jar [command] [args...]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  launch                   Launch Minecraft game (interactive setup)");
        System.out.println("  quicklaunch              Quick launch game (skip setup, launch directly)");
        System.out.println("  login                    Account management menu (interactive)");
        System.out.println("  accounts                 List all saved accounts");
        System.out.println("  account-add-offline <player-name>  Create offline account");
        System.out.println("  listjava                 Find local Java installations");
        System.out.println("  installjava <version>    Install JDK (jdk8/jdk17/jdk21)");
        System.out.println("  listsaves <saves-dir> <version-dir>  List saves");
        System.out.println("  frp [addr]               Start FRP tunnel (default 127.0.0.1:25565)");
        System.out.println("  sendlogs <log-path> <URL> Send logs");
        System.out.println("  ailog <log-path>         AI analyze error log");
        System.out.println("  genbat                   Generate launch script (.bat)");
        System.out.println("  configure                Configure INI file");
        System.out.println("  listmods                 List mods");
        System.out.println("  togglemod <mod-path>     Toggle mod enable/disable");
        System.out.println("  listresourcepacks        List resource packs");
        System.out.println("  listshaders              List shader packs");
        System.out.println("  listconfigs              List mod config files");
        System.out.println("  viewconfig <file-path>   View config file content");
        System.out.println("  editoptions [key] [value]  View/edit options.txt");
        System.out.println("  profiles                 View launcher profile info");
        System.out.println("  complementnatives        Complete native library files");
        System.out.println();
        System.out.println("Account management (detailed):");
        System.out.println("  account-add-microsoft    Microsoft login (device code flow)");
        System.out.println("  account-add-thirdparty <server> <email> <password>  Third-party login");
        System.out.println("  account-switch <UUID>    Switch current account");
        System.out.println("  account-remove <UUID>    Remove account");
        System.out.println("  help                     Show this help");
        System.out.println();
        System.out.println("Run without arguments to enter interactive menu mode");
    }
}
