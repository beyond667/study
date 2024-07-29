### 编译Aosp

#####  环境准备：
vmware安装：

```java
https://download3.vmware.com/software/wkst/file/VMware-workstation-full-16.1.1-17801498.exe  
```

Ubuntu镜像：

```java
http://mirrors.aliyun.com/ubuntu-releases/16.04/ubuntu-16.04.7-desktop-amd64.iso
```

安装git: 
```java
sudo apt install git
```
配置git

```java
关闭ssl校验
git config --global http.sslverify false
git config --global https.sslverify false
配置git 
git config --global user.name paul
git config --global user.email 552499041@qq.com
```

安装依赖工具：

```java
sudo apt install git-core libssl-dev libffi-dev gnupg flex bison gperf build-essential zip curl zlib1g-dev
sudo apt install gcc-multilib g++-multilib libc6-dev-i386 lib32ncurses5-dev x11proto-core-dev libx11-dev libz-dev ccache libgl1- mesa-dev libxml2-utils xsltproc unzip
```

Python3：

```java
cd Downloads
## 下载Python3
wget https://www.python.org/ftp/python/3.7.1/Python-3.7.1.tgz
##解压Python3：
tar xvf Python-3.7.1.tgz
##编译与安装Python3:
./configure
sudo make install
```

配置update-alternatives：

```java
sudo update-alternatives --install /usr/bin/python python /usr/bin/python2.7 2
sudo update-alternatives --install /usr/bin/python python python3的安装地址
(/usr/local/bin/python3.7) 3(权重号)
选择Python版本：
sudo update-alternatives --config python
```

---



#### 下载镜像：

AOSP官方地址：https://source.android.google.cn/setup/build/downloading  
中科大镜像：https://mirrors.ustc.edu.cn/help/aosp.html  
清华镜像：https://mirrors.tuna.tsinghua.edu.cn/help/AOSP/   

根据官方要求下载aosp源码，以清华镜像为例

```java
1 下载初始化包
curl -OC - https://mirrors.tuna.tsinghua.edu.cn/aosp-monthly/aosp-latest.tar
tar xf aosp-latest.tar
cd AOSP   # 解压得到的 AOSP 工程目录
这时 ls 的话什么也看不到，因为只有一个隐藏的 .repo 目录
repo sync # 正常同步一遍即可得到完整目录


2 建立工作目录:
mkdir WORKING_DIRECTORY
cd WORKING_DIRECTORY

初始化仓库:
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest

如果提示无法连接到 gerrit.googlesource.com，请参照git-repo的帮助页面的更新一节。
curl https://mirrors.tuna.tsinghua.edu.cn/git/git-repo -o repo
chmod +x repo
可以将如下内容复制到你的~/.bashrc里
export REPO_URL='https://mirrors.tuna.tsinghua.edu.cn/git/git-repo'

如果需要某个特定的 Android 版本(列表)：
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-13.0.0_r30
同步源码树（以后只需执行这条命令来同步）：
repo sync
```

配置完repo后，其实只用切换android特定版本并sync即可

```java
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-13.0.0_r30
repo sync
```

---

> repo sync后出现如下错误：
>
> ```csharp
> info: A new version of repo is available
> warning: repo is not tracking a remote branch, so it will not receive updates
> repo reset: error: Entry 'command.py' not uptodate. Cannot merge.
> fatal: 不能重置索引文件至版本 'v2.17.3^0'
> ```
>
> 按如下命令：
>
> ```bash
> cd ./repo/repo 
> git pull
> cd ../..
> repo sync -j4
> ```

##### 编译

```java
source build/envsoruce.bt
lunch 
或lunch aosp_oriole-userdebug /aosp_oriole_car-userdebug
make
```

##### 配置交换空间

编译时需要的最小内存为16G，个人pc就算给虚拟机16G也是不够的，所以需要再配置多些交换空间，以弥补内存不足

先检查交换空间的目录

```
swapon -s
```

假如查到的是swapfile，先停用

```
sudo swapoff /swapfile
```

再删除

```
sudo rm /swapfile
```

新建20G的交换空间，新的文件名字可以随便起

```
sudo fallocate -l 20G /swapfile
```

设置文件权限

```
sudo chmod 600 /swapfile
```

挂载

```
sudo mkswap /swapfile
```

激活启用

```
sudo swapon /swapfile
```

写入系统配置，否则系统重启后还要重新配置

```
sudo vim /etc/fstab
```

在最后一行插入

```
/swapfile swap swap defaults 0 0
```

系统只有当虚拟内存不足才会启动Swap，比如系统默认内存只有6000KB时才会启用交换空间，但是此时系统可能已经卡死，无法启动swap，所以需要更改设置

```
sudo vim /etc/sysctl.conf
```

最后一行添加：

```
vm.min_free_kbytes=1500000 #大致1.5G
```

意思是当内存不足1.5G时启用交换空间，重启系统。

***

##### 使用idegen工具生成ipr文件，方便调试

根目录编译idegen工具：

```
mmm development/tools/idegen/
```


生成android.ipr文件：

```
./development/tools/idegen/idegen.sh
```

生成android.ipr和android.imi文件。
此时打开android.ipr会非常慢，而且后面查改都会很慢，所以这里需要先把android.iml修改下，把excludeFolder标签加上所有不需要引的（正常只加framework和package即可）例如以下android.iml：

