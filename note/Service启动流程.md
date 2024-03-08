### Service启动流程

> Service作为四大组件之一，与Activity类似，只不过作为后台服务，不提供用户界面，可以在后台长时间运行。服务启动方式有两种，分别是startService和bindService。（源码基于android13）

#### startService

Activity继承ContextWrapper，这里调用的是父类ContextWrapper的startService方法

>frameworks/base/core/java/android/content/ContextWrapper.java

```java
Context mBase;
protected void attachBaseContext(Context base) {
    if (mBase != null) {
        throw new IllegalStateException("Base context already set");
    }
    mBase = base;
}

@Override
public ComponentName startService(Intent service) {
    return mBase.startService(service);
}
```

mBase即ContexImpl

> frameworks/base/core/java/android/app/ContextImpl.java

```java
@Override
public ComponentName startService(Intent service) {
    warnIfCallingFromSystemProcess();
    return startServiceCommon(service, false, mUser);
}
private ComponentName startServiceCommon(Intent service, boolean requireForeground,
                                         UserHandle user) {
    try {
        validateServiceIntent(service);
        service.prepareToLeaveProcess(this);
        // 1 调用到ams的startService
        ComponentName cn = ActivityManager.getService().startService(
            mMainThread.getApplicationThread(), service, service.resolveTypeIfNeeded(
                getContentResolver()), requireForeground,
            getOpPackageName(), user.getIdentifier());
        .......
            return cn;
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

1处直接通过binder调用到ams的startService，把intent和applicationThread传过去

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
final ActiveServices mServices;
public ComponentName startService(IApplicationThread caller, Intent service...)
    throws TransactionTooLargeException {
    //...
    try {
        res = mServices.startServiceLocked(caller, service...);//1
    } finally {
        Binder.restoreCallingIdentity(origId);
    }
    return res;
}
```

AMS是通过ActiveServices来管理Service的

> frameworks/base/services/core/java/com/android/server/am/ActiveServices.java

```java
ComponentName startServiceLocked(IApplicationThread caller, Intent service...){
    //...
    //1 检索是否存在要启动的服务，如果存在，就把ServiceRecord封装到ServiceLookupResult
    ServiceLookupResult res =
        retrieveServiceLocked(service, null, resolvedType, callingPackage,
                              callingPid, callingUid, userId, true, callerFg, false, false);
    //...
    ServiceRecord r = res.record;
    //...
    //2 调用startServiceInnerLocked把查找到的ServiceRecord传过去
    ComponentName cmp = startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
    return cmp;
}
private ComponentName startServiceInnerLocked(ServiceRecord r, Intent service,...) {
     //...
    //3 这里比较关键，给service标记是通过start请求启动的，并添加到pendingStarts缓存中
    r.startRequested = true;
    r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                                                    service, neededGrants, callingUid));
    //...
    ComponentName cmp = startServiceInnerLocked(smap, service, r, callerFg, addToStarting,
                                                callingUid, wasStartRequested);
    return cmp;
}
ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r...){
    //...
    String error = bringUpServiceLocked(r, service.getFlags(),callerFg,false,false ,false ,true);
    //...
}
```

注释1处会先检查是否存在要启动的服务，即ServiceRecord（AMS用ActivityRecord描述Activity，同样，用ServiceRecord描述Service），如果存在就把ServiceRecord封装到ServiceLookupResult返回。后面一直调用到bringUpServiceLocked方法（注释2）。注释3处比较关键，容易忽略，这里对serviceRecord标记了是通过start启动的，并且缓存了此请求。（因为startService和bindservice都会调用到realStartServiceLocked这个方法，通过startRequested就可以区分，区分后startservice的就会走service的onStartCommand的流程，bindservice的不走）。先看retrieveServiceLocked

```java
private ServiceLookupResult retrieveServiceLocked(Intent service...){
    ServiceRecord r = null;
    //...
    if (r == null) {
        //1 通过PMS去拿应用信息
        ResolveInfo rInfo = mAm.getPackageManagerInternal().resolveService(service, resolvedType, flags, userId, callingUid);
        
        //2从缓存拿ServiceRecord，如果没有就new出来并放进缓存
        r = smap.mServicesByInstanceName.get(name);
        if (r == null && createIfNeeded) {
            r = new ServiceRecord(mAm, className, name, definingPackageName,
                                  definingUid, filter, sInfo, callingFromFg, res,
                                  sdkSandboxProcessName, sdkSandboxClientAppUid,
                                  sdkSandboxClientAppPackage);
            res.setService(r);
            smap.mServicesByInstanceName.put(name, r);
        }
    }
}                                             
```

