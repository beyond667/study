#### 深入理解ContentProvider

##### 简介

ContentProvider作为四大组件之一，主要负责为其他应用提供数据共享。其他应用可以在不知道数据来源，路径的情况下，对共享数据进程增删改查的操作。许多内置应用数据都是通过ContentProvider提供给用户使用的，比如通讯录，音视频，图片，文件等。（本文基于Android13）

> ContentProvider数据传递基于binder+共享内存，关于共享内存可以参照[共享内存](https://github.com/beyond667/study/blob/master/note/%E5%85%B1%E4%BA%AB%E5%86%85%E5%AD%98.md) 

##### 使用

参照示例[MyContentProvider](https://github.com/beyond667/PaulTest/blob/master/server/src/main/java/com/paul/test/server/provider/MyContentProvider.java) ，本文只简单介绍使用流程。  

内容提供者进程：继承ContentProvider抽象类，实现其6个抽象方法（增删改查等），并在manifest中定义authorities

客户端：根据内容提供者定义的authorities构建个uri（类似这样"content://authorities/person"），通过context.getContentResolver.query来做查询操作（增删改查类似），query操作返回的Cursor游标，操作游标即可拿到内容提供者提供的数据。

#### ContentProvider的原理

以query操作为例，其他类似。

先看context.getContentResolver拿到的ContentResolver到底是个什么

```java
public class ContextWrapper extends Context {
    Context mBase;
    public ContentResolver getContentResolver() {
        return mBase.getContentResolver();
    }
}

class ContextImpl extends Context {
    private final ApplicationContentResolver mContentResolver;
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    private ContextImpl(ContextImpl container...) {
        //...
        //ContextImpl初始化时就new了ApplicationContentResolver
        mContentResolver = new ApplicationContentResolver(this, mainThread);
    }

    private static final class ApplicationContentResolver extends ContentResolver {
        private final ActivityThread mMainThread;
        public ApplicationContentResolver(Context context, ActivityThread mainThread) {
            super(context);
            mMainThread = Objects.requireNonNull(mainThread);
        }
    }
}
```

以上代码可以看到，getContentResolver拿到的实际是ApplicationContentResolver对象，此对象在ContextImpl初始化的时候也构建了ApplicationContentResolver对象，并且还持有了ActivityThread对象。由于ApplicationContentResolver未重写query方法，所以query实际调用的还是子类ContentResolver的query方法

>frameworks/base/core/java/android/content/ContentResolver.java

```java
public final @Nullable Cursor query(final Uri uri...) {
    //...
    //1 获取服务端的代理IContentProvider
    IContentProvider unstableProvider = acquireUnstableProvider(uri);
    if (unstableProvider == null) {
        return null;
    }
    //2 调用代理对象的query
    qCursor = unstableProvider.query(mContext.getAttributionSource(), uri, projection,
                                     queryArgs, remoteCancellationSignal);
    //3 处理拿到的cursor结果
    if (qCursor == null) {
        return null;
    }
    final CursorWrapperInner wrapper = new CursorWrapperInner(qCursor, provider);
    return wrapper;
    //...
}
public final IContentProvider acquireUnstableProvider(Uri uri) {
    if (!SCHEME_CONTENT.equals(uri.getScheme())) {
        return null;
    }
    String auth = uri.getAuthority();
    if (auth != null) {
        //4 还是调用了父类ApplicationContentResolver的acquireUnstableProvider
        return acquireUnstableProvider(mContext, uri.getAuthority());
    }
    return null;
}
public interface IContentProvider extends IInterface {
    ......
}
```

+ 注释1通过acquireUnstableProvider去拿服务端的IContentProvider代理对象，拿不到就返回null
+ 注释2调用代理对象的query方法
+ 注释3处理返回的结果Cursor，不为空的话就封装成CursorWrapperInner返回

先看注释4处是怎么获取IContentProvider代理对象

```java
private static final class ApplicationContentResolver extends ContentResolver {
    private final ActivityThread mMainThread;

    protected IContentProvider acquireUnstableProvider(Context c, String auth) {
        return mMainThread.acquireProvider(c,ContentProvider.getAuthorityWithoutUserId(auth), resolveUserIdFromAuthority(auth), false);
    }
}
```

这里调用了ActivityThread的acquireProvider

> frameworks/base/core/java/android/app/ActivityThread.java

```java
public final IContentProvider acquireProvider(
    Context c, String auth, int userId, boolean stable) {
    //1 先从缓存查是不是已经访问过此provider 有的话直接返回
    final IContentProvider provider = acquireExistingProvider(c, auth, userId, stable);
    if (provider != null) {
        return provider;
    }
	//...
    ContentProviderHolder holder = null;
    //2 通过AMS去获取ContentProviderHolder对象
    holder = ActivityManager.getService().getContentProvider(
        getApplicationThread(), c.getOpPackageName(), auth, userId, stable);
    if (holder == null) {
        return null;
    }
    //...  
    //3 对拿到的provider进行安装
    holder = installProvider(c, holder, holder.info, true, holder.noReleaseNeeded, stable);
    return holder.provider;
}
```

+ 注释1会根据auth和userid组成的key去mProviderMap缓存中拿，如果之前已经访问过此provider，会在注释3 installProvider里存到mProviderMap缓存中，这时就直接返回。拿不到就往下走注释2
+ 注释2通过AMS去获取ContentProviderHolder对象，这个对象能在进程间传递，肯定是实现了Parcelable接口，此对象里还包含了IContentProvider代理对象。
+ 注释3对拿到的provider进行安装，以便后面使用，这里会把拿到的provider存进mProviderMap缓存。需要注意此处调用installProvider传的holder不为空，并且此holder是从ams返回的，installProvider我们后面再看。

我们看继续看注释2处AMS怎么获取ContentProviderHolder对象

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
final ContentProviderHelper mCpHelper;
public final ContentProviderHolder getContentProvider(IApplicationThread caller, String callingPackage...) {
    return mCpHelper.getContentProvider(caller, callingPackage, name, userId);
}
```

Android13上这里抽了个帮助类，专门维护ContentProvider

> frameworks/base/services/core/java/com/android/server/am/ContentProviderHelper.java

```java
ContentProviderHolder getContentProvider(IApplicationThread caller, String callingPackage,
                                         String name, int userId, boolean stable) {
    //...校验
    return getContentProviderImpl(caller, name, null, callingUid, callingPackage, null, stable, userId);
}
```

此方法里加了些校验，直接调用getContentProviderImpl

```java
private ContentProviderHolder getContentProviderImpl(IApplicationThread caller, String name, IBinder token...) {

    ContentProviderRecord cpr;
    ContentProviderConnection conn = null;
    ProviderInfo cpi = null;
    boolean providerRunning = false;

    ProcessRecord r = null;
    r = mService.getRecordForAppLOSP(caller);

	//1 先从mProviderMap缓存去获取ContentProviderRecord，如果能拿到并且其进程存活，先查下provider是否在运行
    cpr = mProviderMap.getProviderByName(name, userId);
    if (cpr != null && cpr.proc != null) {
        providerRunning = !cpr.proc.isKilled();
    }

    if (providerRunning) {
        cpi = cpr.info;
        //2 provider已经在运行时如果配置了multiprocess=true或者要启动的contentprovider就是调用进程
        if (r != null && cpr.canRunHere(r)) {
            ContentProviderHolder holder = cpr.newHolder(null, true);
            holder.provider = null;
            return holder;
        }
    }

    if (!providerRunning) {
        //3 provider没在运行，先通过pms去获取ContentProvider的信息
        cpi = AppGlobals.getPackageManager().resolveContentProvider(name...);
        if (cpi == null) {
            return null;
        }
        ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
        //4 再在缓存里通过getProviderByClass找下ContentProviderRecord是否存在
        cpr = mProviderMap.getProviderByClass(comp, userId);
        boolean firstClass = cpr == null || (dyingProc == cpr.proc && dyingProc != null);
        if (firstClass) {
            //5 不存在就先跟注释2一样先判断是不是在调用进程启动，否则就直接new出来ContentProviderRecord
            if (r != null && cpr.canRunHere(r)) {
                return cpr.newHolder(null, true);
            }
            cpr = new ContentProviderRecord(mService, cpi, ai, comp, singleton);
        }
		//6 到这里一样的套路再判断下
        if (r != null && cpr.canRunHere(r)) {
            return cpr.newHolder(null, true);
        }

		//7 到这里就说明要启动的contentprovider跟调用进程不是同一个进程。这里先从已经启动的providers缓存里找目标进程是否存在
        final int numLaunchingProviders = mLaunchingProviders.size();
        int i;
        for (i = 0; i < numLaunchingProviders; i++) {
            if (mLaunchingProviders.get(i) == cpr) {
                break;
            }
        }

        //8 找不到的话i==numLaunchingProviders,说明provider还没启动，可能进程没启动或者进程已启动但是provider没启动
        if (i >= numLaunchingProviders) {
            //9 先看provider的进程是否存在
            ProcessRecord proc = mService.getProcessRecordLocked(cpi.processName, cpr.appInfo.uid);
            IApplicationThread thread;
            //10 目标进程存在
            if (proc != null && (thread = proc.getThread()) != null
                && !proc.isKilled()) {
                ProcessProviderRecord pr = proc.mProviders;
                //11 先看目标进程的provider是否已经记录过此provider，没记录过就先记录下，再执行ActivityThread.scheduleInstallProvider去启动provider
                if (!pr.hasProvider(cpi.name)) {
                    pr.installProvider(cpi.name, cpr);
                    thread.scheduleInstallProvider(cpi);
                }
            }else{
                //12 不存在就通过AMS.startProcessLocked启动进程
                proc = mService.startProcessLocked(cpi.processName, cpr.appInfo, false, 0...);
            }
            cpr.launchingApp = proc;
            //13 添加到mLaunchingProviders缓存
            mLaunchingProviders.add(cpr);
        }

        //14 添加到mProviderMap缓存
        if (firstClass) {
            mProviderMap.putProviderByClass(comp, cpr);
        }
        mProviderMap.putProviderByName(name, cpr);
        //15 构建AMS与此ContentProvider的连接对象ContentProviderConnection
        conn = incProviderCountLocked(r, cpr, token, callingUid, callingPackage, callingTag,
                                      stable, false, startTime, mService.mProcessList, expectedUserId);
        if (conn != null) {
            conn.waiting = true;
        }
    }


    final long timeout =SystemClock.uptimeMillis() + ContentResolver.CONTENT_PROVIDER_READY_TIMEOUT_MILLIS;
    boolean timedOut = false;
     synchronized (cpr) {
         // 16 while循环等待provider启动，没启动的话一直等待到设置的超时时长
         while (cpr.provider == null) {
             final long wait = Math.max(0L, timeout - SystemClock.uptimeMillis());
             if (conn != null) {
                 conn.waiting = true;
             }
             cpr.wait(wait);
             if (cpr.provider == null) {
                 timedOut = true;
                 break;
             }
         }
     }
    //17 超时的话就返回空，否则返回已经包装了conn的ContentProviderHolder对象
    if (timedOut) {
        return null;
    }
    return cpr.newHolder(conn, false);
}
public boolean canRunHere(ProcessRecord app) {
    //provider配置了multiprocess为true或者目标进程和调用进程是同一个
    return (info.multiprocess || info.processName.equals(app.processName))
        && uid == app.info.uid;
}
```
此方法的ContentProvider的核心方法，此方法较长，只关注核心流程
+ 注释1先从mProviderMap缓存去获取ContentProviderRecord，如果能拿到并且其进程存活，先查下provider是否在运行。这里既然是取那肯定有存的地方，注释14即往mProviderMap里存。
+ 注释2 provider已经在运行时如果配置了multiprocess=true或者要启动的contentprovider就是调用进程就说明只用在调用进程启动，注意此时cpr.newHolder(null, true)构建的ContentProviderHolder第一个参数ContentProviderConnection为空，并且标记local为true，说明此provider的本进程的对象
+ 注释3 provider没在运行，先通过pms去获取ContentProvider的信息
+ 注释4 再在缓存里通过getProviderByClass找下ContentProviderRecord是否存在
+ 注释5 在4都找不到的话就先跟注释2一样先判断是不是在调用进程启动，是的话就直接返回，否则就直接new出来ContentProviderRecord
+ 注释6一样的套路再检查下，因为5处firstClass可能为false，这里要确保是不是只在调用进程启动provider
+ 注释7 到这里就说明要启动的contentprovider跟调用进程肯定不是同一个进程。这里先从已经启动的providers缓存mLaunchingProviders里找目标进程是否存在，同样有取就有存，注释13即存
+ 注释8 找不到的话i==numLaunchingProviders,说明provider还没启动，可能进程没启动或者进程已启动但是provider没启动
+ 注释9通过AMS.getProcessRecordLocked去拿目标进程ProcessRecord
+ 注释10如果9能拿到并且其ActivityThread不为空并且没被杀，说明目标进程可用
+ 注释11 在10处如果能拿到目标进程，先看目标进程的provider是否已经记录过此provider，没记录过就先记录下，再执行ActivityThread.scheduleInstallProvider去启动provider，scheduleInstallProvider我们等下再看
+ 注释12在10处如果拿不到目标进程，就通过AMS.startProcessLocked启动目标进程
+ 注释13 14缓存到mLaunchingProviders和mProviderMap，与注释1和7对应
+ 注释15构建AMS与此ContentProvider的连接对象ContentProviderConnection，后面AMS就通过此对象与ContentProvider通信
+ 注释16去等待此provider启动，如果等待时间内还没启动就按超时处理
+ 注释17如果超时就返回空，否则返回封装了ContentProviderConnection的ContentProviderHolder对象

继续看注释11和注释12目标进程存在或者不存在的处理流程

##### 进程存在

先看存在的话调用ActivityThread.scheduleInstallProvider

```java
public final class ActivityThread extends ClientTransactionHandler{
    private class ApplicationThread extends IApplicationThread.Stub {
        public void scheduleInstallProvider(ProviderInfo provider) {
            sendMessage(H.INSTALL_PROVIDER, provider);
        }
    }
    class H extends Handler {
        public void handleMessage(Message msg) {
            case INSTALL_PROVIDER:
            handleInstallProvider((ProviderInfo) msg.obj);
            break;
        }
    }
    public void handleInstallProvider(ProviderInfo info) {
        installContentProviders(mInitialApplication, Arrays.asList(info));
    }
    private void installContentProviders(Context context, List<ProviderInfo> providers) {
        final ArrayList<ContentProviderHolder> results = new ArrayList<>();
        for (ProviderInfo cpi : providers) {
            //1 安装provider
            ContentProviderHolder cph = installProvider(context, null, cpi, false , true , true );
            if (cph != null) {
                cph.noReleaseNeeded = true;
                results.add(cph);
            }
        }
        //2 AMS.publishContentProviders去通过ams发布provider
        ActivityManager.getService().publishContentProviders(getApplicationThread(), results);

    }
}
```

+ 注释1通过installProvider去安装provider，注意这里是传的第二个参数是null，而我们最上面客户端也调用了此方法，传的holder是从ams拿到的，即此参数可以用来判断是客户端还是服务端调用的
+ 注释2调用AMS.publishContentProviders去通过ams发布provider

我们先看注释1的installProvider

```java
private ContentProviderHolder installProvider(Context context,ContentProviderHolder holder, ProviderInfo info,boolean noisy,
                                              boolean noReleaseNeeded, boolean stable) {
    ContentProvider localProvider = null;
    IContentProvider provider;
    //1 服务端调此方法传的holder为空，客户端调用此方法传的不为空
    if (holder == null || holder.provider == null) {
        Context c = null;
        ApplicationInfo ai = info.applicationInfo;
        if (context.getPackageName().equals(ai.packageName)) {
            c = context;
        } 
        //...
        final java.lang.ClassLoader cl = c.getClassLoader();
        LoadedApk packageInfo = peekPackageInfo(ai.packageName, true);
		//2 通过类加载器去加载ContentProvider对象
        localProvider = packageInfo.getAppFactory()
            .instantiateProvider(cl, info.name);
        //3 根据加载出的ContentProvider对象去获取binder代理对象
        provider = localProvider.getIContentProvider();
        if (provider == null) {
            return null;
        }
        //4 执行ContentProvider.onCreate方法
        localProvider.attachInfo(c, info);
    }else{
        //5 客户端直接拿provider
        provider = holder.provider;
    }
    ContentProviderHolder retHolder;
    
    
    IBinder jBinder = provider.asBinder();
    if (localProvider != null) {
        ComponentName cname = new ComponentName(info.packageName, info.name);
        ProviderClientRecord pr = mLocalProvidersByName.get(cname);
        //6 服务端从缓存拿ProviderClientRecord，拿不到的话就通过installProviderAuthoritiesLocked来获取，并放进缓存
        if (pr != null) {
            provider = pr.mProvider;
        } else {
            holder = new ContentProviderHolder(info);
            holder.provider = provider;
            holder.noReleaseNeeded = true;
            pr = installProviderAuthoritiesLocked(provider, localProvider, holder);
            mLocalProviders.put(jBinder, pr);
            mLocalProvidersByName.put(cname, pr);
        }
        retHolder = pr.mHolder;
    } else {
        //7 客户端从缓存拿ProviderRefCount
        ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
        if (prc != null) {
            //...
        } else {
            //8 拿不到也通过installProviderAuthoritiesLocked来获取
            ProviderClientRecord client = installProviderAuthoritiesLocked(
                provider, localProvider, holder);
            if (noReleaseNeeded) {
                prc = new ProviderRefCount(holder, client, 1000, 1000);
            } else {
                prc = stable
                    ? new ProviderRefCount(holder, client, 1, 0)
                    : new ProviderRefCount(holder, client, 0, 1);
            }
            //9 把上面new出来的ProviderRefCount放进缓存
            mProviderRefCountMap.put(jBinder, prc);
        }
        retHolder = prc.holder;
    }
    return retHolder;
}
```

installProvider方法看着很复杂，其实主要处理客户端和服务端的contentProvider的安装，对服务端来说：

+ 注释1处传的是空，走注释2 3 4 6的逻辑
+ 注释2和3通过类加载器加载ContentProvider实例对象，并获取其代理对象provider，并在4处执行contentProvider.attachInfo，里面会执行ContentProvider.onCreate方法
+ 注释6服务端从缓存拿ProviderClientRecord，拿不到的话就通过installProviderAuthoritiesLocked来获取，并放进缓存

对于客户端来说

+ 注释1处传的holder不为空，因为客户端拿到的是ams返回的代理对象，走注释5 7 8 9的逻辑
+ 注释7客户端从缓存拿ProviderRefCount，拿不到在注释8也通过installProviderAuthoritiesLocked来获取ProviderClientRecord，并和holder一起封装到ProviderRefCount里，并以binder作为key存进缓存，这样客户端后面就可以通过这个缓存拿到holder和ProviderClientRecord

注释4的attachInfo

> frameworks/base/core/java/android/content/ContentProvider.java

```java
private void attachInfo(Context context, ProviderInfo info, boolean testing) {
    if (mContext == null) {
        mContext = context;
        //...
        ContentProvider.this.onCreate();
    }
}
```

##### 进程不存在

再继续看目标进程不存在的情况，之前Activity启动流程已经详细看过，这里只关注跟ContentProvider启动相关的。

简单来说AMS通知Zygote进程fork出目标进程，并指定回调ActivityThread.main方法，后面调用过程：ActivityThread.attach>AMS.attachApplication

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
public final void attachApplication(IApplicationThread thread, long startSeq) {
    attachApplicationLocked(thread, callingPid, callingUid, startSeq);
}

private boolean attachApplicationLocked(@NonNull IApplicationThread thread,
                                        int pid, int callingUid, long startSeq) {
    //...只关注ContentProvider相关
    //1 通过generateApplicationProvidersLocked获取ProviderInfo列表
    List<ProviderInfo> providers = normalMode ? mCpHelper.generateApplicationProvidersLocked(app): null;
    
    //2 通知服务端已经绑定成功
     thread.bindApplication(processName, appInfo, providers, null, profilerInfo...);
    //...
}
```

+ 注释1通过generateApplicationProvidersLocked获取ProviderInfo列表
+ 注释2通知服务端AMS已经绑定成功，这个启动流程已经很熟悉了

```java
List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
    final List<ProviderInfo> providers;
    //1 调用pms拿所有的ContentProvider
    providers = AppGlobals.getPackageManager().queryContentProviders(
        app.processName, app.uid, ActivityManagerService.STOCK_PM_FLAGS
        | PackageManager.GET_URI_PERMISSION_PATTERNS
        | PackageManager.MATCH_DIRECT_BOOT_AUTO, /*metaDataKey=*/ null)
        .getList();
    int numProviders = providers.size();
    final ProcessProviderRecord pr = app.mProviders;
    for (int i = 0; i < numProviders; i++) {
        ProviderInfo cpi = providers.get(i);
        //...
        //存到mProviderMap缓存里
        ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
        ContentProviderRecord cpr = mProviderMap.getProviderByClass(comp, app.userId);
        if (cpr == null) {
            cpr = new ContentProviderRecord(mService, cpi, app.info, comp, singleton);
            mProviderMap.putProviderByClass(comp, cpr);
        }
        // 2 调用ProcessProviderRecord.installProvider
        pr.installProvider(cpi.name, cpr);
    }
    //...
    return providers.isEmpty() ? null : providers;
}
//ProcessProviderRecord.java
void installProvider(String name, ContentProviderRecord provider) {
    mPubProviders.put(name, provider);
}
```

+ 注释1通过pms拿所有的ContentProvider列表
+ 注释2遍历1拿到的列表，调用ProcessProviderRecord.installProvider，其实相当于往ProcessRecord.mProviders的缓存mPubProviders里存了所有配置的ContentProvider，AMS这里记录后通知服务端进程已经绑定成功，我们继续看AplicationThread.bindApplication->H.handMessage->ActivityThread.handleBindApplication

```java
private void handleBindApplication(AppBindData data) {
    //...
    Application app;
    //1 先通过类加载器初始化Application，并执行Application的attach
    app = data.info.makeApplicationInner(data.restrictedBackupMode, null);

    //2 安装所有的ContentProvider 这个进程存在时已分析
    if (!ArrayUtils.isEmpty(data.providers)) {
        installContentProviders(app, data.providers);
    }
	//3 执行instrument.onCreate
    mInstrumentation.onCreate(data.instrumentationArgs);
    //4 执行Application.onCreate
    mInstrumentation.callApplicationOnCreate(app);
}
```

注释很清晰，注释2installContentProviders上面已经分析过了，最后都会到AMS.publishContentProviders去通知AMS发布。可以看到ContentProvider的安装过程比application的onCreate还早。

##### AMS.publishContentProviders发布ContentProvider

我们继续看AMS.publishContentProviders

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
public final void publishContentProviders(IApplicationThread caller, List<ContentProviderHolder> providers) {
    //...省略校验
    mCpHelper.publishContentProviders(caller, providers);
}
```

> frameworks/base/services/core/java/com/android/server/am/ContentProviderHelper.java

```java
void publishContentProviders(IApplicationThread caller, List<ContentProviderHolder> providers) {
    if (providers == null) {
        return;
    }
    final ProcessRecord r = mService.getRecordForAppLOSP(caller);
    for (int i = 0, size = providers.size(); i < size; i++) {
        ContentProviderHolder src = providers.get(i);
        if (src == null || src.info == null || src.provider == null) {
            continue;
        }
        ContentProviderRecord dst = r.mProviders.getProvider(src.info.name);
        if (dst == null) {
            continue;
        }
        
        //1 从正在启动的provider缓存里找，找到就从缓存里移除
        for (int j = 0, numLaunching = mLaunchingProviders.size(); j < numLaunching; j++) {
            if (mLaunchingProviders.get(j) == dst) {
                mLaunchingProviders.remove(j);
                j--;
                numLaunching--;
            }
        }
        //2 给ContentProviderRecord的provider赋值为要发布的provider，并notifyAll通知客户端已经发布成功
        synchronized (dst) {
            dst.provider = src.provider;
            dst.setProcess(r);
            dst.notifyAll();
        }
    }
}
```

+ 注释1从正在启动的provider缓存里找，找到就从缓存里移除
+ 注释2给ContentProviderRecord的provider赋值为要发布的provider，并notifyAll通知客户端已经发布成功

此时AMS已经发布成功，并通过notifyAll通知，之前getContentProviderImpl时wait那里被唤醒，AMS把封装好的ContentProviderHolder返回给客户端，客户端通过installProvider进行安装后把IcontentProvider代理对象返回，后面调代理对象的query方法即通过binder调用到服务端的query方法

```java
public abstract class ContentProvider implements ContentInterface, ComponentCallbacks2 {
    //...
    private Transport mTransport = new Transport();
    class Transport extends ContentProviderNative {
        volatile ContentInterface mInterface = ContentProvider.this;
        .......
            @Override
            public Cursor query(String callingPkg, Uri uri, @Nullable String[] projection,
                                @Nullable Bundle queryArgs, @Nullable ICancellationSignal cancellationSignal) {
            //...
            // 1 ContentProvider.this.query
            cursor = mInterface.query(
                uri, projection, queryArgs,
                CancellationSignal.fromTransport(cancellationSignal));
            //...
        }
        //...
    }
    //...
    @UnsupportedAppUsage
    public IContentProvider getIContentProvider() {
        return mTransport;
    }
}
```

由于getIContentProvider返回的是Transport，这里调用的Transport.query，在注释1处调用外部类的query，即我们自定义的ContentProvider的query方法。其他增删改查类似。到这里ContentProvider的启动流程就结束了。

##### ContentProvider共享内存的原理

关于共享内存的原理可以参照[共享内存](https://github.com/beyond667/study/blob/master/note/%E5%85%B1%E4%BA%AB%E5%86%85%E5%AD%98.md) ，这里关注ContentProvider在数据传输时是怎么共享内存的。

由上文可只，客户端启动流程中拿到服务端的代理类IContentProvider，并调用代理类的query，代理类实际是ContentProviderProxy，我们从代理类的query开始看

>```
>frameworks/base/core/java/android/content/ContentProviderNative.java
>```

```java
abstract public class ContentProviderNative extends Binder implements IContentProvider {
    final class ContentProviderProxy implements IContentProvider{
        public Cursor query(AttributionSource attributionSource, Uri url...){
            //初始化BulkCursorToCursorAdaptor
            BulkCursorToCursorAdaptor adaptor = new BulkCursorToCursorAdaptor();
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            //1 把上面初始化的BulkCursorToCursorAdaptor的IContentObserver塞到data里
            data.writeStrongBinder(adaptor.getObserver().asBinder());
			//2 binder通信传到服务端
            mRemote.transact(IContentProvider.QUERY_TRANSACTION, data, reply, 0);

            if (reply.readInt() != 0) {
                //3 服务端拿到BulkCursorDescriptor，并调用BulkCursorToCursorAdaptor.initialize实例化本地对象
                BulkCursorDescriptor d = BulkCursorDescriptor.CREATOR.createFromParcel(reply);
                Binder.copyAllowBlocking(mRemote, (d.cursor != null) ? d.cursor.asBinder() : null);
                adaptor.initialize(d);
            } else {
                adaptor.close();
                adaptor = null;
            }
            return adaptor;
        }
    }
}
```

+ 注释1把上面初始化的BulkCursorToCursorAdaptor的IContentObserver塞到data里
+ 注释2通过binder调用服务端
+ 注释3 从服务端拿到BulkCursorDescriptor，并调用BulkCursorToCursorAdaptor.initialize实例化其对象

再看服务端通过binder收到其请求

> frameworks/base/core/java/android/content/ContentProviderNative.java

```java
public boolean onTransact(int code, Parcel data, Parcel reply, int flags){
    //...
    case QUERY_TRANSACTION:
    {
        //...
        Uri url = Uri.CREATOR.createFromParcel(data);
        //拿客户端传过来的IContentObserver binder对象
        IContentObserver observer = IContentObserver.Stub.asInterface(data.readStrongBinder());
		//1 调用ContentProvider的query，拿到的数据放在Cursor里
        Cursor cursor = query(attributionSource, url, projection, queryArgs,cancellationSignal);

        if (cursor != null) {
            CursorToBulkCursorAdaptor adaptor = null;

            //2 服务端也创建个CursorToBulkCursorAdaptor把结果和客户端的IContentObserver关联起来
            adaptor = new CursorToBulkCursorAdaptor(cursor, observer,getProviderName());
            cursor = null;

            //3 关键步骤，获取BulkCursorDescriptor并写进reply返回客户端
            BulkCursorDescriptor d = adaptor.getBulkCursorDescriptor();
            adaptor = null;

            reply.writeNoException();
            reply.writeInt(1);
            d.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            //...
            return true;
        }
    }

}
```

+ 注释1调用我们自定义的ContentProvider的query方法，把结果放进cursor
+ 注释2服务端也创建个CursorToBulkCursorAdaptor把结果和客户端的IContentObserver关联起来
+ 3 关键步骤，获取BulkCursorDescriptor，这里会真正创建共享内存

>frameworks/base/core/java/android/database/CursorToBulkCursorAdaptor.java

```java
public BulkCursorDescriptor getBulkCursorDescriptor() {
    synchronized (mLock) {
        BulkCursorDescriptor d = new BulkCursorDescriptor();
        d.cursor = this;
        d.columnNames = mCursor.getColumnNames();
        d.wantsAllOnMoveCalls = mCursor.getWantsAllOnMoveCalls();
        //1 调用cursor.getCount这里会保证CursorWindow不为空
        d.count = mCursor.getCount();
        //2 这里的CursorWindow一定不为空
        d.window = mCursor.getWindow();
        if (d.window != null) {
            d.window.acquireReference();
        }
        return d;
    }
}
```

这块代码表面看会疑惑，注释2 getWindow拿到的CursorWindow是在什么时候初始化的？具体看注释1的实现SQLiteCursor。

> frameworks/base/core/java/android/database/sqlite/SQLiteCursor

```java
public class SQLiteCursor extends AbstractWindowedCursor {
    public int getCount() {
        if (mCount == NO_COUNT) {
            fillWindow(0);
        }
        return mCount;
    }
    private void fillWindow(int requiredPos) {
        //1 没初始化CursorWindow就先初始化，已经初始化过就clear下
        clearOrCreateWindow(getDatabase().getPath());
        //2 往
		mCount = mQuery.fillWindow(mWindow, requiredPos, requiredPos, true);

    }
}
```

