HexCLI 一键安装包 / HexCLI one-click installer
=================================================

这个压缩包里只有安装器、启动脚本和终端客户端，没有 Minecraft 服务端，也没有任何 mod jar。
安装器会从 Modrinth / GitHub 下载并校验（sha512）所有需要的 mod，Fabric 服务端启动器
会在第一次开服时自己从 Mojang 下载原版服务端。

This archive contains only the installer, the bootstrap scripts and the terminal client - no
Minecraft server and no mod jars. The installer downloads and verifies (sha512) every mod from
Modrinth / GitHub; the Fabric server launcher downloads the vanilla server from Mojang on first
start.

包含的文件 / Contents
---------------------
  install.sh / install.ps1 / install.bat  引导脚本：下载私有 Java 17 并调用安装器
  hexcli-installer.jar                    安装器（install / verify）
  hexcli-client.jar                       终端客户端（connect 脚本会复制到 server 目录）
  README.txt                              本文件

Linux / macOS
-------------
  chmod +x install.sh
  ./install.sh                 # 会先显示 Minecraft EULA 链接并询问 y/N
  ./install.sh -y              # 非交互（或 HEXCLI_ACCEPT_EULA=1）

之后 / then:
  cd server
  ./start-and-connect.sh       # 开服 -> 等 hexcli/client.json -> 连上客户端；退出客户端时关服
  ./start-server.sh            # 只开服（控制台可输入 hexcli 命令）
  ./connect.sh                 # 只连客户端（服务器已在运行）
  ../runtime/bin/java -jar ../hexcli-installer.jar verify --dir .   # 重新校验文件

Windows
-------
  双击 install.bat，或在 PowerShell 里：
  powershell -ExecutionPolicy Bypass -File install.ps1
  powershell -ExecutionPolicy Bypass -File install.ps1 -y      # 非交互

之后 / then:
  cd server
  start-and-connect.bat
  start-server.bat
  connect.bat

说明 / Notes
------------
* Java：脚本只使用 ./runtime 里的私有 Temurin JRE 17（首次运行自动下载并校验 sha256），
  不会修改系统 Java，也不会改 PATH。用 HEXCLI_JAVA=/path/to/java 或 -Java 指定其它 Java。
  Java is only taken from the private ./runtime (Temurin JRE 17, downloaded and sha256-verified
  on first run); the system Java and PATH are never touched. Override with HEXCLI_JAVA=... .
* 默认只监听 127.0.0.1:25565，正版验证关闭（online-mode=false）。要让别人连进来请改
  server/server.properties，并自行承担风险。
  The server listens on 127.0.0.1:25565 with online-mode=false. Edit server/server.properties
  to expose it - at your own risk.
* 首次开服需要联网（下载原版服务端 + 依赖库），可能要几分钟。
  The first start needs network access (vanilla server + libraries) and can take a few minutes.
* 重复运行 install.sh 是安全的：已存在且哈希正确的文件会跳过，eula.txt / server.properties
  不会被覆盖。
  Re-running install.sh is safe: verified files are skipped, eula.txt / server.properties are
  never overwritten.
* 用命令行玩法：./connect.sh 后直接输入 hexParse 代码（例如 num_1 num_2 add），或
  java -jar hexcli-client.jar --dir server --exec "num_1 num_2 add"。
  In the client, type hexParse code directly (e.g. num_1 num_2 add), or script it with
  --exec "num_1 num_2 add".
* 只支持 Fabric（Minecraft 1.20.1），没有 Forge 版本。
  Fabric (Minecraft 1.20.1) only; there is no Forge variant.

更多 / More: https://github.com/xm1221/MieLib/blob/main/HEXCLI.md