再看bringUpServiceLocked是怎么启动服务的

```java
private String bringUpServiceLocked(ServiceRecord r, int intentFlags...) {
    //1 这里的r.app是等service创建完才不为空，如果这里不为空，说明service已经创建好了此时service.onCreate已经执行
    // 直接走sendServiceArgsLocked去执行service.onStartCommand，如果为空，说明服务还没创建，走注释2去拿服务端进程
    if (r.app != null && r.app.getThread() != null) {
        sendServiceArgsLocked(r, execInFg, false);
        return null;
    }
    //...
    ProcessRecord app;
    //2 service对应的进程是否存在
    app = mAm.getProcessRecordLocked(procName, r.appInfo.uid);    
    if (app != null ) {
        final IApplicationThread thread = app.getThread();
        if (thread != null) {
            try {
                app.addPackage(r.appInfo.packageName, r.appInfo.longVersionCode, mAm.mProcessStats);
                //3 真正启动服务
                realStartServiceLocked(r, app, thread, pid, uidRecord,execInFg, enqueueOomAdj);
                return null;
            } catch (TransactionTooLargeException e) {
                throw e;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting service " + r.shortInstanceName, e);
            }
        }
	}
    //4 要启动的服务进程不存在，通过AMS.startProcessLocked先启动进程
    if (app == null && !permissionsReviewRequired && !packageFrozen) {
          app = mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                        hostingRecord, ZYGOTE_POLICY_FLAG_EMPTY, false, isolated);
    }
    //5 把要启动的service存起来，待要启动的进程启动好后再起服务，startService和bindService都会存进mPendingServices
    if (!mPendingServices.contains(r)) {
        mPendingServices.add(r);
    }
	//...
	return null;
}
```

注释写的很清楚，注释1先去判断对应的service是否已经创建，如果创建了，直接走sendServiceArgsLocked流程。否则走注释2，先看对应服务的进程是否启动，即ProcessRecord，如果进程启动了就直接通过realStartServiceLocked去启动服务，如果进程没启动，就通过注释4调用AMS.startProcessLocked来启动对应进程，然后把要启动的服务先缓存到mPendingServices中，待要启动进程启动好后再拉起服务。先看进程已经启动的情况，即调到realStartServiceLocked

```java
private void realStartServiceLocked(ServiceRecord r, ProcessRecord app,
                                    IApplicationThread thread...) {
    //...
    //1 调用到要启动服务的进程的scheduleCreateService去创建服务
    thread.scheduleCreateService(r, r.serviceInfo,
                                 mAm.compatibilityInfoForPackage(r.serviceInfo.applicationInfo),
                                 app.mState.getReportedProcState());
    //...
    //2 等service创建完成后，再通过binder把参数传过去执行服务的onStartCommand
    sendServiceArgsLocked(r, execInFg, true);
    //...
}
```

thread即ApplicationThread，这里注释1通过binder通信调用到要启动服务的进程的ApplicationThread.scheduleCreateService，待进程的service创建完成后，再通过2处启动服务。先看scheduleCreateService创建服务

> frameworks/base/core/java/android/app/ActivityThread.java

