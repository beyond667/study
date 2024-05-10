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

```c++
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
+ ART(Android Runtime)虚拟机是4.4发布的用来替换DVM的虚拟机。DVM采用JIT（just in time）即时编译，即一边编译，一边运行，运行效率较低，在安装时pms通过socket发送dex_opt指令给installd进程对dex进行优化为odex，运行时解释成机器码。而ART采用AOT（ahead of time）预编译，在安装时PMS通过binder调用installd进程的dex2oat通过静态编译把所有的dex文件编译成oat文件，编译后的oat其实是一个标准的ELF文件，只是相对于普通的ELF文件多了`oat data section`和`oat exec section`这两个字段，运行时直接运行oat文件。优点是运行时就不需要再即时编译，直接运行，运行效率高。缺点是安装过程较长。为了平衡运行时的性能，存储，安装，加装时间，7.0之后Art虚拟机进行了改进，采用混编的模式，安装时不再进行预编译，运行时进行JIT，并生成离线的Profile文件，记录“热代码”，在设备idle或者充电时对profile文件进行预编译。这么做的好处是兼顾了安装与运行的平衡，未对全部的代码进行预编译，也节省了存储空间。

#### dex优化过程

了解了ART虚拟机后，我们继续分析前言里安装过程的最后一步executePostCommitSteps

> frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java

```java
private void executePostCommitSteps(CommitRequest commitRequest) {
    //...
    // Prepare the application profiles for the new code paths.
    // This needs to be done before invoking dexopt so that any install-time profile
    // can be used for optimizations.
    //1 先准备profile
    mArtManagerService.prepareAppProfiles( pkg,mPm.resolveUserIds,true);
    
    //2 dex优化
    mPackageDexOptimizer.performDexOpt(pkg, realPkgSetting,null...);
    //...
}
```

+ 注释1先通过ArtManagerService.prepareAppProfiles去准备Profile文件，英文注释解释了此操作需要在dexopt优化前做以便所有安装时的profile文件（install-time profile）也能优化，其实就是app版本更新时需要先强制生成一次最新的profile，以便dex优化时直接编译，对于首次安装生成空的profile(个人猜测，未验证)。
+ 注释2执行dex优化

需要说明的是，对Profile文件的管理主要是通过ArtManagerService来做的，而ArtManagerService内部持有了installer实例，本质也是通过installd来干活的。这里会先生成profile再进行dex优化

##### Profile文件的生成

先看注释1的prepareAppProfiles

>frameworks/base/services/core/java/com/android/server/pm/dex/ArtManagerService.java

```java
private final Installer mInstaller;
public ArtManagerService(Context context, Installer installer,Object ignored) {
    mContext = context;
    mInstaller = installer;
    mHandler = new Handler(BackgroundThread.getHandler().getLooper());
}
public void prepareAppProfiles(...) {
    for (int i = 0; i < user.length; i++) {
        prepareAppProfiles(pkg, user[i], updateReferenceProfileContent);
    }
}
public void prepareAppProfiles(AndroidPackage pkg,int user,boolean updateReferenceProfileContent) {
    ArrayMap<String, String> codePathsProfileNames = getPackageProfileNames(pkg);
    for (int i = codePathsProfileNames.size() - 1; i >= 0; i--) {
        //...
        //通过Installer来准备Profile
        boolean result = mInstaller.prepareAppProfile(pkg.getPackageName(), user, appId,profileName, codePath, dexMetadataPath);
    }
}
```

>frameworks/base/services/core/java/com/android/server/pm/Installer.java

```java
public boolean prepareAppProfile(String pkg...)  {
    return mInstalld.prepareAppProfile(pkg, userId, appId, profileName, codePath,dexMetadataPath);
}
```

ArtManagerService也是通过installer来干活的，只是其更侧重于对Profile的管理，这里调用了服务端installd的prepareAppProfile

> frameworks/native/cmds/installd/InstalldNativeService.cpp

```c++
binder::Status InstalldNativeService::prepareAppProfile(const std::string& packageName...) {
    *_aidl_return = prepare_app_profile(packageName, userId, appId, profileName, codePath, dexMetadata);
    return ok();
}
```

prepare_app_profile在dexopt.cpp里实现

> frameworks/native/cmds/installd/dexopt.cpp

```cpp
bool prepare_app_profile(const std::string& package_name,
                         userid_t user_id,
                         appid_t app_id,
                         const std::string& profile_name,
                         const std::string& code_path,
                         const std::optional<std::string>& dex_metadata) {
    // Prepare the current profile.
    //1 获取该应用的profile路径，即/data/misc/profiles/cur/0/包名/primary.prof
    std::string cur_profile = create_current_profile_path(user_id, package_name, profile_name,false);
    //2 没有的话就生成此profile文件
    if (fs_prepare_file_strict(cur_profile.c_str(), 0600, uid, uid) != 0) {
        PLOG(ERROR) << "Failed to prepare " << cur_profile;
        return false;
    }

    //配置profman命令的参数
    RunProfman args;
    args.SetupCopyAndUpdate(dex_metadata_fd,
                            ref_profile_fd,
                            apk_fd,
                            code_path);
    //3 这里fork了个进程去执行profman命令
    pid_t pid = fork();
    if (pid == 0) {
        /* child -- drop privileges before continuing */
        gid_t app_shared_gid = multiuser_get_shared_gid(user_id, app_id);
        drop_capabilities(app_shared_gid);

        // The copy and update takes ownership over the fds.
        args.Exec();
    }

    /* parent */
    int return_code = wait_child_with_timeout(pid, kShortTimeoutMs);
    if (!WIFEXITED(return_code)) {
        PLOG(WARNING) << "profman failed for " << package_name << ":" << profile_name;
        cleanup_output_fd(ref_profile_fd.get());
        return false;
    }
    return true;
}
```

+ 注释1获取了该应用的prof文件路径，即/data/misc/profiles/cur/0/包名/primary.prof
+ 注释2会准备此prof文件，没有的话就生成
+ 注释3fork了个进程，我们知道一次fork两次执行，这里fork出的客户端会去执行profman命令

profman命令会根据prof文件生成profile文件，即热点代码的profile文件，这样在后面dex优化的时候就不会一股脑的全部编译，而是只编译热点代码。

##### dex2opt

继续看PackageDexOptimizer.performDexOpt的dex优化过程

>frameworks/base/services/core/java/com/android/server/pm/PackageDexOptimizer.java

```java
int performDexOpt(AndroidPackage pkg...) {
    return performDexOptLI(pkg, pkgSetting, instructionSets,packageStats, packageUseInfo, options);
}

