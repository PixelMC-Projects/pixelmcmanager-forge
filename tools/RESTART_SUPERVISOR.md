# PixelMC Manager Restart Supervisor

## 中文

可选的重启托管程序，适用于 Forge 和 Fabric。需要 Python 3；没有监听端口，不随包分发任何实际服务器凭据。

- 独立开服：用 `python3 restart_host.py standalone --game-dir <游戏目录> -- <原完整开服命令>` 代替直接启动 Java。每次重启都沿用该命令及 JVM 参数。
- MCSManager：在宿主机运行 `python3 restart_host.py mcsm --game-dir <宿主机游戏目录> --instance <实例UUID> --daemon-config <daemon配置路径> --state-file <宿主机状态文件>`。宿主机目录必须对应实例实际目录；daemon配置留在宿主机，不复制给游戏容器。
- 重启由模组的空服逻辑发起。游戏内输入 `/pixelmcmanager` 查看命令；没有托管程序时，模组不会靠退出自身去冒充重启。

Windows 可用 `python` 代替 `python3`。尖括号内容替换为自己的路径/参数。托管程序须保持运行；MCSM模式可交给宿主机进程管理器托管。

## English

Optional restart supervisor shared by Forge and Fabric. Requires Python 3, opens no listening port, and includes no live credentials.

- Standalone: launch with `python3 restart_host.py standalone --game-dir <game-directory> -- <original-full-start-command>`. Restarts reuse the exact command and JVM arguments.
- MCSManager: run on the host with `python3 restart_host.py mcsm --game-dir <host-game-directory> --instance <instance-UUID> --daemon-config <daemon-config-path> --state-file <host-state-file>`. The game directory must match the instance. Keep the daemon configuration on the host, outside the game container.
- The mod requests a restart only after its empty-server conditions are met. Use `/pixelmcmanager help` for commands. The mod does not exit and pretend that a missing supervisor can restart it.

On Windows use `python` if appropriate. Replace bracketed arguments and keep the supervisor running.
