### installd守护进程
#### 前言

在[PMS启动流程](https://github.com/beyond667/study/blob/master/note/PMS%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)安装流程的最后一步commit时调用executePostCommitSteps，最终会执行到Installer.dexopt来通过JNI层去真正安装，PMS只是对apk做了拷贝和解析，真正干活的是在installd守护进程。为什么不直接在PMS里做呢？因为PMS是运行在system_server进程，此进程只有systemy用户权限无root用户权限，无访问应用程序目录的权限，无法访问/data/data下的目录，而installd进程有root权限，可以访问此目录。本文基于Android13。

```shell
USER     PID  PPID VSZ       RSS     WCHAN                   ADDR NAME    
system   1717 615  19826348  296392  do_epoll_wait           0    system_server
root     1192 1    12499180  3248    binder_ioctl_write_read 0    installd
```

#### installd进程启动

Android7.0之前守护进程都在init.rc中启动，7.0之后被拆分到对应分区的etc/init目录，每个服务一个rc文件，与该服务相关的触发器，操作等也定义在同一rc文件中。

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

这时相当于在c++层的ServiceManager中添加了个installd为key的系统服务，并且不断监听客户端的binder请求。