private int performDexOptLI(AndroidPackage pkg,...) {
    //...
    int newResult = dexOptPath(pkg...);
    //...
}
private int dexOptPath(AndroidPackage pkg...){
    //...
    boolean completed = getInstallerLI().dexopt(path,);
    //...
}

//Installer.java
public boolean dexopt(String apkPath...) {
    return mInstalld.dexopt(apkPath...);
}
```

mInstalld的服务端是InstalldNativeService.cpp

> frameworks/native/cmds/installd/InstalldNativeService.cpp

```c++
binder::Status InstalldNativeService::dexopt(...){
    //...
    int res = android::installd::dexopt(apk_path, uid, pkgname, instruction_set, dexoptNeeded,
                                        oat_dir, dexFlags, compiler_filter, volume_uuid, class_loader_context, se_info,
                                        downgrade, targetSdkVersion, profile_name, dm_path, compilation_reason, &error_msg,
                                        &completed);
    return res ? error(res, error_msg) : ok();
}
```

调用到了dexopt.cpp的dexopt方法

>frameworks/native/cmds/installd/dexopt.cpp

```c++
int dexopt(const char* dex_path...) {
    //...
    
    // Create the output OAT file.
    //1 生成oat文件
    RestorableFile out_oat =
        open_oat_out_file(dex_path, oat_dir, is_public, uid, instruction_set, is_secondary_dex);

    // Create a swap file if necessary.
    unique_fd swap_fd = maybe_open_dexopt_swap_file(out_oat.path());
    // Open the reference profile if needed.
    UniqueFile reference_profile = maybe_open_reference_profile(
        pkgname, dex_path, profile_name, profile_guided, is_public, uid, is_secondary_dex);
    
    
    RunDex2Oat runner(dex2oat_bin, execv_helper.get());
    runner.Initialize(out_oat.GetUniqueFile()...);
    
    bool cancelled = false;
    // 2 跟profman一样，也是fork了子进程去执行dex2oat操作
    pid_t pid = dexopt_status_->check_cancellation_and_fork(&cancelled);
    if (pid == 0) {
        //3 DexoptReturnCodes::kDex2oatExec即exec(dex2oat)
        runner.Exec(DexoptReturnCodes::kDex2oatExec);
    } else {
        int res = wait_child_with_timeout(pid, kLongTimeoutMs);
        //...
    }
    // We've been successful, commit work files.
    //...清理缓存文件略
    
    *completed = true;
    return 0;
}
```

+ 注释1会生成oat文件，此时文件为空
+ 注释2跟profman一样，也是fork了子进程去执行dex2oat操作
+ 注释3子进程执行了dex2oat命令，此可执行文件在手机的/system/bin/dex2oat，其作用就是编译dex文件，生成ota文件，这时oat文件的内容就是优化dex文件后的。art虚拟机生成的是oat，jvm虚拟机生成的是odex文件。

#### 总结

installd守护进程是在开机时由installd.rc启动，在ServiceManager中注册了以installd为key的系统服务，此过程在system_service进程启动之前。待system_service启动时，在其main方法中会启动Installer服务，Installer内部会通过binder通信去获取installd的代理对象，在AMS,PMS启动中会把installer传进去，后面AMS,PMS再执行包的安装卸载等都是通过installer来完成。安装过程的最后一步commit会先准备profile文件，由ArtManagerService调用到installer，其实也是调用的installd守护进程，准备profile文件时会fork子进程去执行profman命令去把热点代码生成到profile文件中，在dex2oat时也会通过fork子进程去进行dex优化，并把profile编译成机器码。







