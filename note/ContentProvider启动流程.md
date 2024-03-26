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
    //3 对拿到的provider
    holder = installProvider(c, holder, holder.info, true, holder.noReleaseNeeded, stable);
    return holder.provider;
}
```

+ 注释1会根据auth和userid组成的key去mProviderMap缓存中拿，如果之前已经访问过此provider，会在注释3 installProvider里存到mProviderMap缓存中，这时就直接返回。拿不到就往下走注释2
+ 注释2通过AMS去获取ContentProviderHolder对象，这个对象能在进程间传递，肯定是实现了Parcelable接口，此对象里还包含了IContentProvider代理对象。
+ 注释3对拿到的provider进行安装，以便后面使用，这里会把拿到的provider存进mProviderMap缓存。

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

getContentProviderImpl这个方法比较长，只关注核心流程

```java
private ContentProviderHolder getContentProviderImpl(IApplicationThread caller, String name, IBinder token...) {

}
```

