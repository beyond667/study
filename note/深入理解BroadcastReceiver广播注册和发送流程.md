### BroadcastReceiver广播注册和发送流程

> BroadcastReceiver作为四大组件之一，广播可以在应用间或应用内发送消息，与订阅-发布即观察者模式类似，底层通过binder通信。（源码基于android13）

#### 分类

广播分为标准广播和有序广播，粘性广播（5.0后已经废弃）。  

有序广播发出去同一时刻只有一个广播接收器能收到这条消息，当本条接收器执行完毕，广播才继续传播。有序广播的接收顺序：

1. 在默认情况下，相同的注册方式下，会按照注册顺序先后接收
2. 按照Priority属性值从大到小排序
3. Priority属性值相同者，动态注册的广播优先。

#### 广播注册流程

广播注册分为静态注册和动态注册。

定义广播

```java
public class BootCompleteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        }
    }
}

```

AndroidManifest.xml中声明广播

```xml
<receiver android:name=".BootCompleteReceiver"
          android:enabled="true"
          android:exported="true">
    <!-- 接收启动完成的广播 -->
    <intent-filter android:priority="1000">
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.ACTION_SCREEN_ON" />
        <action android:name="android.intent.action.ACTION_SCREEN_OFF" />
    </intent-filter>
</receiver>
```

动态注册广播

```java
BootCompleteReceiver bootCompleteReceiver = new BootCompleteReceiver();
IntentFilter screenStatusIF = new IntentFilter();
screenStatusIF.addAction(Intent.ACTION_SCREEN_ON);
screenStatusIF.addAction(Intent.ACTION_SCREEN_OFF);
registerReceiver(bootCompleteReceiver, screenStatusIF);
```

registerReceiver即动态注册广播。

> frameworks/base/core/java/android/content/ContextWrapper.java

```java
Context mBase;
public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
    return mBase.registerReceiver(receiver, filter);
}
```

> frameworks/base/core/java/android/app/ContextImpl.java

```java
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
    return registerReceiver(receiver, filter, null, null);
}
@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,int flags) {
    return registerReceiver(receiver, filter, null, null, flags);
}
@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,String broadcastPermission, Handler scheduler) {
    return registerReceiverInternal(receiver, getUserId(),filter, broadcastPermission, scheduler, getOuterContext(), 0);
}
```

层层套娃，继续看registerReceiverInternal

```java
private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
                                        IntentFilter filter, String broadcastPermission,
                                        Handler scheduler, Context context, int flags) {
    IIntentReceiver rd = null;
    if (receiver != null) {
        if (mPackageInfo != null && context != null) {
            //1 对receiver包装成IIntentReceiver
            rd = mPackageInfo.getReceiverDispatcher(
                receiver, context, scheduler,
                mMainThread.getInstrumentation(), true);
        } else {
            rd = new LoadedApk.ReceiverDispatcher(
                receiver, context, scheduler, null, true).getIIntentReceiver();
        }
    }
    //...
    //2 调用AMS.registerReceiverWithFeature
    final Intent intent = ActivityManager.getService().registerReceiverWithFeature(
        mMainThread.getApplicationThread(), mBasePackageName, getAttributionTag(),
        AppOpsManager.toReceiverId(receiver), rd, filter, broadcastPermission, userId,
        flags);
}
	return intent;
}
```

这块跟bindservice非常相似，都是ContextImpl里绑定服务对象或者广播接受者对象。把用户注册时传过来的BroadcastReceiver包装到Binder里，然后通过binder通信调用到AMS，把binder传过去，AMS记录绑定信息后等有广播事件后查询到此监听，再回调此监听者的回调方法。

> frameworks/base/core/java/android/app/LoadedApk.java

```java
public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,Context context...) {
    synchronized (mReceivers) {
        LoadedApk.ReceiverDispatcher rd = null;
        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
        //1 先从mReceivers缓存拿
        map = mReceivers.get(context);
        if (map != null) {
            rd = map.get(r);
        }
        //2 拿不到就new个
        if (rd == null) {
            rd = new ReceiverDispatcher(r, context, handler,
                                        instrumentation, registered);
            if (map == null) {
                map = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                mReceivers.put(context, map);
            }
            //3 再存到缓存
            map.put(r, rd);
        }
        return rd.getIIntentReceiver();
    }
}
```

