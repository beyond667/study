### AMS与启动流程

#### 背景

常见的AMS、PWS、WMS等等都是系统服务，运行于system_server进程，并且向servicemanager进程注册其Binder以便其他进程获取binder与对应的服务进行通信。为了新增自定义系统服务，我们可以参考AMS等原生系统服务，如新增 PaulManagerService，APP可以通过getSystemService("paul")获取PaulManagerService的代理类PaulManager来跨进程通信。

#### 具体流程

#### 一 Framework自定义系统服务

##### 1.1 IPaulManager.aidl

在 `frameworks/base/core/java/android/app` 中编写 **IPaulManager.aidl**。

```java
package android.app;
/** @hide */
interface IPaulManager {
    String request(String exclude);
}
```

##### 1.2 PaulManager

在 `frameworks/base/core/java/android/app` 下编写**PaulManager.java**。

```java
package android.app;

import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.annotation.Nullable;
import android.os.ServiceManager;
import android.util.Singleton;
@SystemService(Context.PAUL_SERVICE)
public class PaulManager{
    private Context mContext;
    /**
     * @hide
     */
    public PaulManager() {
    }
    /**
     * @hide
     */
    public static IPaulManager getService() {
        return IPaulManagerSingleton.get();
    }
    @UnsupportedAppUsage
    private static final Singleton<IPaulManager> IPaulManagerSingleton =
            new Singleton<IPaulManager>() {
                @Override
                protected IPaulManager create() {
                    final IBinder b = ServiceManager.getService(Context.PAUL_SERVICE);
                    final IPaulManager im = IPaulManager.Stub.asInterface(b);
                    return im;
                }
            };
    @Nullable
    public String request( @Nullable String msg) {
        try {
            return getService().request(msg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
```

##### 1.3 修改Context.java

在`frameworks/base/core/java/android/content/Context.java`中加入常量：

```java
   public static final String PAUL_SERVICE = "paul";
/** @hide */
    @StringDef(suffix = { "_SERVICE" }, value = {
            ....
            ACCOUNT_SERVICE,
            PAUL_SERVICE,
            ...
            });
```

##### 1.4 PaulManagerService

在 `frameworks/base/services/core/java/com/android/server/paul` 中新增**PaulManagerService**

```java
package com.android.server.paul;
import android.annotation.Nullable;
import android.app.IPaulManager;
import android.os.RemoteException;
public class PaulManagerService extends IPaulManager.Stub {
    @Override
    public String request(String msg) throws RemoteException {
        return "PaulManagerService接收数据:" + msg; //这里只对传过来的数据稍微处理
    }
}
```

##### 1.5 SystemServer中注册

在`frameworks/base/services/java/com/android/server/SystemServer.java`中 注册系统服务

```java
//startBootstrapServices和startCoreServices里注册也可以
private void startOtherServices(){
    ...
	ServiceManager.addService(Context.PAUL_SERVICE,new PaulManagerService());
    ...
}
```

#####  1.6 SystemServiceRegistry注册

在`frameworks/base/core/java/android/app/SystemServiceRegistry.java`注册服务获取器，这样我们才可以通过getSystemService（"paul"）来获取自定义的系统服务。

```java
static{
    ...
    registerService(Context.PAUL_SERVICE, PaulManager.class,
                    new CachedServiceFetcher<PaulManager>() {
                        @Override
                        public PaulManager createService(ContextImpl ctx) throws  ServiceNotFoundException {
                            return new PaulManager();
                        }});
    ...
}
```

##### 1.7 配置SELinux权限

在 `system/sepolicy/prebuilts/api/33.0/private` 与` system/sepolicy/private/`目录下，分别修改：

`service_contexts ` :

```java
activity                                  u:object_r:activity_service:s0
#配置自定义服务selinux角色
paul                                      u:object_r:paul_service:s0
```

`service.te`:

```java
#配置自定义服务类型
type paul_service,                  app_api_service, system_api_service, system_server_service, service_manager_type;
```

`untrusted_app_all.te `:

```java
#允许所有app使用自定义服务
allow untrusted_app_all paul_service:service_manager find;
```

##### 1.8 更新API

```
make update-api
```

编译运行到手机上后查看服务是否生效