```
<?xml version="1.0" encoding="UTF-8"?>
<module version="4" relativePaths="true" type="JAVA_MODULE">
  <component name="FacetManager">
    <facet type="android" name="Android">
      <configuration />
    </facet>
  </component>
  <component name="ModuleRootManager" />
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
<sourceFolder url="file://$MODULE_DIR$/vendor" isTestSource="false" />
<sourceFolder url="file://$MODULE_DIR$/packages" isTestSource="false" />
<sourceFolder url="file://$MODULE_DIR$/frameworks" isTestSource="false" />
<excludeFolder url="file://$MODULE_DIR$/./external/emma"/>
<excludeFolder url="file://$MODULE_DIR$/./external/jdiff"/>
<excludeFolder url="file://$MODULE_DIR$/out/eclipse"/>
<excludeFolder url="file://$MODULE_DIR$/.repo"/>
<excludeFolder url="file://$MODULE_DIR$/external/bluetooth"/>
<excludeFolder url="file://$MODULE_DIR$/external/chromium"/>
<excludeFolder url="file://$MODULE_DIR$/external/icu4c"/>
<excludeFolder url="file://$MODULE_DIR$/external/webkit"/>
<excludeFolder url="file://$MODULE_DIR$/frameworks/base/docs"/>
<excludeFolder url="file://$MODULE_DIR$/out/host"/>
<excludeFolder url="file://$MODULE_DIR$/out/target/common/docs"/>
<excludeFolder url="file://$MODULE_DIR$/out/target/common/obj/JAVA_LIBRARIES/android_stubs_current_intermediates"/>
<excludeFolder url="file://$MODULE_DIR$/out/target/product"/>
<excludeFolder url="file://$MODULE_DIR$/prebuilt"/>
<excludeFolder url="file://$MODULE_DIR$/art"/>
<excludeFolder url="file://$MODULE_DIR$/bionic"/>
<excludeFolder url="file://$MODULE_DIR$/bootable"/>
<excludeFolder url="file://$MODULE_DIR$/build"/>
<excludeFolder url="file://$MODULE_DIR$/compatibility"/>
<excludeFolder url="file://$MODULE_DIR$/cts"/>
<excludeFolder url="file://$MODULE_DIR$/dalvik"/>
<excludeFolder url="file://$MODULE_DIR$/developers"/>
<excludeFolder url="file://$MODULE_DIR$/development"/>
<excludeFolder url="file://$MODULE_DIR$/device"/>
<excludeFolder url="file://$MODULE_DIR$/disregard"/>
<excludeFolder url="file://$MODULE_DIR$/external"/>
<excludeFolder url="file://$MODULE_DIR$/hardware"/>
<excludeFolder url="file://$MODULE_DIR$/kernel"/>
<excludeFolder url="file://$MODULE_DIR$/libcore"/>
<excludeFolder url="file://$MODULE_DIR$/libnativehelper"/>
<excludeFolder url="file://$MODULE_DIR$/out"/>
<excludeFolder url="file://$MODULE_DIR$/pdk"/>
<excludeFolder url="file://$MODULE_DIR$/platform_testing"/>
<excludeFolder url="file://$MODULE_DIR$/prebuilts"/>
<excludeFolder url="file://$MODULE_DIR$/sdk"/>
<excludeFolder url="file://$MODULE_DIR$/system"/>
<excludeFolder url="file://$MODULE_DIR$/test"/>
<excludeFolder url="file://$MODULE_DIR$/toolchain"/>
<excludeFolder url="file://$MODULE_DIR$/tools"/>
<excludeFolder url="file://$MODULE_DIR$/vendor"/>
<excludeFolder url="file://$MODULE_DIR$/packages"/>

    </content>
    <orderEntry type="sourceFolder" forTests="false" />
    <orderEntry type="inheritedJdk" />
    <orderEntryProperties />
  </component>
</module>


```

---



##### 运行到真机

先下载驱动，比如上面编译的是android-13.0.0_r30，就从这里找自己手机的驱动
https://developers.google.com/android/drivers?hl=zh-cn  
具体步骤，比如的我的pixel6：  
1 先找到android-13.0.0_r30标记对应的buildid：TQ1A.230205.002

```
TQ1A.230205.002	android-13.0.0_r30	Android13	Pixel 4a、Pixel 4a (5G)、Pixel 5、Pixel 5a、Pixel 6、Pixel 6 Pro、Pixel 6a、Pixel 7、Pixel 7 Pro	2023-02-05
```

2 在驱动页面那里找230205.002和pixel6适用的驱动：

```java
适用于 Android 13.0.0(TQ1A.230205.002) 的 Pixel 6 二进制文件
```

3 下载到aosp根目录，在./extract-google_devices-oriole13.sh，一直回车，直到让输入                    I ACCEPT，输入即可  
4 然后再source lunch make 三件套编译  
5 刷机：（先确定oem已开，不需要root） 

```java
adb reboot bootloader #进入fastboot模式
cd到out/target/product/oriole目录
fastboot devices # 查看手机是否连接
fastboot flashing unlock #解锁
fastboot flashall -w #刷机
```