跟getServiceDispatcher类似，先看缓存是否有，有的话就从缓存拿，拿不到就new一个存缓存里。

> frameworks/base/core/java/android/app/LoadedApk.java

```java
static final class ReceiverDispatcher {
    //1 InnerReceiver是ReceiverDispatcher的静态内部类，本质是个binder
    final static class InnerReceiver extends IIntentReceiver.Stub {

    }
    //2 ReceiverDispatcher的构造函数new了InnerReceiver，把自己传了进去
    ReceiverDispatcher(BroadcastReceiver receiver, Context context,
                       Handler activityThread, Instrumentation instrumentation,
                       boolean registered) {
        mIIntentReceiver = new InnerReceiver(this, !registered);
        mReceiver = receiver;
        mContext = context;
        //...
    }
    //3 通过getIIntentReceiver拿到内部类mIIntentReceiver
    IIntentReceiver getIIntentReceiver() {
        return mIIntentReceiver;
    }
}
```

同ServiceDispatcher

+ 注释1 InnerReceiver是ReceiverDispatcher的静态内部类，本质是个binder，
+ 注释2在ReceiverDispatcher的构造函数new了InnerReceiver
+ 并通过注释3的getIIntentReceiver方法拿到内部类mIIntentReceiver，也就是new的InnerReceiver对象。

继续看AMS.registerReceiverWithFeature是怎么注册InnerReceiver的

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
private static final int MAX_RECEIVERS_ALLOWED_PER_APP = 1000;
@Deprecated
//registerReceiver方法已经废弃
public Intent registerReceiver(IApplicationThread caller...) {
    return registerReceiverWithFeature(caller, callerPackage...);
}