```java
adb shell service list| grep paul

#输出： 表示成功加入自定义服务
101 paul: [android.app.IPaulManager]
```



#### 二 客户端使用自定义服务

##### 方式一：基于双亲委托机制

在需要使用自定义服务的app中编写PaulManager（包名与framework中一致）：

```java
package android.app;
public class PaulManager {
    public String request(String msg) {
        return "";
    }
}
```

在需要调用自定义服务的地方：

```java
PaulManager paulManager =(PaulManager)getSystemService("paul");
String result = paulManager.request("ss");
```

此时由于类加载的双亲委托机制，app在运行时实际使用的是framework中的PaulManager，app中的PaulManager仅仅只是为了编译成功编写的空壳。

##### 方式二：修改sdk

先通过`make sdk`把sdk完整编译出来，然后把**out/target/common/obj/JAVA_LIBRARIES/android_stubs_current_intermediates/classes.jar**拷贝出来重命名为`android.jar`，可以直接替换自己`Sdk\platforms\android-33`目录下的android.jar，也可以自定义一个新的sdk，比如android-330

流程如下：

##### 2.1 拷贝android-33为android-330，并修改android-330/source.properties

```properties
Pkg.Desc=Android SDK Platform 330
Pkg.UserSrc=false
Platform.Version=330
Platform.CodeName=
Pkg.Revision=1
AndroidVersion.ApiLevel=330
AndroidVersion.ExtensionLevel=3
AndroidVersion.IsBaseSdk=true
Layoutlib.Api=15
Layoutlib.Revision=1
Platform.MinToolsRev=22

```

##### 2.2 修改android-330/package.xml

```xml
</license><localPackage path="platforms;android-330" obsolete="false"><type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns11:platformDetailsType"><api-level>330</api-level><codename></codename><extension-level>3</extension-level><base-extension>true</base-extension><layoutlib api="15"/></type-details><revision><major>2</major></revision><display-name>Android SDK Platform 330</display-name><uses-license ref="android-sdk-license"/></localPackage></ns2:repository>
```

即把里面所有33的都改为330

##### 2.3 修改**SDK/sources/source.properties**

```properties
Pkg.UserSrc=false
Pkg.Revision=1
AndroidVersion.ApiLevel=330
```

##### 2.4 同样修改**SDK/sources/package.xml**

```xml
</license><localPackage path="sources;android-330" obsolete="false"><type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns10:sourceDetailsType"><api-level>330</api-level><codename></codename></type-details><revision><major>1</major></revision><display-name>Sources for Android 330</display-name><uses-license ref="android-sdk-license"/></localPackage></ns2:repository>
```

##### 2.5 build.gradle中使用自定义的sdk

```groovy
android {
compileSdk 330
....
defaultConfig {
targetSdk 330
}
}
```

#### 三 SystemService生命周期

上面实例是以 `ServiceManager.addService("xx", new XXManagerService) `将自己Binder注册进入SM才能够让其他进程利用Binder与之通信。  

```java
    public static void addService(String name, IBinder service) {
        addService(name, service, false, IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
    }
    public static void addService(String name, IBinder service, boolean allowIsolated) {
        addService(name, service, allowIsolated, IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
    }
    public static void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) {
        try {
            getIServiceManager().addService(name, service, allowIsolated, dumpPriority);
        } catch (RemoteException e) {
            Log.e(TAG, "error in addService", e);
        }
    }
    private static IServiceManager getIServiceManager() {
        if (sServiceManager != null) {
            return sServiceManager;
        }
        // Find the service manager
        sServiceManager = ServiceManagerNative.asInterface(Binder.allowBlocking(BinderInternal.getContextObject()));
        return sServiceManager;
    }
```

可以看到最终是通过native层`sServiceManager.addService`来把binder注册进SM。

第二种是以`mSystemServiceManager.startService `的方式，通过监听系统启动的不同阶段进行不同的处理。以AMS为例：

```java
    private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
    	//···
          t.traceBegin("StartActivityManager");
        // TODO: Might need to move after migration to WM.
        ActivityTaskManagerService atm = mSystemServiceManager.startService(
                ActivityTaskManagerService.Lifecycle.class).getService();
        mActivityManagerService = ActivityManagerService.Lifecycle.startService(
                mSystemServiceManager, atm);
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);
        mWindowManagerGlobalLock = atm.getGlobalLock();
      
        //启动PMS服务
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);
        mActivityManagerService.initPowerManagement();
        
        //关键点一
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        //关键点二
        mActivityManagerService.setSystemProcess();
        //···
    }
```

