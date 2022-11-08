# 问题描述
华为儿童手表进行cts问题时无法测试通过，报异常

# 测试步骤

* 1、进行cts测试

# 分析过程

* 1、从host日志和android_log当中查看异常堆栈，获取安装测试的apk
* 2、反编译apk查看对应cts测试功能和代码业务逻辑，并没有分析出来
* 3、刷对应测试版本，安装该apk
* 4、通过命令获取到该测试并执行测试用例，本地多次无法测试通过
* 5、与测试沟通上个版本测试报告无此问题
* 6、继续分析测试apk源码，发现测试用例与触摸相关
* 7、通过girrit获取两个版本之间所有提交，查看修改情况，确定了最近修改触摸相关代码
* 8、回退相关代码并编译版本，从新进行本地测试，缩小范围
* 9、最终确定代码提交

``` java
windows下单条执行
1、安装对于测试APK adb install
2、pm list instrumentation查看测试的运行容器
3、测试整包 am instrument  -w com.google.android.media.gts/android.support.test.runner.AndroidJUnitRunner
4、单个用例 am instrument -e -r class com.google.android.media.gts.WidevineYouTubePerformanceTests#testL1Cenc1080P60 -w com.google.android.media.gts/android.support.test.runner.AndroidJUnitRunner
命令格式：adb shell am instrument -e -r 测试用例名称 -w 测试runner

```
  
# 解决方案

* 1、回退本笔代码，让对应修改人员分析
* 2、与对应人员沟通，修改滑动阻率来提升性能
* 3、由于修改滑动阻率太小，修改为合适值