```java
private class ApplicationThread extends IApplicationThread.Stub {
    public final void scheduleCreateService(IBinder token,
    ServiceInfo info, CompatibilityInfo compatInfo, int processState) {
        sendMessage(H.CREATE_SERVICE, s);
    }
}
class H extends Handler {
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CREATE_SERVICE:
                handleCreateService((CreateServiceData)msg.obj);
                break;
        }
    }
}
private void handleCreateService(CreateServiceData data) {
    //1 获取应用的描述对象LoadedApk
    LoadedApk packageInfo = getPackageInfoNoCheck(
        data.info.applicationInfo, data.compatInfo);
    Service service = null;
    java.lang.ClassLoader cl = packageInfo.getClassLoader();
    //2 通过1拿到到描述对象，通过类加载器加装处真正的Service对象
    service = packageInfo.getAppFactory()
        .instantiateService(cl, data.info.name, data.intent);
    //3 根据1处拿到的描述对象，创建ContextImpl，即Service的上下文
     ContextImpl context = ContextImpl.getImpl(service.createServiceBaseContext(this, packageInfo));
    //4 通过attach把ContextImpl和service关联起来
    service.attach(context, this, data.info.name, data.token, app,
                   ActivityManager.getService());
    //5 调用service的onCreate
    service.onCreate();
    //6 本地用ArrayMap存放已经启动好的service
    mServices.put(data.token, service);
    //...
}
```

先拿到应用的描述对象，再创建service和创建ContextImpl，通过attach把service和ContextImpl关联起来，再执行Service的onCreate方法，终于到应用的onCreate回调了。注释6会把创建好的service缓存到mServices，方便ams下次调用。此时service已经创建好了，继续看ActiveServices.sendServiceArgsLocked启动服务。

```java
private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg, boolean oomAdjusted) {
    //1 startService的方式启动的，pendingStarts不为空。bindservice的pendingStarts为空
    final int N = r.pendingStarts.size();
    if (N == 0) {
        return;
    }

    //...
    //2 只有startservice的才会走这里
    r.app.getThread().scheduleServiceArgs(r, slice);
    //...
}
```

注释1会根据根据前面存进去的pendingStarts缓存来区分是否走注释2的流程，startservice启动的pendingStarts不为空，bindservice的pendingStarts为空。注释2处scheduleServiceArgs又是熟悉的套路，binder通信调用到ApplicatinThread的scheduleServiceArgs，再通过hander调用到ActivityThread的handleServiceArgs方法

```java
private void handleServiceArgs(ServiceArgsData data) {
    //...
    Service s = mServices.get(data.token);
    res = s.onStartCommand(data.args, data.flags, data.startId);
    //...
}
```

从创建service时缓存的mServices中拿到具体的service，并回调onStartCommand，到此，startService的流程就结束了。

上面还有个进程不存在的场景，即在bringUpServiceLocked方法里拿到的ProcessRecord为空，就会通过AMS.startProcessLocked来启动进程，并回调到ActivityThread的main方法。这个过程在Activity启动流程中已经详细介绍了，这里只关注跟服务相关的。

> frameworks/base/core/java/android/app/ActivityThread.java 

```java
public static void main(String[] args) {
     //...
    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);
     //...
}
private void attach(boolean system, long startSeq) {
    //...
    final IActivityManager mgr = ActivityManager.getService();
    mgr.attachApplication(mAppThread, startSeq);
    //...
}
```

