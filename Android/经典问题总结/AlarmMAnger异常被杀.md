# 问题描述

被大数据监控发现用户手机存在Watchdog查杀重启问题。

# 测试步骤

* 1、长时间待机
* 2、卡欠费或者push不可用

# 分析过程

* 1、首先查看android日志当中的重启类型，可以搜索watchdog或者system server、kill process等关键词
* 2、确认问题发生的时间点、模块、调用栈
* 3、查看dropbox的调用栈AlarmManagerService的栈情况，发现wacthdog长时间没有获取锁，而AlarmManagerService正在执行重派Alarm的操作，因此怀疑此处存在死循环
* 4、紧接着从Android日志中确认是否处于IDLE和非充电场景，果然是的。
* 5、根据上述推断是闹钟对其影响了AlarmManagerService的执行
* 6、然后具体看系统中的Alarm发现，有很多Alarm的whenElpsed的时间是一个小于当前开机时间的值，因此导致此次死循环的原因就是因为Alarm对齐导致系统持锁分发Alarm，然后并未释放。
* 7、进一步查看push模块提供的周期是否有变化，发现PUHSH发送了一个为4小时的周期，导致计算存在严重问题，由于需求规定Alarm对齐的周期在3至10分钟，

# 解决方案

1、与PUSH模块沟通让其做限制
2、AlarmManagerService模块对PUSH发送的周期进行校验，如果不再范围内默认为5分钟对齐