# BAT脚本

@表示只在命令行当中打印命令执行结果，而不输出具体命令

* 1、打开注册表  regedit
* 2、列出当前目录下文件 dirs
* 3、打开当前文件目录在文件管理器中 start .
* 4、打开新的cmd start
* 5、字符串查找 findstr
* 6、ping
* 7、telnet https端口443
* 8、拷贝文件copy aaa.apk apk/aaa.apk
* 9、获取当前目录下apk并输出
``` python
for %%i in (*.apk) do (
	echo %%i
)
pause
 ```
* 10、一行一行读取txt文本，并去空
``` python
for /f %%i in (test.txt) do (
	echo %%i
)
pause
```
* 11、goto循环
:test
    echo test
goto test
* 12、等待三秒 timeout /nobreak /t 3  或者ping 127.0.0.1 -n > null
* 13、>>  重定向追加
* 14、>   重定向
* 15、if
```python
if %VALUE%=="" (
    echo "value is null"
)
```