public Intent registerReceiverWithFeature(...String callerFeatureId, String receiverId, IIntentReceiver receiver, IntentFilter filter...) {
    ProcessRecord callerApp = null;
    //调用的客户端的进程
    callerApp = getRecordForAppLOSP(caller);
    
	//...
    //1 首次注册，肯定不在AMS的mRegisteredReceivers缓存里
    ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
    if (rl == null) {
        rl = new ReceiverList(this, callerApp, callingPid, callingUid,userId, receiver);
        if (rl.app != null) {
            //2 调用进程已注册的广播数量，大于1000直接报错，不大于1000就放进缓存
            final int totalReceiversForApp = rl.app.mReceivers.numberOfReceivers();
            if (totalReceiversForApp >= MAX_RECEIVERS_ALLOWED_PER_APP) {
                throw new IllegalStateException("Too many receivers, total of " );
            }
            rl.app.mReceivers.addReceiver(rl);
        }
        mRegisteredReceivers.put(receiver.asBinder(), rl);
    } 
    
    //3 把IntentFilter包装成BroadcastFilter，参数传了rl
    BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage, callerFeatureId,receiverId, permission...);
    if (rl.containsFilter(filter)) {
        Slog.w(TAG, "...already registered for pid... ");
    } else {
        //4 把包装好的BroadcastFilter存进rl中
        rl.add(bf);
        //5 把bf存进mReceiverResolver
        mReceiverResolver.addFilter(getPackageManagerInternal().snapshot(), bf);
    }
}
```

这里是注册广播的核心处理的地方。

+ 注释1先从mRegisteredReceivers拿以receiver.asBinder()为key的ReceiverList对象，首次注册肯定不为空就new个ReceiverList对象，传进了Receiver，此时ReceiverList列表为空。
+ 注释2处判断调用进程已注册的广播数量，如果大于1000就直接报错，否则就存进mRegisteredReceivers缓存。
+ 注释3处开始处理IntentFilter，先把IntentFilter（里面存了我们传的action）和ReceiverList（注释1处已经传进了receiver）包装成BroadcastFilter，这样就把filter和receiver关联起来了，
+ 注释4处把关联好的BroadcastFilter对象加到ReceiverList列表中（注释1处只是new，并没有add个对象进去），这样mRegisteredReceivers缓存的ReceiverList就添加了BroadcastFilter对象。此时BroadcastFilter就已经包括了完整的关联信息，
+ 注释5处又把BroadcastFilter放进了mReceiverResolver，这个在AMS分发广播时会用到。

这里注意下这几个对象的关系：mRegisteredReceivers缓存的key是receivier的binder对象，value是ReceiverList列表对象，ReceiverList列表里存的是对IntentFilter包装后BroadcastFilter对象。

到这里，广播的注册就结束了，也就是把客户端传的receiver和IntentFilter存到了AMS的mReceiverResolver和mRegisteredReceivers中。

#### 广播发送流程

> frameworks/base/core/java/android/content/ContextWrapper.java

```java
@Override
public void sendBroadcast(Intent intent) {
    mBase.sendBroadcast(intent);
}
```

> frameworks/base/core/java/android/app/ContextImpl.java

```java
public void sendBroadcast(Intent intent) {
    ActivityManager.getService().broadcastIntentWithFeature(
        mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
        null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
        null, AppOpsManager.OP_NONE, null, false, false, getUserId());
}
```

AMS.broadcastIntentWithFeature

> frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

```java
public final int broadcastIntentWithFeature(IApplicationThread caller...) {
    return broadcastIntentLocked(callerApp...);
}
final int broadcastIntentLocked(ProcessRecord callerApp...) {
    return broadcastIntentLocked(callerApp...);
}
```

套娃式调用到broadcastIntentLocked

```java
final int broadcastIntentLocked(ProcessRecord callerApp, String callerPackage...){
    //前500行都是校验
    //...粘性广播 不考虑
    if(sticky){}
    //...
    List receivers = null;
    List<BroadcastFilter> registeredReceivers = null;
    //1 过滤下静态广播
    if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
        receivers = collectReceiverComponents(
            intent, resolvedType, callingUid, users, broadcastAllowList);
    }
    //2 过滤下动态广播
    if (intent.getComponent() == null) {
        registeredReceivers = mReceiverResolver.queryIntent(snapshot, intent,resolvedType, false /*defaultOnly*/, userId);
    }
    
    int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
    //3非有序广播并且查到的静态广播size大于0
    if (!ordered && NR > 0) {
        //4 根据intent的flag去找合适的队列
        final BroadcastQueue queue = broadcastQueueForIntent(intent);
        //5 封装BroadcastRecord消息对象准备入队列处理
        BroadcastRecord r = new BroadcastRecord(queue, intent,registeredReceivers,...);
        final boolean replaced = replacePending && (queue.replaceParallelBroadcastLocked(r) != null);
        //6是否有还未处理的同样的广播，没有的话就入队，有的话就跳过
        if (!replaced) {
            //7 入队
            queue.enqueueParallelBroadcastLocked(r);
            //8 处理队列消息
            queue.scheduleBroadcastsLocked();
        }
        //9 清空本地变量
        registeredReceivers = null;
        NR = 0;
    }
    //10 静态广播的处理
    if ((receivers != null && receivers.size() > 0)|| resultTo != null) {
        BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue...);
        queue.enqueueOrderedBroadcastLocked(r);
        queue.scheduleBroadcastsLocked();
    }
    //...
}
```

这块是发送广播的核心。

+ 注释1通过intent去PMS通过清单文件寻找注册的静态广播，能找到就放进receivers变量。注册广播的流程中我们知道已经把信息存进了mReceiverResolver中，
+ 注释2通过mReceiverResolver去查找所有注册指定条件的动态广播，并放到registeredReceivers变量，
+ 注释3根据查到的registeredReceivers变量大于0就走动态广播的发送流程。
+ 注释4根据intent的flag获取个合适的前台或者后台处理队列。
+ 注释5封装个BroadcastRecord为下一步入队处理消息做准备。这里可以看到广播的发送不是立即就会被接受者接收的，而是放到了调度队列，并通过handler发送消息给接受者。
+ 注释6处处理假如已经入队但还没来得及发送给接受者的情况，这时候新的消息就不会再入队。
+ 注释7假如没有新的替换的消息就把BroadcastRecord入队，
+ 注释8处理队列消息，
+ 注释9清空要处理的动态广播的变量
+ 注释10处处理静态广播的列表不为空，跟动态广播一个套路

我们继续看8处的消息队列处理。

> frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java

```java
public void scheduleBroadcastsLocked() {
    if (mBroadcastsScheduled) {
        return;
    }
    mHandler.sendMessage(mHandler.obtainMessage(BROADCAST_INTENT_MSG, this));
    mBroadcastsScheduled = true;
}
public void enqueueParallelBroadcastLocked(BroadcastRecord r) {
    mParallelBroadcasts.add(r);
}