ActivityManagerService:

```java
public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback, ActivityManagerGlobalLock {
    
        private void start() {
            removeAllProcessGroups();
            mBatteryStatsService.publish();
            mAppOpsService.publish();
            Slog.d("AppOps", "AppOpsService published");
            LocalServices.addService(ActivityManagerInternal.class, mInternal);
            LocalManagerRegistry.addManager(ActivityManagerLocal.class,
                    (ActivityManagerLocal) mInternal);
            mActivityTaskManager.onActivityManagerInternalAdded();
            mPendingIntentController.onActivityManagerInternalAdded();
            mAppProfiler.onActivityManagerInternalAdded();
    	}
    
        public void setSystemProcess() {
            ServiceManager.addService(Context.ACTIVITY_SERVICE, this, /* allowIsolated= */ true,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
            //...
        }

        public static final class Lifecycle extends SystemService {
        private final ActivityManagerService mService;
        private static ActivityTaskManagerService sAtm;

        public Lifecycle(Context context) {
            super(context);
            mService = new ActivityManagerService(context, sAtm);
        }

        public static ActivityManagerService startService(
                SystemServiceManager ssm, ActivityTaskManagerService atm) {
            sAtm = atm;
            return ssm.startService(ActivityManagerService.Lifecycle.class).getService();
        }

        @Override
        public void onStart() {
            mService.start();
        }

        @Override
        public void onBootPhase(int phase) {
            mService.mBootPhase = phase;
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mService.mBatteryStatsService.systemServicesReady();
                mService.mServices.systemServicesReady();
            } else if (phase == PHASE_ACTIVITY_MANAGER_READY) {
                mService.startBroadcastObservers();
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mService.mPackageWatchdog.onPackagesReady();
            }
        }

        public ActivityManagerService getService() {
            return mService;
        }
    }
}
```

再看SystemServiceManager.startService

```java
	public <T extends SystemService> T startService(Class<T> serviceClass) {
  		//...省略非关键代码
         final String name = serviceClass.getName();
         final T service;
         Constructor<T> constructor = serviceClass.getConstructor(Context.class);
         service = constructor.newInstance(mContext);
         startService(service);
         return service;
    }
    private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();
    public void startService(@NonNull final SystemService service) {
        // Register it.
        mServices.add(service);
        // Start it.
        service.onStart();
    }
    public void startBootPhase(@NonNull TimingsTraceAndSlog t, int phase) {
        mCurrentPhase = phase;
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            service.onBootPhase(mCurrentPhase);
        }
    }
```

`mSystemServiceManager.startService `通过反射初始化泛型的构造函数后继续调用本地的startService，这里会把新添加的ServiceService添加到本地列表mServices中，再执行其onStart方法，startBootPhase被调用时会遍历mServices，以此执行其onBootPhase。

以AMS为例，Lifecycle的onStart会调用外部类也就是ActivityManagerService.start()，start()里会启动一些关联的服务，最终在setSystemProcess方法里通过`ServiceManager.addService`把自己加到系统服务里。

所以可以得到以下结论：

1. ActivityManagerService.Lifecycle.startService实际还是通过SystemServiceManager.startService的方式启动，而SystemServiceManager.startService后面还是需要手动`ServiceManager.addService`添加进系统服务。所以方式二本质上跟方式一完全一样，不过其可以在addService前做些自己的业务。

2. Lifecycle作为静态内部类，继承于SystemService，重写`onStart`和`onBootPhase`，其onStart方法会立即执行，onBootPhase方法会通过 `mSystemServiceManager.startBootPhase`（关键点一）来监听SystemServer的不同启动阶段。

3. AMS中在setSystemProcess时才真正绑定了Binder（关键点二），前面的操作主要是先启动关联的服务。

   ***

上面AMS内部类Lifecycle的onStart比较特殊，下面看一般的系统服务Lifecycle的onStart，随便找一个：