进程创建完后调用AMS.attachApplication

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
public final void attachApplication(IApplicationThread thread, long startSeq) {
    synchronized (this) {
        attachApplicationLocked(thread, callingPid, callingUid, startSeq);
    }
}
final ActiveServices mServices;
private boolean attachApplicationLocked(@NonNull IApplicationThread thread,
                                        int pid, int callingUid, long startSeq) {
    //...
    didSomething |= mServices.attachApplicationLocked(app, processName);
    //...
}
```

这里只关注跟服务相关的流程，调用到ActiveServices.attachApplicationLocked

```java
boolean attachApplicationLocked(ProcessRecord proc, String processName)
    throws RemoteException {
    //...
    //1 有要启动的服务
    if (mPendingServices.size() > 0) {
        ServiceRecord sr = null;

        for (int i=0; i<mPendingServices.size(); i++) {
            sr = mPendingServices.get(i);
            mPendingServices.remove(i);
            i--;
            //2 真正去启动服务
            realStartServiceLocked(sr, proc, thread, pid, uidRecord, sr.createdFromFg,
                                   true);
        }
    }
    //...
}
```

mPendingServices里存着要启动的服务，注释2调用realStartServiceLocked去真正启动服务，后面流程跟进程已存在的完全一样。

#### bindService

绑定流程和启动流程有细微差别，表现在bindservice时需要传个ServiceConnection接口，绑定成功后会回调该接口。另外，bindservice>service.onCreate>service.onBind，多次调用bindservice只会调用一次onBind，而startService>>service.onCreate>service.onStartCommand，多次调用startService会执行多次onStartCommand。

> frameworks/base/core/java/android/content/ContextWrapper.java

```java
Context mBase;
public boolean bindService(Intent service, ServiceConnection conn,
                           int flags) {
    return mBase.bindService(service, conn, flags);
}
```

一样的套路，除了传ServiceConnection接口，一般flags会传Context.BIND_AUTO_CREATE

> frameworks/base/core/java/android/app/ContextImpl.java

```java
final @NonNull LoadedApk mPackageInfo;
public boolean bindService(Intent service, ServiceConnection conn, int flags) {
    return bindServiceCommon(service, conn, flags, null, mMainThread.getHandler(), null,getUser());
}
private boolean bindServiceCommon(Intent service, ServiceConnection conn, int flags,
                                  String instanceName, Handler handler, Executor executor, UserHandle user) {
    IServiceConnection sd;
    if (executor != null) {
        sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), executor, flags);
    } else {
        //1 传过来的executor为空，走这里传的是hander
        sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
    }
    //...
    //2 AMS.bindServiceInstance
    int res = ActivityManager.getService().bindServiceInstance(
        mMainThread.getApplicationThread(), getActivityToken(), service,
        service.resolveTypeIfNeeded(getContentResolver()),
        sd, flags, instanceName, getOpPackageName(), user.getIdentifier());
    return res != 0;
}
```

注意，注释1处通过LoadedApk.getServiceDispatcher来获取IServiceConnection，可以把IServiceConnection理解成对client端定义的ServiceConnection做了封装，IServiceConnection本质是个binder对象，里面包括了ServiceConnection接口，把这binder传给ams后，等绑定成功后ams会回调此binder对象，binder对象再通过内部封装的ServiceConnection回调其onServiceConnected接口。注释2处把构造好的sd通过AMS.bindServiceInstance传给ams绑定。先往注释1里看下怎么构造IServiceConnection

> frameworks/base/core/java/android/app/LoadedApk.java

```java
private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mServices= new ArrayMap<>();
public final IServiceConnection getServiceDispatcher(ServiceConnection c, Context context, Handler handler, int flags) {
    return getServiceDispatcherCommon(c, context, handler, null, flags);
}
private IServiceConnection getServiceDispatcherCommon(ServiceConnection c,Context context, Handler handler, Executor executor, int flags) {
    synchronized (mServices) {
        LoadedApk.ServiceDispatcher sd = null;
        //1 先从mServices缓存里找
        ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
        if (map != null) {
            sd = map.get(c);
        }
        if (sd == null) {
            //2 没找到的话new出来
            if (executor != null) {
                sd = new ServiceDispatcher(c, context, executor, flags);
            } else {
                sd = new ServiceDispatcher(c, context, handler, flags);
            }
            if (map == null) {
                map = new ArrayMap<>();
                mServices.put(context, map);
            }
            //3 把new出来的sd缓存起来
            map.put(c, sd);
        } else {
            sd.validate(context, handler, executor);
        }
        return sd.getIServiceConnection();
    }
}
```

注释1 先从mServices缓存里找，能找到就直接返回，不能找到就new出来再存缓存。ServiceDispatcher是LoadedApk的内部类

```java
public final class LoadedApk {
    static final class ServiceDispatcher {
        //1 ServiceDispatcher的构造函数中new了静态内部类InnerConnection
        ServiceDispatcher(ServiceConnection conn,Context context, Handler activityThread, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = activityThread;
            mActivityExecutor = null;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }
        private final ServiceDispatcher.InnerConnection mIServiceConnection;
        IServiceConnection getIServiceConnection() {
            return mIServiceConnection;
        }
        
