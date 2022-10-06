# Adb 命令

## 基本指令
* 1、查看设备 adb devices
* 2、进入指定设备 adb -s 1中结果 shell
* 3、查看版本 adb version
* 4、查看日志 adb logcat
* 5、启动ADB服务 adb start-server
* 6、停止ADB服务 adb kill-server
* 7、电脑推送到手机 adb push localFile remotePath
* 8、手机拉取到电脑 adb pull localFile remotePath
* 9、安装apk adb install apkFile
* 10、卸载apk adb uninstall packageName
* 11、进入9008刷机模式： adb reboot edl / adb reboot bootloader
* 12、获取序列号adb get-serialno
* 13、查看内存占用前6 adb shell top -m 6
* 14、杀进程 adb kill
* 15、查进程 ps
* 16、挂在分区为可读写 adb remount
* 17、跑monkey adb shell monkey -v -p 包名 500
* 18、查看分辨率 wm size
* 19、设置分辨率 wm size 1080x1920
* 20、截屏 adb shell screencap -p /sdcard/01.png
* 21、发送广播 adb shell am broadcast
* 22、adb reboot resetfactory恢复出厂
* 23、adb shell top内存
* 24、adb shell showmap
* 25、adb shell dumpsys meminfo
* 26、su 进入root
* 27、date set 设置时间
* 28、free
* 29、cat
* 30、top 监控内特
## AM命令
* 1、启动app adb shell am start -n {packageName}/.* {activityName}
* 2、杀app的进程 am kill <packageName>
## 电池相关
* 1、模拟不充电 adb shell dumpsys battery unplug
* 2、设置电池电量
## 属性相关
* 1、获取属性 getprop
* 2、setting put
* 3、setting get
## PM命令
* 1、列出手机所有的包名 pm list packages
* 2、安装/卸载 pm install/uninstall
* 3、清理数据 pm clear 
* 4、测试包查询 pm list instrumentation
* 5、打印apk的安装路径 adb shell pm path PACKAGE_NAME
## 日志相关
* 1、打印log logcat
* 2、清除日志 logcat -c
* 3、日志中查找 logcat | grep -i 关键词
* 4、输出特定类型日志 logcat -v
* 5、打印在ActivityManager标签里包含start的日志 adb logcat -s ActivityManager | findstr "START"
## dumpsys相关
* 1、查看当前TOP activity  adb shell dumpsys activity | * findstr "mFocusedActivity"
* 2、查看当前TOP窗口  adb shell "dumpsys window | grep * mCurrentFocus"
* 3、查看CPU相关信息 adb shell dumpsys cpuinfo 查看CPU相关信息 
* 4、查看电池使用信息 adb shell dumpsys battery 
* 5、查看广播 dumpsys |grep BroadcastRecord 
* 6、查看当前运行activity adb shell activity dumpsys activity * activities | grep -i run
* 7、强行进入Doze模式 adb shell dumpsys deviceidle step [light|* deep] 
* 8、退出Doze模式，让手机恢复正常需要复位充电模式 adb shell * dumpsys battery reset
* 9、设置手机电量为1％ adb shell dumpsys battery set level 1
## 内存填充
* adb shell dd if=/dev/zero of=/mnt/sdcard/bigfile bs=1024000 * count=1024  
* 1、修改count大小的值，1G=1024，要填充多少自己计算
* 2、在sdcard新建个bigfile文件，大小是1G，可以多填写几个文件，但是* 这个文件名要更改，不然会覆盖掉
* 3、可通过adb shell到手机目录mnt/sdcard/ 执行 ls 查看,du -sh bigfile可以看到该文件在一直增大
* 4、bs=1024000 == bs=1g
* 5、adb shell dd if=/dev/zero of=/mnt/sdcard/bigfile 直接等待填充完毕
## Android内存查看
* 1、dumpsys -t 60 meminfo查看内存占用
* 2、dumpsys -t 60 meminfo pid查看某个进程内存各项占比
* 3、showmap pid查看某个进程具体内存

## 其他a
* adb reboot recovery 进入recovery模式
* adb fastboot flash boot boot.img 刷boot镜像
* adb shell dumpsys batterystats --enable full-wake-history
* adb shell dumpsys batterystats --reset
  
## monkey 
* adb shell monkey --kill-process-after-error --ignore-security-exceptions --ignore-crashes --pct-appswitch 90 --pct-touch 10 --throttle 10000 --ignore-timeouts --ignore-native-crashes 100000000