```java
public class UserManagerService extends IUserManager.Stub {
    public static class LifeCycle extends SystemService {
        public LifeCycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.USER_SERVICE, mUms);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
				//...
            }
        }
	}
}
```

一般在onStart里会调用`publishBinderService`,来看SystemService

```java
    protected final void publishBinderService(@NonNull String name, @NonNull IBinder service) {
        publishBinderService(name, service, false);
    }
    protected final void publishBinderService(@NonNull String name, @NonNull IBinder service,
            boolean allowIsolated) {
        publishBinderService(name, service, allowIsolated, DUMP_FLAG_PRIORITY_DEFAULT);
    }
    protected final void publishBinderService(String name, IBinder service,
            boolean allowIsolated, int dumpPriority) {
        ServiceManager.addService(name, service, allowIsolated, dumpPriority);
    }
```

还是调了`ServiceManager.addService`来绑定binder服务。

***

##### onBootPhase的阶段

以Android12为例，一共有以下8个阶段：

```java
public static final int PHASE_WAIT_FOR_DEFAULT_DISPLAY = 100;
public static final int PHASE_WAIT_FOR_SENSOR_SERVICE = 200;
public static final int PHASE_LOCK_SETTINGS_READY = 480;
public static final int PHASE_SYSTEM_SERVICES_READY = 500;
public static final int PHASE_DEVICE_SPECIFIC_SERVICES_READY = 520;
public static final int PHASE_ACTIVITY_MANAGER_READY = 550;
public static final int PHASE_THIRD_PARTY_APPS_CAN_START = 600;
public static final int PHASE_BOOT_COMPLETED = 1000;
```

**PHASE_WAIT_FOR_DEFAULT_DISPLAY**: 等待默认显示阶段，只有DisplayManagerService会处理，此时AMS，PMS已经启动好，但是AMS还没绑定到SM中。

```java
//DisplayManagerService.java    
@Override
public void onBootPhase(int phase) {
    if (phase == PHASE_WAIT_FOR_DEFAULT_DISPLAY) {
        synchronized (mSyncRoot) {
            //getDefaultDisplayDelayTimeout拿到的是10s
            long timeout = SystemClock.uptimeMillis()
                + mInjector.getDefaultDisplayDelayTimeout();
            while (mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY) == null
                   || mVirtualDisplayAdapter == null) {
                long delay = timeout - SystemClock.uptimeMillis();
                if (delay <= 0) {
                    throw new RuntimeException("Timeout waiting for default display "
                                               + "to be initialized. DefaultDisplay="
                                               + mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY)
                                               + ", mVirtualDisplayAdapter=" + mVirtualDisplayAdapter);
                }
                try {
                    mSyncRoot.wait(delay);
                } catch (InterruptedException ex) {
                }
            }
        }
    } else if (phase == PHASE_BOOT_COMPLETED) {
        mDisplayModeDirector.onBootCompleted();
    }
}
```

可以看到只要通过`getDisplayLocked(Display.DEFAULT_DISPLAY)`拿不到默认的逻辑Display，就会一直多等10s，直到拿到为止。

**PHASE_WAIT_FOR_SENSOR_SERVICE** ：等待传感器服务阶段。Android12新增的，由于WMS启动需要传感器启动好，所以在WMS启动前会发送这个阶段事件给SensorService处理。

```java
// WMS needs sensor service ready
mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE);
wm = WindowManagerService.main(context, inputManager, !mFirstBoot, mOnlyCore,
new PhoneWindowManager(), mActivityManagerService.mActivityTaskManager);
ServiceManager.addService(Context.WINDOW_SERVICE, wm, /* allowIsolated= */ false,DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PROTO);
```

SensorService:

```java
    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE) {
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart,
                    START_NATIVE_SENSOR_SERVICE);
            synchronized (mLock) {
                mSensorServiceStart = null;
            }
        }
    }

    public SensorService(Context ctx) {
        super(ctx);
        synchronized (mLock) {
            mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
                long ptr = startSensorServiceNative(new ProximityListenerDelegate());
                synchronized (mLock) {
                    mPtr = ptr;
                }
            }, START_NATIVE_SENSOR_SERVICE);
        }
    }
```

即也是等SensorService的native初始化好才解锁继续往下走。

