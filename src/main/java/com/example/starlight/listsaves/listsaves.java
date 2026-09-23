package com.example.starlight.listsaves;

/*
 * 该文件用于读取Minecraft版本目录下的saves文件夹中的所有存档，并将它们的名称和路径输出到控制台。
 * 该程序使用了Java的File类来访问文件系统，并使用了增强的for循环来遍历存档文件夹中的所有文件。
 * 该程序还使用了String类的endsWith方法来检查每个文件是否是一个目录，并且使用了getName方法来获取存档的名称。
 * 
 * 修改说明：不再通过ReadIni获取配置，改为从命令行参数接收存档目录和版本目录。
 * 使用方式：java com.example.starlight.listsaves.listsaves <存档目录路径> <版本目录路径>
 * 例如：java .../listsaves "D:\minecraft\.minecraft\saves" "D:\minecraft\.minecraft\versions\1.20.1"
 */

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

public class listsaves {
    /**
     * 解析存档 level.dat 中记录的游戏版本名（Data.Version.Name，如 "1.20.4"）。
     * 用于界面标注该存档对应的游戏版本。
     *
     * @param saveDir 存档目录（包含 level.dat）
     * @return 版本名；解析失败返回 null（调用方可回退到当前配置版本）
     */
    public static String parseLevelVersion(File saveDir) {
        if (saveDir == null) return null;
        File levelDat = new File(saveDir, "level.dat");
        if (!levelDat.isFile()) return null;
        try (DataInputStream dis = new DataInputStream(
                new GZIPInputStream(new FileInputStream(levelDat)))) {
            byte tagType = dis.readByte();
            if (tagType != 10) return null; // 必须是根 TAG_Compound
            short nameLength = dis.readShort();
            if (nameLength > 0) dis.skipBytes(nameLength);
            while (dis.available() > 0) {
                byte childType = dis.readByte();
                if (childType == 0) break;
                short childNameLen = dis.readShort();
                byte[] childNameBytes = new byte[childNameLen];
                dis.readFully(childNameBytes);
                String childName = new String(childNameBytes, "UTF-8");
                if ("Data".equals(childName) && childType == 10) {
                    // 遍历 Data，查找 Version compound
                    while (dis.available() > 0) {
                        byte dataChildType = dis.readByte();
                        if (dataChildType == 0) break;
                        short dataChildNameLen = dis.readShort();
                        byte[] dataChildNameBytes = new byte[dataChildNameLen];
                        dis.readFully(dataChildNameBytes);
                        String dataChildName = new String(dataChildNameBytes, "UTF-8");
                        if ("Version".equals(dataChildName) && dataChildType == 10) {
                            // 遍历 Version，查找 Name 字符串
                            while (dis.available() > 0) {
                                byte verChildType = dis.readByte();
                                if (verChildType == 0) break;
                                short verChildNameLen = dis.readShort();
                                byte[] verChildNameBytes = new byte[verChildNameLen];
                                dis.readFully(verChildNameBytes);
                                String verChildName = new String(verChildNameBytes, "UTF-8");
                                if ("Name".equals(verChildName) && verChildType == 8) {
                                    short strLen = dis.readShort();
                                    byte[] strBytes = new byte[strLen];
                                    dis.readFully(strBytes);
                                    return new String(strBytes, "UTF-8");
                                } else {
                                    skipNBTTag(dis, verChildType);
                                }
                            }
                            break;
                        } else {
                            skipNBTTag(dis, dataChildType);
                        }
                    }
                    break;
                } else {
                    skipNBTTag(dis, childType);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * 解析存档 level.dat 中的世界显示名（LevelName）。
     * 供启动器界面直接调用，避免走 main 方法的控制台输出链路。
     *
     * @param saveDir 存档目录（包含 level.dat）
     * @return 世界显示名；解析失败返回 null（调用方可回退到目录名）
     */
    public static String parseLevelName(File saveDir) {
        if (saveDir == null) return null;
        File levelDat = new File(saveDir, "level.dat");
        if (!levelDat.isFile()) return null;
        try (DataInputStream dis = new DataInputStream(
                new GZIPInputStream(new FileInputStream(levelDat)))) {
            byte tagType = dis.readByte();
            if (tagType != 10) return null; // 必须是根 TAG_Compound
            short nameLength = dis.readShort();
            if (nameLength > 0) dis.skipBytes(nameLength);
            while (dis.available() > 0) {
                byte childType = dis.readByte();
                if (childType == 0) break;
                short childNameLen = dis.readShort();
                byte[] childNameBytes = new byte[childNameLen];
                dis.readFully(childNameBytes);
                String childName = new String(childNameBytes, "UTF-8");
                if ("Data".equals(childName) && childType == 10) {
                    while (dis.available() > 0) {
                        byte dataChildType = dis.readByte();
                        if (dataChildType == 0) break;
                        short dataChildNameLen = dis.readShort();
                        byte[] dataChildNameBytes = new byte[dataChildNameLen];
                        dis.readFully(dataChildNameBytes);
                        String dataChildName = new String(dataChildNameBytes, "UTF-8");
                        if ("LevelName".equals(dataChildName) && dataChildType == 8) {
                            short strLen = dis.readShort();
                            byte[] strBytes = new byte[strLen];
                            dis.readFully(strBytes);
                            return new String(strBytes, "UTF-8");
                        } else {
                            skipNBTTag(dis, dataChildType);
                        }
                    }
                    break;
                } else {
                    skipNBTTag(dis, childType);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public static void main(String[] args) {
        // 检查命令行参数数量
        if (args.length < 2) {
            System.err.println("Usage: java listsaves <saves dir path> <versions dir path>");
            System.err.println("Example: java listsaves \"D:\\minecraft\\.minecraft\\saves\" \"D:\\minecraft\\.minecraft\\versions\\1.20.1\"");
            return;
        }

        // 从命令行参数获取存档目录和版本目录
        String savesDirPath = args[0];
        String gameVersionDir = args[1];

        // 拼接存档文件夹路径
        File savesDir = new File(savesDirPath);

        // 获取游戏版本名称
        String versionName = new File(gameVersionDir).getName();

        // 检查存档文件夹是否存在
        if (!savesDir.exists() || !savesDir.isDirectory()) {
            System.err.println("Saves directory not found: " + savesDirPath);
            return;
        }

        // 获取存档列表（只保留目录）
        String[] saveList = savesDir.list((current, name) -> new File(current, name).isDirectory());

        // 检查是否存在存档文件夹
        if (saveList == null || saveList.length == 0) {
            System.out.println("No saves found");
            return;
        }

        for (String saveName : saveList) {
            File saveDir = new File(savesDir, saveName);
            File levelDat = new File(saveDir, "level.dat");

            if (!levelDat.exists()) {
                System.out.println("Save [" + saveName + "] is missing level.dat, may be corrupted");
                continue;
            }

            // 解析level.dat文件
            String worldName = saveName;
            boolean parseSuccess = true; // 标记解析是否成功

            try (DataInputStream dis = new DataInputStream(
                    new GZIPInputStream(new FileInputStream(levelDat)))) {

                // 跳过NBT标签头，读取根Compound标签
                byte tagType = dis.readByte();
                if (tagType == 10) { // TAG_Compound
                    // 跳过根标签名称
                    short nameLength = dis.readShort();
                    if (nameLength > 0) {
                        byte[] nameBytes = new byte[nameLength];
                        dis.readFully(nameBytes);
                    }

                    // 读取Data compound
                    while (dis.available() > 0) {
                        byte childType = dis.readByte();
                        if (childType == 0) break; // TAG_End

                        short childNameLen = dis.readShort();
                        byte[] childNameBytes = new byte[childNameLen];
                        dis.readFully(childNameBytes);
                        String childName = new String(childNameBytes, "UTF-8");

                        if (childName.equals("Data") && childType == 10) {
                            // 进入Data compound，查找LevelName
                            while (dis.available() > 0) {
                                byte dataChildType = dis.readByte();
                                if (dataChildType == 0) break;

                                short dataChildNameLen = dis.readShort();
                                byte[] dataChildNameBytes = new byte[dataChildNameLen];
                                dis.readFully(dataChildNameBytes);
                                String dataChildName = new String(dataChildNameBytes, "UTF-8");

                                if (dataChildName.equals("LevelName") && dataChildType == 8) {
                                    // TAG_String
                                    short strLen = dis.readShort();
                                    byte[] strBytes = new byte[strLen];
                                    dis.readFully(strBytes);
                                    worldName = new String(strBytes, "UTF-8");
                                    break;
                                } else {
                                    // 跳过其他标签
                                    skipNBTTag(dis, dataChildType);
                                }
                            }
                            break;
                        } else {
                            // 跳过其他标签
                            skipNBTTag(dis, childType);
                        }
                    }
                }
            } catch (IOException e) {
                parseSuccess = false;
            }

            // 只有解析成功时才输出存档信息
            if (parseSuccess) {
                System.err.println("-----------------------------------");
                System.out.println("Game version: " + versionName);
                System.out.println("Save name: " + worldName);
                System.out.println("Save path: " + saveDir.getAbsolutePath());
                System.out.println("-----------------------------------");
            }
        }
    }

    private static void skipNBTTag(DataInputStream dis, byte childType) throws IOException {
        switch (childType) {
            case 1 -> // TAG_Byte
                dis.readByte();
            case 2 -> // TAG_Short
                dis.readShort();
            case 3 -> // TAG_Int
                dis.readInt();
            case 4 -> // TAG_Long
                dis.readLong();
            case 5 -> // TAG_Float
                dis.readFloat();
            case 6 -> // TAG_Double
                dis.readDouble();
            case 7 ->  {
                // TAG_Byte_Array
                int byteArrayLength = dis.readInt();
                dis.skipBytes(byteArrayLength);
            }
            case 8 ->  {
                // TAG_String
                short stringLength = dis.readShort();
                dis.skipBytes(stringLength);
            }
            case 9 ->  {
                // TAG_List
                byte listType = dis.readByte();
                int listLength = dis.readInt();
                for (int i = 0; i < listLength; i++) {
                    skipNBTTag(dis, listType);
                }
            }
            case 10 ->  {
                // TAG_Compound
                while (true) {
                    byte compoundType = dis.readByte();
                    if (compoundType == 0) break; // TAG_End
                    short compoundNameLength = dis.readShort();
                    dis.skipBytes(compoundNameLength);
                    skipNBTTag(dis, compoundType);
                }
            }
            case 11 ->  {
                // TAG_Int_Array
                int intArrayLength = dis.readInt();
                dis.skipBytes(intArrayLength * 4);
            }
            case 12 ->  {
                // TAG_Long_Array
                int longArrayLength = dis.readInt();
                dis.skipBytes(longArrayLength * 8);
            }
            default -> throw new IOException("Unknown NBT tag type: " + childType);
        }
    }
}