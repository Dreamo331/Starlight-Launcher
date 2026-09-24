chcp 65001

title Minecraft Client Builder

rmdir /s /q build dist *.spec

pyinstaller --onefile --console --name "SL-MinecraftLAN" --add-data "room;room" --add-data "network;network" --add-data "utils;utils" --hidden-import websockets --hidden-import Crypto --hidden-import Crypto.Cipher.AES --hidden-import Crypto.Util.Padding main.py