**PHASE_LOCK_SETTINGS_READY**：该阶段会锁定Settings。此时已创建好PMS,PKMS等。只有ShortcutService和DevicePolicyManagerService会处理。以ShortcutService为例：

```java
@Override
public void onBootPhase(int phase) {
    mService.onBootPhase(phase);
}
void onBootPhase(int phase) {
    if (DEBUG || DEBUG_REBOOT) {
        Slog.d(TAG, "onBootPhase: " + phase);
    }
    switch (phase) {
        case SystemService.PHASE_LOCK_SETTINGS_READY:
            initialize();
            break;
        case SystemService.PHASE_BOOT_COMPLETED:
            mBootCompleted.set(true);
            break;
    }
}
private void initialize() {
    synchronized (mLock) {
        loadConfigurationLocked();
        loadBaseStateLocked();
    }
}
```

套路一样，加锁去做些业务。

**PHASE_SYSTEM_SERVICES_READY**:与3之前并无新的服务启动，很多服务都会监听其阶段。代表系统服务已经准备好，可以调用核心的系统服务，比如AMS,PMS,WMS等。以BluetoothService为例

```java
//BluetoothService.java
@Override
public void onBootPhase(int phase) {
    if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
        publishBinderService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE,
                             mBluetoothManagerService);
    } else if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY &&
               !UserManager.isHeadlessSystemUserMode()) {
        initialize();
    }
}
```

可以看到很多服务都是在这个时机才会绑定到系统服务中。

**PHASE_DEVICE_SPECIFIC_SERVICES_READY**：设备特殊服务已准备好。无具体服务监听此流程。

```java
t.traceBegin("StartDeviceSpecificServices");
final String[] classes = mSystemContext.getResources().getStringArray(
    R.array.config_deviceSpecificSystemServices);
for (final String className : classes) {
    mSystemServiceManager.startService(className);
}
mSystemServiceManager.startService(GAME_MANAGER_SERVICE_CLASS);
if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)) {
    mSystemServiceManager.startService(UWB_SERVICE_CLASS);
}

t.traceBegin("StartBootPhaseDeviceSpecificServicesReady");
mSystemServiceManager.startBootPhase(t, SystemService.PHASE_DEVICE_SPECIFIC_SERVICES_READY);
```

从config_deviceSpecificSystemServices这个列表去启动厂商自定义的特殊设备服务，默认列表为空。谷歌可能把GAME_MANAGER服务和UWB服务作为特殊设备服务。

**PHASE_ACTIVITY_MANAGER_READY**：已准备就绪,进入该阶段服务能发送广播;但是system_server主线程并没有就绪。以BluetoothService为例可以看到其initialize初始化放到了这个阶段。

**PHASE_THIRD_PARTY_APPS_CAN_START**：可以启动第三方应用阶段。服务可以启动第三方应用，第三方应用也可以通过Binder来调用服务。此前已经启动SystemUI，剩余服务调用systemReady。

**PHASE_BOOT_COMPLETED**：启动完成。此时应用桌面已经启动，用户已经可以和设备交互。

系统服务启动完成后，system_server进程进入Looper.loop()状态，一直等到消息队列有消息过来。

```java
t.traceBegin("StartServices");
startBootstrapServices(t);
startCoreServices(t);
startOtherServices(t);
// Loop forever.
Looper.loop();
```



#### 可能遇到的问题

##### 一  Android13使用命令  `make sdk` 编译sdk时遇到如下报错：

```java
error: packages/modules/RuntimeI18n/apex/Android.bp:67:1: dependency "art-bootclasspath-fragment" of "i18n-bootclasspath-fragment" missing variant:
  apex:com.android.art
available variants:
  os:android,arch:common

```

只需要在`\build\make\target\product\aosp_product.mk`的最后加上：

```java
MODULE_BUILD_FROM_SOURCE := true
```

这里具体修改哪个mk文件由你lunch的哪个决定，比如我`lunch aosp_oriole-userdebug` ，主要为了编译Pixel 6，尝试修改其他mk无效，只有aosp_product.mk有效。



##### 二 单独编译framework

Android11（包括11）之后：

```makefile
make framework-minus-apex
make services
```

Android11之前：

```makefile
make framework
make services
```

再推到手机

```java
adb push  V:\aosp\out\target\product\oriole\system\framework /system/
```

