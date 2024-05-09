### installd守护进程
#### 前言

在[PMS启动流程](https://github.com/beyond667/study/blob/master/note/PMS%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)安装流程的最后一步commit时调用executePostCommitSteps，最终会执行到Installer.dexopt来通过JNI层去真正安装，PMS只是对apk做了拷贝和解析，真正干活的是在installd守护进程。为什么不直接在PMS里做呢？因为PMS是运行在system_server进程，此进程只有systemy用户权限无root用户权限，无访问应用程序目录的权限，无法访问/data/data下的目录，而installd进程有root权限，可以访问此目录。本文基于Android13。

```shell
USER     PID  PPID VSZ       RSS     WCHAN                   ADDR NAME    
system   1717 615  19826348  296392  do_epoll_wait           0    system_server
root     1192 1    12499180  3248    binder_ioctl_write_read 0    installd
```

#### installd进程启动

[开机启动](https://github.com/beyond667/study/blob/master/note/%E5%BC%80%E6%9C%BA%E6%B5%81%E7%A8%8B.md)中会启动各种rc文件，Android7.0之前守护进程都在init.rc中启动，7.0之后被拆分到对应分区的etc/init目录，每个服务一个rc文件，与该服务相关的触发器，操作等也定义在同一rc文件中。

6.0的init.rc和7.0的installd.rc：

> system/core/rootdir/init.rc #6.0的init.rc文件

```ini
//...省略其他服务
service installd /system/bin/installd
class main
socket installd stream 600 system system
```

Android7.0拆分到installd.rc，但是还是用的socket通信。Android8.0之后换成了binder通信

8.0之后的installd.rc：

> frameworks/native/cmds/installd/installd.rc

```ini
service installd /system/bin/installd
    class main
```

这里启动了installd服务，此服务定义在frameworks/native/cmds/installd/Android.bp

> frameworks/native/cmds/installd/Android.bp

```json
cc_binary {
    name: "installd",
    srcs: ["installd.cpp"], //执行installd.cpp的main方法
    init_rc: ["installd.rc"],
	//...
}
```

执行了installd.cpp的main方法

>frameworks/native/cmds/installd/installd.cpp

```cpp
int main(const int argc, char *argv[]) {
    return android::installd::installd_main(argc, argv);
}
static int installd_main(const int argc ATTRIBUTE_UNUSED, char *argv[]) {
    int ret;
    //初始化 /data 、/system目录
    if (!initialize_globals()) {
        exit(1);
    }
    //初始化 /data/misc/user
    if (initialize_directories() < 0) {
        exit(1);
    }
    //selinux校验
    if (selinux_enabled && selinux_status_open(true) < 0) {
        exit(1);
    }
    //1 注册binder服务 InstalldNativeService
    if ((ret = InstalldNativeService::start()) != android::OK) {
        exit(1);
    }

    //2 把当前线程即主线程作为binder线程，不断监听binder请求
    IPCThreadState::self()->joinThreadPool();
    //如果走到这里，说明installd进程要关闭了
    LOG(INFO) << "installd shutting down";
    return 0;
}
```

初始化/data 、/system目录，校验selinux校验过程有兴趣同学可以往深里看。

+ 注释1通过InstalldNativeService::start()去注册installd的服务
+ 注释2把当前线程即主线程作为binder线程，不断监听binder请求

其实installd作为守护进程的'守护'功能即体现在这里，此服务不断监听用户的安装卸载等binder请求并处理

> frameworks/native/cmds/installd/InstalldNativeService.cpp

```cpp
status_t InstalldNativeService::start() {
    IPCThreadState::self()->disableBackgroundScheduling(true);
    status_t ret = BinderService<InstalldNativeService>::publish();
    if (ret != android::OK) {
        return ret;
    }
    sp<ProcessState> ps(ProcessState::self());
    ps->startThreadPool();
    ps->giveThreadPoolName();
    return android::OK;
}
```

调用了BinderService<InstalldNativeService>::publish()

>frameworks/native/libs/binder/include/binder/BinderService.h

```cpp
static status_t publish(...) {
    sp<IServiceManager> sm(defaultServiceManager());
    return sm->addService(String16(SERVICE::getServiceName()), new SERVICE(), allowIsolated,dumpFlags);
}
```

调用c++层的ServiceManager.addService，传的name是泛型的getServiceName方法，即InstalldNativeService的getServiceName

> frameworks/native/cmds/installd/InstalldNativeService.h

```cpp
static char const* getServiceName() { return "installd"; }
```

这时相当于在c++层的ServiceManager中添加了个以installd为key的系统服务，并且不断监听客户端的binder请求。此时installd服务端已经准备好了。

##### 客户端调用installd服务

在[PMS启动流程](https://github.com/beyond667/study/blob/master/note/PMS%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)中执行PMS的main方法时传了installer对象

> frameworks/base/services/java/com/android/server/SystemServer.java

```java
private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
    Installer installer = mSystemServiceManager.startService(Installer.class);
	//AMS,PMS都用到了installer
    mActivityManagerService.setInstaller(installer);
    PackageManagerService.main(mSystemContext, installer...);
}
```

SystemServer进程启动了Installer服务，此Installer服务与installd守护进程的服务不是同一个，此服务相对于installd守护进程作为客户端来调用installd服务端来真正安装卸载。

>frameworks/base/services/core/java/com/android/server/pm/Installer.java

```java
public class Installer extends SystemService {
    private volatile IInstalld mInstalld = null;
    @Override
    public void onStart() {
        if (mIsolated) {
            mInstalld = null;
            mInstalldLatch.countDown();
        } else {
            connect();
        }
    }
    private void connect() {
        //1 通过binder拿installd守护进程的服务，并且linkToDeath绑定死亡监听，一旦服务端挂掉，就重新连接
        IBinder binder = ServiceManager.getService("installd");
        if (binder != null) {
            binder.linkToDeath(() -> {
                mInstalldLatch = new CountDownLatch(1);
                connect();
            }, 0);
        }

        if (binder != null) {
            //2 本地缓存installd服务端代理对象
            IInstalld installd = IInstalld.Stub.asInterface(binder);
            mInstalld = installd;
            mInstalldLatch.countDown();
        } 
    }
}
```

+ 注释1通过binder拿installd守护进程的服务，并且linkToDeath绑定死亡监听，一旦服务端挂掉，就重新连接
+ 注释2在installer客户端缓存installd服务端的代理对象，后续的安装卸载请求本质都是通过此installd的代理对象执行的

PMS里由于缓存了installer对象，所以PMS安装卸载其实也是通过installd来干活的。

##### JVM虚拟机、DVM虚拟机、 ART虚拟机

+ JVM(Java Virtual Machine)，java虚拟机，本质上就是个软件，是计算机硬件上的一层软件抽象，在这上面可以运行java程序。C语言编译后生成的汇编语言可以直接在硬件上运行，而java编译后生成的class文件只能在JVM虚拟机上运行，JVM会把class文件翻译成机器指令才能在设备上运行，即JVM是把平台无关的class文件翻译成各个平台相关的机器码来实现跨平台。
+ DVM(Dalvik Virtual Machine)，Dalvik虚拟机。运行在android设备上，可以理解成DVM是基于JVM并对其进行了改造，让其更适合在内存和处理器速度有限的移动设备上运行。与JVM不同的是，jvm是基于栈，dvm是基于寄存器的架构，复制数据时减少了大量的出入栈操作，执行效率更高些；jvm执行的是class字节码，而dvm执行的是Dalvik字节码，Dalvik字节码由class转换而来（SDK中的dx工具），并被打包到一个DEX（Dalvik Executable）可执行文件中。另外安卓每一个app都运行在独立的虚拟机中，一个app的崩溃不会影响其他虚拟机，允许运行多个虚拟机。Android4.4之前都是DVM，4.4时DVM和ART共存，5.0后废弃。
+ ART(Android Runtime)虚拟机是4.4发布的用来替换DVM的虚拟机。DVM采用JIT（just in time）即时编译，即一边编译，一边运行，运行效率较低，在安装时pms通过socket发送dex_opt指令给installd进程对dex进行优化为odex，运行时解释成机器码。而ART采用AOT（ahead of time）预编译，在安装时PMS通过binder调用installd进程的dex2oat通过静态编译把所有的dex文件编译成oat文件，编译后的oat其实是一个标准的ELF文件，只是相对于普通的ELF文件多了`oat data section`和`oat exec section`这两个字段，运行时直接运行oat文件。优点是运行时就不需要再即时编译，直接运行，运行效率高。缺点是安装过程较长。为了平衡运行时的性能，存储，安装，加装时间，7.0之后Art虚拟机进行了改进，采用混编的模式，安装时不再进行预编译，运行时进行JIT，并生成离线的Profile文件，记录“热代码”，在设备空闲或者充电时对profile文件进行预编译。这么做的好处是兼顾了安装与运行的平衡，未对全部的代码进行预编译，也节省了存储空间。

