        //2 InnerConnection是ServiceDispatcher的静态内部类，本质是个binder
        private static class InnerConnection extends IServiceConnection.Stub {
            final WeakReference<LoadedApk.ServiceDispatcher> mDispatcher;
            InnerConnection(LoadedApk.ServiceDispatcher sd) {
                mDispatcher = new WeakReference<LoadedApk.ServiceDispatcher>(sd);
            }
            public void connected(ComponentName name, IBinder service, boolean dead)
                throws RemoteException {
                LoadedApk.ServiceDispatcher sd = mDispatcher.get();
                if (sd != null) {
                    //3 binder回调connected后，内部类调用外部sd的connected方法
                    sd.connected(name, service, dead);
                }
            }
        }
    }
}
```

结构稍微有点复杂，ServiceDispatcher为LoadedApk内部类，InnerConnection又是ServiceDispatcher的静态内部类，持有外部类ServiceDispatcher的弱引用，InnerConnection又是个binder。可以联想到这么设计的目的就是把这个binder传给ams，绑定成功后ams回调binder这里定义的connected方法（注释3）。sd.connected方法我们后面再分析。继续AMS.bindServiceInstance看AMS是怎么绑定服务的。

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
public int bindServiceInstance(IApplicationThread caller, IBinder token, Intent service,
                               String resolvedType, IServiceConnection connection, int flags, String instanceName,
                               String callingPackage, int userId) throws TransactionTooLargeException {
    return bindServiceInstance(caller, token, service, resolvedType, connection, flags,
                               instanceName, false, 0, null, callingPackage, userId);
}
private int bindServiceInstance(IApplicationThread caller, IBinder token, Intent service,
                                String resolvedType, IServiceConnection connection, int flags, String instanceName,
                                boolean isSdkSandboxService, int sdkSandboxClientAppUid,
                                String sdkSandboxClientAppPackage, String callingPackage, int userId)
    throws TransactionTooLargeException {
    return mServices.bindServiceLocked(caller, token, service, resolvedType, connection,
                                       flags, instanceName, isSdkSandboxService, sdkSandboxClientAppUid,
                                       sdkSandboxClientAppPackage, callingPackage, userId);
}
```

到ActiveService服务管理类的bindServiceLocked方法

> frameworks/base/services/core/java/com/android/server/am/ActiveServices.java

```java
final ArrayMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections = new ArrayMap<>();
int bindServiceLocked(IApplicationThread caller, IBinder token, Intent service,
                      String resolvedType, final IServiceConnection connection...){
    //...
    //1 校验调用的进程是否存在
    final ProcessRecord callerApp = mAm.getRecordForAppLOSP(caller);
    if (callerApp == null) {
        throw new SecurityException(
            "Unable to find app for caller " + caller
            + " (pid=" + callingPid
            + ") when binding service " + service);
    }
    //2 校验调用的进程的activity是否存在
    activity = mAm.mAtmInternal.getServiceConnectionsHolder(token);
    if (activity == null) {
        Slog.w(TAG, "Binding with unknown activity: " + token);
        return 0;
    }

    //3 检索是否存在要启动的服务，如果存在，就把ServiceRecord封装到ServiceLookupResult
    ServiceLookupResult res = retrieveServiceLocked(service, instanceName,isSdkSandboxService, sdkSandboxClientAppUid, sdkSandboxClientAppPackage,resolvedType, callingPackage, callingPid, callingUid, userId, true,callerFg,isBindExternal, allowInstant);
    ServiceRecord s = res.record;

    //4 检索app的绑定信息
    AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
    ConnectionRecord c = new ConnectionRecord(b, activity,
                                              connection, flags, clientLabel, clientIntent,
                                              callerApp.uid, callerApp.processName, callingPackage, res.aliasComponent);
    IBinder binder = connection.asBinder();
    s.addConnection(binder, c);
    b.connections.add(c);
    activity.addConnection(c);

    //5 缓存绑定信息到mServiceConnections，解绑的时候会从这里拿
    ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
    if (clist == null) {
        clist = new ArrayList<>();
        mServiceConnections.put(binder, clist);
    }
    clist.add(c);

    if ((flags&Context.BIND_AUTO_CREATE) != 0) {
        //6 由于bindservice时传的flag是BIND_AUTO_CREATE，执行里面的bringUpServiceLocked
        if (bringUpServiceLocked(s, service.getFlags(), callerFg, false,
                                 permissionsReviewRequired, packageFrozen, true) != null) {
            return 0;
        }
    }

    //7如果要绑定的服务已经绑定过，会回调connected接口。如果服务没绑定过，会请求绑定服务流程
    //也就是说多次调用bindService，并不会多次执行onBind方法，但是ServiceConnection的onServiceConnected方法会多次回调
    if (s.app != null && b.intent.received) {
        c.conn.connected(clientSideComponentName, b.intent.binder, false);
    }else if (!b.intent.requested) {
        requestServiceBindingLocked(s, b.intent, callerFg, false);
    }
}
```

