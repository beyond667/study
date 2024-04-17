#### 前言

PMS是android系统很重要的一个系统服务，主要负责管理应用的安装，卸载，更新，查询，权限管理。我们在四大组件的启动流程中都看到PMS的身影，比如通过Intent启动另一个应用时都会先通过PMS去获取该应用的PackageInfo信息。本文主要从两个方面分析PMS：PMS安装APP流程，PMS的启动和使用流程。本文基于Android13。

#### PMS启动流程

在[开机流程](https://github.com/beyond667/study/blob/master/note/Activity%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)中已经分析过，PMS是在`SystemServer`的`startBootstrapServices()`里启动的，本文只关注跟PMS相关的。

> frameworks/base/services/java/com/android/server/SystemServer.java

```java
private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
private static final String ENCRYPTED_STATE = "1";
private boolean mOnlyCore;

private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
	//...
    // Only run "core" apps if we're encrypting the device.
    String cryptState = VoldProperties.decrypt().orElse("");
    if (ENCRYPTING_STATE.equals(cryptState)) {
        mOnlyCore = true;
    } else if (ENCRYPTED_STATE.equals(cryptState)) {
        mOnlyCore = true;
    }
    
    IPackageManager iPackageManager;
    Pair<PackageManagerService, IPackageManager> pmsPair = PackageManagerService.main(
        mSystemContext, installer, domainVerificationService,
        mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
    mPackageManagerService = pmsPair.first;
    iPackageManager = pmsPair.second;
	//...
}
```

注意vold.decrypt属性值如果是1或者trigger_restart_min_framework，mOnlyCore就为true，代表不需扫描data分区。vold.decrypt是获取android磁盘的加密状态，默认是扫描data分区的。

>frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java

```java
public static Pair<PackageManagerService, IPackageManager> main(Context context,Installer installer, @NonNull DomainVerificationService domainVerificationService,boolean factoryTest, boolean onlyCore) {

    //1 构建注射器，里面传了PMS构造函数中需要的对象
    PackageManagerServiceInjector injector = new PackageManagerServiceInjector(
        context, lock, installer, installLock, new PackageAbiHelperImpl(),
        backgroundHandler,
        (i, pm) -> PermissionManagerService.create(context,
                                                   i.getSystemConfig().getAvailableFeatures()),
        (i, pm) -> new Settings(Environment.getDataDirectory(),
                                RuntimePermissionsPersistence.createInstance(),
                                i.getPermissionManagerServiceInternal(),
                                domainVerificationService, backgroundHandler, lock),
        //...
    );
    //2 直接new PackageManagerService创建实例
    PackageManagerService m = new PackageManagerService(injector, onlyCore, factoryTest,
                                                        PackagePartitions.FINGERPRINT, Build.IS_ENG, Build.IS_USERDEBUG,
                                                        Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL);

    //3 通过PMS new出IPackageManagerImpl这个binder对象，以key为“package”添加到系统服务中
    IPackageManagerImpl iPackageManager = m.new IPackageManagerImpl();
    ServiceManager.addService("package", iPackageManager);
    final PackageManagerNative pmn = new PackageManagerNative(m);
    ServiceManager.addService("package_native", pmn);
    LocalManagerRegistry.addManager(PackageManagerLocal.class, m.new PackageManagerLocalImpl());
    return Pair.create(m, iPackageManager);
}
```

+ 注释1处先创建了个注射器，里面塞了很多PMS初始化时需要的资源，比如权限管理的服务PermissionManagerService，协助PMS保存应用信息的Settings等等。
+ 注释2把注释1的注射器传到PMS的构造函数中，new出实例
+ 注释3由于PMS并没继承Binder，所以其并不是binder对象，通过m.new IPackageManagerImpl初始化binder对象，并绑定到以key为`package`的系统服务中

注释1里有个new Settings

> frameworks/base/services/core/java/com/android/server/pm/Settings.java

```java
Settings(File dataDir...)  {
    mSystemDir = new File(dataDir, "system");
    mSystemDir.mkdirs();
	//记录系统所有安装的apk的信息
    mSettingsFilename = new File(mSystemDir, "packages.xml");
    //packages.xml的备份文件
    mBackupSettingsFilename = new File(mSystemDir, "packages-backup.xml");
    //记录手机里所有app的简要信息，包括name、dataPath等
    mPackageListFilename = new File(mSystemDir, "packages.list");
    FileUtils.setPermissions(mPackageListFilename, 0640, SYSTEM_UID, PACKAGE_INFO_GID);

    final File kernelDir = new File("/config/sdcardfs");
    mKernelMappingFilename = kernelDir.exists() ? kernelDir : null;
    //强制stop的apk信息  
    mStoppedPackagesFilename = new File(mSystemDir, "packages-stopped.xml");
    //packages-stopped的备份文件
    mBackupStoppedPackagesFilename = new File(mSystemDir, "packages-stopped-backup.xml");

    registerObservers();
}
```











































































#### adb install流程

常见的安装场景有以下几种：

+ 命令行：adb install /push(系统应用)
+ 用户下载apk，通过系统内置的安装器packageinstall安装，有安装界面
+ 系统开机时安装系统应用
+ 应用商店自动安装

不管通过哪种方式，最终干活的都是PMS，这里对adb的方式比较好奇，先具体分析下`adb install`后的流程。

在windows平台执行adb install，其实是adb.exe install，这里调用到了adb模块的main方法

> packages/modules/adb/client/main.cpp

```c++
int main(int argc, char* argv[], char* envp[]) {
    return adb_commandline(argc - 1, const_cast<const char**>(argv + 1));
}
```

> packages/modules/adb/client/commandline.cpp

```cpp
int adb_commandline(int argc, const char** argv) {
	//...
    if (!strcmp(argv[0], "devices")) {
        //...
    }else if (!strcmp(argv[0], "shell")) {
        return adb_shell(argc, argv);
    }else if (!strcmp(argv[0], "install")) {
        if (argc < 2) error_exit("install requires an argument");
        return install_app(argc, argv);
    } 
    //...
}
```

原来我们平常输入的adb devices/shell/install其实就是走的这里。继续看install_app

>packages/modules/adb/client/adb_install.cpp

```c++
int install_app(int argc, const char** argv) {
    //...
    //这里会计算下需要用那种模式安装，非二进制的默认返回的是INSTALL_PUSH
    auto [primary_mode, fallback_mode] =
        calculate_install_mode(install_mode, use_fastdeploy, incremental_request);
    switch (install_mode) {
        case INSTALL_PUSH:
            return install_app_legacy(passthrough_argv.size(), passthrough_argv.data(),use_fastdeploy);
            //....
    }
}

static int install_app_legacy(int argc, const char** argv, bool use_fastdeploy){
    //....
    if (do_sync_push(apk_file, apk_dest.c_str(), false, CompressionType::Any, false)) {
        result = pm_command(argc, argv);
        delete_device_file(apk_dest);
    }
    return result;
}
static int pm_command(int argc, const char** argv) {
    std::string cmd = "pm";
    while (argc-- > 0) {
        cmd += " " + escape_arg(*argv++);
    }
    return send_shell_command(cmd);
}
```

先通过do_sync_push把源目录push到目标目录，再通过pm_command去执行相关命令，这里看到传的字符串是"pm ..."，这里又调回commandline.send_shell_command

> packages/modules/adb/client/commandline.cpp

```cpp
int send_shell_command(const std::string& command, bool disable_shell_protocol,
                       StandardStreamsCallbackInterface* callback) {
    //...
    while (true) {
        if (attempt_connection) {
            std::string error;
            //拼接了一条在server执行的shell执行
            std::string service_string = ShellServiceString(use_shell_protocol, "", command);
            //把该指令通过socket通信发送到服务端
            fd.reset(adb_connect(service_string, &error));
            if (fd >= 0) {
                break;
            }
        }
        if (!wait_for_device("wait-for-device")) {
            return 1;
        }
    }
}
```

先通过ShellServiceString拼接一条在服务端执行的shell指令，再通过socket通信发送到服务端

>packages/modules/adb/client/adb_client.cpp

```c++
int adb_connect(std::string_view service, std::string* error) {
    return adb_connect(nullptr, service, error);
}
int adb_connect(TransportId* transport, std::string_view service, std::string* error,bool force_switch_device) {
    //...
    unique_fd fd(_adb_connect(service, transport, error, force_switch_device));
    //...
}
static int _adb_connect(std::string_view service, TransportId* transport, std::string* error,bool force_switch = false) {
    //...
    std::optional<TransportId> transport_result = switch_socket_transport(fd.get(), error);
    //...
}
static int _adb_connect(std::string_view service, TransportId* transport, std::string* error,bool force_switch = false) {
    //...
    if (!SendProtocolString(fd.get(), service)) {
        *error = perror_str("write failure during connection");
        return -1;
    }
    //...
}
```

看到这里就不再往socket通信更深里看了。这里SendProtocolString传的是个文件描述符，即前面传的pm字符串，其实这里传的是/frameworks/base/cmds/pm/pm文件

>/frameworks/base/cmds/pm/pm

```cpp
#!/system/bin/sh
cmd package "$@"
```

这里的服务端即shell进程，会执行该cmd package指令，调用其cmd.main入口方法，注意这里已经由adb进程进入到shell进程。

> frameworks/native/cmds/cms/main.cpp

```cpp
int main(int argc, char* const argv[]) {
    //...
    return cmdMain(arguments, android::aout, android::aerr, STDIN_FILENO, STDOUT_FILENO,STDERR_FILENO, RunMode::kStandalone);
}
```

>frameworks/native/cmds/cms/cmd.cpp

```c++
int cmdMain(const std::vector<std::string_view>& argv, TextOutput& outputLog, TextOutput& errorLog, int in, int out, int err, RunMode runMode) {
    //...
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> service;
    service = sm->checkService(serviceName);
    status_t error = IBinder::shellCommand(service, in, out, err, args, cb, result);
    //...
}
```

这里先获取serviceManager，再调用checkService去获取相应的服务，这里由于传的是package，所以是获取了PMS，调用binder的shellCommand，这个service即PMS的代理对象

>frameworks/native/libs/binder/Binder.cpp

```c++
status_t IBinder::shellCommand(const sp<IBinder>& target, int in, int out, int err,Vector<String16>& args, const sp<IShellCallback>& callback,const sp<IResultReceiver>& resultReceiver)
{
	//...
    return target->transact(SHELL_COMMAND_TRANSACTION, send, &reply);
}
```

binder机制，shell客户端调用代理类的transact方法，服务端调用其对应的onTransact方法，这里会调用Binder.onTransact

> frameworks/base/core/java/android/os/Binder.java

```java
protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,int flags) throws RemoteException {
    //...
    if (code == SHELL_COMMAND_TRANSACTION) {
        shellCommand(in != null ? in.getFileDescriptor() : null,
                     out.getFileDescriptor(),
                     err != null ? err.getFileDescriptor() : out.getFileDescriptor(),
                     args, shellCallback, resultReceiver);
    }
}
public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                         @Nullable FileDescriptor err,
                         @NonNull String[] args, @Nullable ShellCallback callback,
                         @NonNull ResultReceiver resultReceiver) throws RemoteException {
    onShellCommand(in, out, err, args, callback, resultReceiver);
}
```

由于PMS重写了onShellCommand方法，这里调用到PMS.onShellCommand

> frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java

```java
@Override
public void onShellCommand(FileDescriptor in, FileDescriptor out,
                           FileDescriptor err, String[] args, ShellCallback callback,
                           ResultReceiver resultReceiver) {
    (new PackageManagerShellCommand(this, mContext,mDomainVerificationManager.getShell()))
    .exec(this, in, out, err, args, callback, resultReceiver);
}
```

new了个PackageManagerShellCommand，并执行其exec方法，这里会回调PackageManagerShellCommand.onCommand方法

> frameworks/base/services/core/java/com/android/server/pm/PackageManagerShellCommand.java

```java
public int onCommand(String cmd) {
    switch (cmd) {
        case "install":
            return runInstall();
        //...
    }
}
private int runInstall() throws RemoteException {
    return doRunInstall(makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS));
}
private int doRunInstall(final InstallParams params) throws RemoteException{
	//...
    final int sessionId = doCreateSession(params.sessionParams,params.installerPackageName,params.userId);
    if (doCommitSession(sessionId, false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
        return 1;
    }
    //...
}
```

这里通过doCreateSession和doCommitSession跟PackageInstall交互，通过PackageInstall去真正走安装流程。这个我们在PMS安装流程里具体看。

总的来说，adb install命令会先在adb进程构建服务端执行的shell语句后通过socket传给shell进程执行，shell进程执行cmd package 指令后获取指定的服务，比如PMS，并通过binder机制调用其shellCommand，最终再根据传的adb后面的参数，比如install/uninstall执行相应的安装/卸载。