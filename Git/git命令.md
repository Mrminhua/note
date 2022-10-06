# git配置
* 1、生成ssh ssh-keygen -t rsa
* 2、配置用户名 git config --global user.name 名字
* 3、配置邮箱 git config --global user.email 邮箱
* 4、配置vim git config --global core.editor vim
* 5、配置提交信息模板 git config --global commit.template 文件
* 6、防止重复输入密码  git config --global credential.helper store 
# 基本命令
* 1、查看本地修改状态 git status
* 2、添加修改到缓存 git add 目录/文件 
* 3、打开提交信息模板 git commit 或 追加时git commit --amend
* 4、提交本地代码到云端 git push orign HEAD:refs/for/分支  
* 5、给某个change追加 git push orign HEAD:refs/change/changeId
* 6、查看配置 git config --list 
* 7、重置修改到某条提交 git reset HEAD --hard
* 8、放弃某次修改 git reset HEAD~1 --soft
* 9、同步最新代码 git pull
* 10、合并差分包 git apply --reject 文件
* 11、强制拉取最新库上代码 git pull --rebase
* 12、查看提交信息 git log
* 13、打开具体的某个提交 git show commitID
* 14、查看文件的1到10行的修改情况 git blame 文件 -L "1,10"
* 15、将修改撤回到add之前 git rm 文件
* 16、查看修改内容 git diff
* 17、分支代码检出 git checkout
* 18、还原某个文件或者分支 git checkout . 或git checkout * commitId 文件
* 19、查看帮助或所有命令 git help 或git help -a
* 20、保存修改的本地 git stash
* 21、还原临时缓存 git stash pop
* 22、查看零时缓存 git stash list
# Repo命令
* 1、查看分支 repo branch
* 2、同步代码 repo sync -j4 4是指用4个线程
* 3、初始化repo repo init
* 4、repo help
# VIM操作
* 1、按i或a进入insert模式
* 2、在非insert模式下双击按键D删除整行