这个方法是绑定服务的核心方法。

注释1和注释2先分别校验客户端进程和activity是否存在，不存在直接退出此流程。

注释3处retrieveServiceLocked这个方法前面已经介绍了，这里会拿到service的描述对象ServiceRecord

注释4先构建了AppBindRecord，然后new ConnectionRecord创建了连接记录，并通过ServiceRecord.addConnection把客户端的IServiceConnection和ConnectionRecord关联起来。先看retrieveAppBindingLocked

```java
final ArrayMap<Intent.FilterComparison, IntentBindRecord> bindings= new ArrayMap<Intent.FilterComparison, IntentBindRecord>();
final ArrayMap<ProcessRecord, AppBindRecord> apps = new ArrayMap<ProcessRecord, AppBindRecord>();
public AppBindRecord retrieveAppBindingLocked(Intent intent, ProcessRecord app){
    Intent.FilterComparison filter = new Intent.FilterComparison(intent);
    //1 一对多
    IntentBindRecord i = bindings.get(filter);
    if (i == null) {
        i = new IntentBindRecord(this, filter);
        bindings.put(filter, i);
    }
    AppBindRecord a = i.apps.get(app);
    if (a != null) {
        return a;
    }
    a = new AppBindRecord(this, i, app);
    i.apps.put(app, a);
    return a;
}
```

注意到绑定的服务是一对多的关系，多个应用可以同时绑定同一个服务。所以注释1处先把service的参数封装成过滤条件，然后从bindings缓存中查找IntentBindRecord，这个对象里包含了此服务的所有绑定进程。再从IntentBindRecord里根据进程去查找AppBindRecord，找不到就new一个存进去。

继续上面注释5处会缓存绑定信息到mServiceConnections，解绑的时候会从这里拿

注释6处由于bindservice时传的flag是BIND_AUTO_CREATE，执行里面的bringUpServiceLocked，这方法上面详细介绍了，会先去获取服务端的进程，如果存在，就走sendServiceArgsLocked，startService启动的话会回调onStartCommand，bindService的不会走这个流程。如果进程不存在，就通过AMS去拉相应的服务进程，待进程启动后通过attachApplication再走服务的启动或者绑定流程。

注释7处如果服务没有绑定过，b.intent.requested为false，会调requestServiceBindingLocked走绑定流程，如果服务绑定过，只会回调connected接口。

```java
private final boolean requestServiceBindingLocked(ServiceRecord r,IntentBindRecord i,boolean execInFg, boolean rebind) {
    //1 服务端还没启动，直接返回
    if (r.app == null || r.app.getThread() == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
    //...
    r.app.getThread().scheduleBindService(r, i.intent.getIntent(), rebind,
                                          r.app.mState.getReportedProcState());
    if (!rebind) {
        i.requested = true;
    }
    i.hasBound = true;
    i.doRebind = false;
    return true;
}
```

注释1处判断服务端是否已经启动，未启动的话直接返回，如果已经启动，就会执行onBind流程。



对于注释6，疑惑的是，startService和bindService都会执行bringUpServiceLocked，服务端未启动时，都会拉起服务进程，问题是AMS是怎么判断是走的startService还是bindService，因为服务进程启动后，还要分别执行onStartComman或者onBind

服务进程不存在时这段流程调用关系：ActiveServices.bringUpServiceLocked >AMS.startProcessLocked > ActivityThread.main>ActivityThread.attach>AMS.attachApplication>AMS.attachApplicationLocked>ActiveServices.attachApplicationLocked>ActiveServices.realStartServiceLocked，