private final class BroadcastHandler extends Handler {
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case BROADCAST_INTENT_MSG: {
                processNextBroadcast(true);
                break;
            }}}}
private void processNextBroadcast(boolean fromMsg) {
    processNextBroadcastLocked(fromMsg, false);
}
final void processNextBroadcastLocked(boolean fromMsg, boolean skipOomAdj) {
    BroadcastRecord r;
    //1 mParallelBroadcasts有待处理的广播
    while (mParallelBroadcasts.size() > 0) {
        //2 从队列拿出来一个
        r = mParallelBroadcasts.remove(0);
        final int N = r.receivers.size();
        //3 r.receivers里存了刚查到的所有的广播接受者
        for (int i=0; i<N; i++) {
            Object target = r.receivers.get(i);
            //4 分发给每个接受者
            deliverToRegisteredReceiverLocked(r,(BroadcastFilter) target,false, i);
        }
        //...
    }
   //...
}
```

队列的处理比较简单，从刚才入队的消息中一个个获取并调用deliverToRegisteredReceiverLocked去一个个分发给指定的接收者

```java
private void deliverToRegisteredReceiverLocked(BroadcastRecord r...) {
	//...
    performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                         new Intent(r.intent), r.resultCode, r.resultData,
                         r.resultExtras, r.ordered, r.initialSticky, r.userId,
                         filter.receiverList.uid, r.callingUid,
                         r.dispatchTime - r.enqueueTime,
                         r.receiverTime - r.dispatchTime);
    //...
}
void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver...){
    //1 接收者进程不为空
    if (app != null) {
        final IApplicationThread thread = app.getThread();
        //3 进程在，正常情况下IApplicationThread也在，否则就报错
        if (thread != null) {
            thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                                              data, extras, ordered, sticky, sendingUser,
                                              app.mState.getReportedProcState());
        } else {
            throw new RemoteException("app.thread must not be null");
        }
    } else {
        //2 接受者进程为空
        receiver.performReceive(intent, resultCode, data, extras, ordered,sticky, sendingUser);
    }
}
```

performReceiveLocked中先判断广播接收者的进程是否存在，如果存在，就回调IApplicationThread.scheduleRegisteredReceiver方法；如果进程不存在，就直接回调

receiver.performReceive，此时说明是静态广播。

到ActivityThread里

> frameworks/base/core/java/android/app/ActivityThread.java

```java
public void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent,
                                       int resultCode, String dataStr, Bundle extras, boolean ordered,
                                       boolean sticky, int sendingUser, int processState) throws RemoteException {
    updateProcessState(processState, false);
    receiver.performReceive(intent, resultCode, dataStr, extras, ordered,
                            sticky, sendingUser);
}
```

一样调用到receiver.performReceive。

继续看LoadedApk的内部类ReceiverDispatcher的静态内部类InnerReceiver

>frameworks/base/core/java/android/app/LoadedApk.java

```java
public final class LoadedApk { 
    static final class ReceiverDispatcher {
        final BroadcastReceiver mReceiver;
        //上面已经看过，注册广播时ReceiverDispatcher持有了BroadcastReceiver
        ReceiverDispatcher(BroadcastReceiver receiver, Context context...) {
            mIIntentReceiver = new InnerReceiver(this, !registered);
            mReceiver = receiver;
        }
        final static class InnerReceiver extends IIntentReceiver.Stub {
            final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
            InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
                //InnerReceiver的构造函数持有了外部类的弱引用
                mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
            }
            @Override
            public void performReceive(Intent intent, int resultCode, String data,
                                       Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                final LoadedApk.ReceiverDispatcher rd;
                if (intent == null) {
                    rd = null;
                } else {
                    //1 从InnerReceiver持有外部类ReceiverDispatcher的弱引用中查
                    rd = mDispatcher.get();
                }
                if (rd != null) {
                    //2 能找到未销毁的receiver，就执行ReceiverDispatcher.performReceive
                    rd.performReceive(intent, resultCode, data, extras, ordered, sticky, sendingUser);
                } else {
                    //3 否则AMS.finishReceiver去关闭广播注册
                    IActivityManager mgr = ActivityManager.getService();
                    mgr.finishReceiver(this, resultCode, data, extras, false, intent.getFlags());
                }
            }
        }
    }
    //ReceiverDispatcher.performReceive
    public void performReceive(Intent intent, int resultCode, String data,
                               Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
        final Args args = new Args(intent, resultCode, data, extras, ordered,sticky, sendingUser);
        //4 通过hander往主线程post个runnable
        if (intent == null || !mActivityThread.post(args.getRunnable())) {
            if (mRegistered && ordered) {
                IActivityManager mgr = ActivityManager.getService();
                args.sendFinished(mgr);
            }
        }
    }

    final class Args extends BroadcastReceiver.PendingResult {
          public final Runnable getRunnable() {
               return () -> {
                   //5 先拿外部类的BroadcastReceiver
                   final BroadcastReceiver receiver = mReceiver;
                   if (receiver == null || intent == null || mForgotten) {
                       return;
                   }
                   receiver.setPendingResult(this);
                   //6 回调BroadcastReceiver.onReceive
                   receiver.onReceive(mContext, intent);
               }
          	}
    	}
    }
} 
```

这个回调过程也很简单

+ 注释1从InnerReceiver持有外部类ReceiverDispatcher的弱引用中查
+ 注释2能找到的话就调ReceiverDispatcher.performReceive
+ 注释3找不到说明ReceiverDispatcher已经被回收了，通知ams去关闭此receiver注册
+ 注释4在ReceiverDispatcher.performReceive中通过hander往主线程post个runnable，说明后面的runnable是运行在主线程
+ 注释5在runnable中先去获取外部类ReceiverDispatcher持有的BroadcastReceiver
+ 注释6去回调BroadcastReceiver.onReceive，即在主线程回调了我们定义的BroadcastReceiver的onReceive方法

这样发送广播的流程就结束了。

#### 总结

+ 广播本质还是用了binder机制，只不是把binder传给AMS做管理。
+ 广播的注册实际上是把自定义的BroadcastReceiver存到ReceiverDispatcher，把ReceiverDispatcher的内部类InnerReceiver保存到AMS，InnerReceiver本质是个binder，AMS把InnerReceiver记录到缓存mReceiverResolver和mRegisteredReceivers中
+ 广播的发送通过AMS先从mReceiverResolver缓存查询指定BroadcastFilter，封装成BroadcastRecord然后入队等待队列一个个执行，如果还有老的同样的广播没执行完，就不添加新的广播。然后分发给指定的进程，执行ActivityThread.scheduleRegisteredReceiver,这里会回调InnerReceiver.performReceive,InnerReceiver又执行外部类ReceiverDispatcher的performReceive，其往主线程hander post了个runnable，里面回调BroadcastReceiver.onReceive方法。
+ 由于发送的时候广播是按队列执行，所以广播的发送不是立即就被接收者接收，也可能发送了3个同样的广播，由于队列执行较慢，只接收一个广播的情况。