```java
private void realStartServiceLocked(ServiceRecord r, ProcessRecord app,
                                    IApplicationThread thread...) {
    //...
    //1 调用到要启动服务的进程的scheduleCreateService去创建服务
    thread.scheduleCreateService(r, r.serviceInfo,
                                 mAm.compatibilityInfoForPackage(r.serviceInfo.applicationInfo),
                                 app.mState.getReportedProcState());
    //...
    //2 执行binding流程
    requestServiceBindingsLocked(r, execInFg);
    //...
    //3 执行onStartCommand流程
    sendServiceArgsLocked(r, execInFg, true);
    //...
}
//4 遍历了所有的bindings缓存，分别执行requestServiceBindingLocked来回调起onBind
private final void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg)
    throws TransactionTooLargeException {
    for (int i=r.bindings.size()-1; i>=0; i--) {
        IntentBindRecord ibr = r.bindings.valueAt(i);
        if (!requestServiceBindingLocked(r, ibr, execInFg, false)) {
            break;
        }
    }
}
```

注释1，start和bind都会先执行service.onCreate

注释2和4，会去遍历之前缓存的bindings，分别执行requestServiceBindingLocked去执行scheduleBindService绑定流程，startService的bindings为空

注释3上面详细讲过了，根据pendingStarts去区分，startService的里面有值并走startCommand流程，bindService的不走。

继续看下scheduleBindService

```java
public final void scheduleBindService(IBinder token, Intent intent,
                                      boolean rebind, int processState) {
    sendMessage(H.BIND_SERVICE, s);
}
//handler：
//  case BIND_SERVICE:
//     handleBindService((BindServiceData)msg.obj);
private void handleBindService(BindServiceData data) {
    //...
    ActivityManager.getService().publishService( data.token, data.intent, binder);
    //...
}

//AMS.publishService
public void publishService(IBinder token, Intent intent, IBinder service) {
    synchronized(this) {
        mServices.publishServiceLocked((ServiceRecord)token, intent, service);
    }
}
```

ActiveServices.publishServiceLocked

```java
void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
   	//1 根据intent构建过滤条件，查找之前存的IntentBindRecord
    Intent.FilterComparison filter= new Intent.FilterComparison(intent);
    IntentBindRecord b = r.bindings.get(filter);
    if (b != null && !b.received) {
        b.binder = service;
        b.requested = true;
        b.received = true;
        ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> clist = connections.valueAt(conni);
            for (int i=0; i<clist.size(); i++) {
                ConnectionRecord c = clist.get(i);
                final ComponentName clientSideComponentName = c.aliasComponent != null ? c.aliasComponent : r.name;
                //遍历所有的clist，回调IServiceConnection的connected
                c.conn.connected(clientSideComponentName, service, false);
            }
        }
    }
}
```

publishServiceLocked发布服务时会先根据intent构建过滤条件，查找到所有的ConnectionRecord列表，并分别回调IServiceConnection的connected接口。

```java
private static class InnerConnection extends IServiceConnection.Stub {
    public void connected(ComponentName name, IBinder service, boolean dead)throws RemoteException {
        LoadedApk.ServiceDispatcher sd = mDispatcher.get();
        if (sd != null) {
            sd.connected(name, service, dead);
        }
    }
}
public void connected(ComponentName name, IBinder service, boolean dead) {
    if (mActivityExecutor != null) {
        mActivityExecutor.execute(new RunConnection(name, service, 0, dead));
    } else if (mActivityThread != null) {
        mActivityThread.post(new RunConnection(name, service, 0, dead));
    } else {
        doConnected(name, service, dead);
    }
}
private final class RunConnection implements Runnable {
    public void run() {
        if (mCommand == 0) {
            doConnected(mName, mService, mDead);
        } else if (mCommand == 1) {
            doDeath(mName, mService);
        }
    }
}
public void doConnected(ComponentName name, IBinder service, boolean dead) {
    //...
    if (service != null) {
        //1 回调onServiceConnected
        mConnection.onServiceConnected(name, service);
    } else {
        mConnection.onNullBinding(name);
    }
}
```

AMS回调客户端的IServiceConnection.connected接口，此接口调用外部类ServiceDispatcher.connected，这里直接往主线程post个任务，此run方法相当于在客户端的主线程运行，执行doConnected，此方法里会回调客户端定义的ServiceConnection.onServiceConnected。至此，bindService也介绍完了。























