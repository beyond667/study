### Activity启动流程

#### 背景

启动流程是Android的基础，牵涉的东西很多，包括AMS,WMS,Window，绘制流程，Token机制等。先以首次启动为例。

#### 具体流程

##### 一 客户端binder通信调用ATMS.startActivity

点击桌面图标，执行Activity.startActivity -> Activity.startActivityForResult ->Instrumentation.execStartActivity->ATMS.startActivity

Activity.class

```java
@Override
public void startActivity(Intent intent) {
    this.startActivity(intent, null);
}
 @Override
public void startActivity(Intent intent, @Nullable Bundle options) {
	// ...
    if (options != null) {
        startActivityForResult(intent, -1, options);
    } else {
        startActivityForResult(intent, -1);
    }
}
public void startActivityForResult(@RequiresPermission Intent intent, int requestCode) {
    startActivityForResult(intent, requestCode, null);
}
public void startActivityForResult(@RequiresPermission Intent intent, int requestCode, @Nullable Bundle options) {
    Instrumentation.ActivityResult ar = mInstrumentation.execStartActivity(
        this, mMainThread.getApplicationThread(), mToken, this,intent, requestCode, options);
}
```

Instrumentation.java

```java
public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token, Activity target, Intent intent, int requestCode, Bundle options) {
    //...
    int result = ActivityTaskManager.getService().startActivity(
        whoThread,who.getOpPackageName(),who.getAttributionTag(),intent,intent.resolveTypeIfNeeded(who.getContentResolver()), token, target != null ? target.mEmbeddedID : null, requestCode, 0, null, options);
    // ...
}
```

##### 二 AMS根据条件创建进程

ActivityTaskManagerService.java(ATMS)

```JAVA
public final int startActivity(IApplicationThread caller, String callingPackage,
                               String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
                               String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
                               Bundle bOptions) {
    return startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
                               resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions,
                               UserHandle.getCallingUserId());
}
@Override
public int startActivityAsUser(IApplicationThread caller, String callingPackage,
                               String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
                               String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
                               Bundle bOptions, int userId) {
    return startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
                               resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId,
                               true /*validateIncomingUser*/);
}
private int startActivityAsUser(IApplicationThread caller, String callingPackage,
                                @Nullable String callingFeatureId, Intent intent, String resolvedType,
                                IBinder resultTo, String resultWho, int requestCode, int startFlags,
                                ProfilerInfo profilerInfo, Bundle bOptions, int userId, boolean validateIncomingUser) {
    // TODO: Switch to user app stacks here.
    return getActivityStartController().obtainStarter(intent, "startActivityAsUser")
        .setCaller(caller)
        .setCallingPackage(callingPackage)
        .setCallingFeatureId(callingFeatureId)
        .setResolvedType(resolvedType)
        .setResultTo(resultTo)
        .setResultWho(resultWho)
        .setRequestCode(requestCode)
        .setStartFlags(startFlags)
        .setProfilerInfo(profilerInfo)
        .setActivityOptions(bOptions)
        .setUserId(userId)
        .execute();
}
```

通过obtainStarter拿到ActivityStarter，执行execute方法  

ActivityStarter.java

```java
int execute() {
    //...
    res = executeRequest(mRequest);
    //...
}
private int executeRequest(Request request) {
    //...400行代码 省略
    final ActivityRecord r = new ActivityRecord.Builder(mService)
        .setCaller(callerApp)
        .setLaunchedFromPid(callingPid)
        .setLaunchedFromUid(callingUid)
        .setLaunchedFromPackage(callingPackage)
        .setLaunchedFromFeature(callingFeatureId)
        .setIntent(intent)
        .setResolvedType(resolvedType)
        .setActivityInfo(aInfo)
        .setConfiguration(mService.getGlobalConfiguration())
        .setResultTo(resultRecord)
        .setResultWho(resultWho)
        .setRequestCode(requestCode)
        .setComponentSpecified(request.componentSpecified)
        .setRootVoiceInteraction(voiceSession != null)
        .setActivityOptions(checkedOptions)
        .setSourceRecord(sourceRecord)
        .build();
    mLastStartActivityRecord = r;
    //...
    mLastStartActivityResult = startActivityUnchecked(r, sourceRecord, voiceSession,
                                                      request.voiceInteractor, startFlags, true /* doResume */, checkedOptions,
                                                      inTask, inTaskFragment, restrictedBgActivity, intentGrants);
    //...
}
private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
                                   IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                   int startFlags, boolean doResume, ActivityOptions options, Task inTask,
                                   TaskFragment inTaskFragment, boolean restrictedBgActivity,
                                   NeededUriGrants intentGrants) {
    //...
    result = startActivityInner(r, sourceRecord, voiceSession, voiceInteractor,
                                startFlags, doResume, options, inTask, inTaskFragment, restrictedBgActivity,
                                intentGrants);
    //...
}
int startActivityInner(final ActivityRecord r, ActivityRecord sourceRecord,
                       IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                       int startFlags, boolean doResume, ActivityOptions options, Task inTask,
                       TaskFragment inTaskFragment, boolean restrictedBgActivity,
                       NeededUriGrants intentGrants) {
    //... 这里做了很多task的处理
    // Compute if there is an existing task that should be used for.
    final Task targetTask = reusedTask != null ? reusedTask : computeTargetTask();
    final boolean newTask = targetTask == null;
    //...
    if (newTask) {
        //...
        setNewTask(taskToAffiliate);
    } else if (mAddingToTask) {
        addOrReparentStartingActivity(targetTask, "adding to task");
    }

    //...
    mRootWindowContainer.resumeFocusedTasksTopActivities(
        mTargetRootTask, mStartActivity, mOptions, mTransientLaunch);
    //...
}

```

这里说明下，ActivityRecord构建者模式创建ActivityRecord时，会创建Token  

```java
public final class ActivityRecord extends WindowToken implements WindowManagerService.AppFreezeListener {    
    private ActivityRecord(ActivityTaskManagerService _service, WindowProcessController _caller,
                           int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage,
                           @Nullable String _launchedFromFeature, Intent _intent, String _resolvedType,
                           ActivityInfo aInfo, Configuration _configuration, ActivityRecord _resultTo,
                           String _resultWho, int _reqCode, boolean _componentSpecified,
                           boolean _rootVoiceInteraction, ActivityTaskSupervisor supervisor,
                           ActivityOptions options, ActivityRecord sourceRecord, PersistableBundle persistentState,
                           TaskDescription _taskDescription, long _createTime) {
        super(_service.mWindowManager, new Token(_intent).asBinder(), TYPE_APPLICATION, true,
              null /* displayContent */, false /* ownerCanManageAppTokens */);

        mAtmService = _service;
        appToken = (Token) token;
        //...
    }
}
class WindowToken extends WindowContainer<WindowState> {
    protected WindowToken(WindowManagerService service, IBinder _token, int type,
                          boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens) {
        this(service, _token, type, persistOnEmpty, dc, ownerCanManageAppTokens,
             false /* roundedCornerOverlay */, false /* fromClientToken */, null /* options */);
    }
    protected WindowToken(WindowManagerService service, IBinder _token, int type,
                          boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens,
                          boolean roundedCornerOverlay, boolean fromClientToken, @Nullable Bundle options) {
        super(service);
        token = _token;
        //...
    }
}
```

ActivityRecord继承WindowToken，所以这个appToken就是new Token(_intent).asBinder()这个binder

```java
public final class ActivityRecord extends WindowToken implements WindowManagerService.AppFreezeListener {    

     //Android12上继承IApplicationToken.Stub
    static class Token extends IApplicationToken.Stub {
        private WeakReference<ActivityRecord> weakActivity;

        private void attach(ActivityRecord activity) {
            if (weakActivity != null) {
                throw new IllegalStateException("Already attached..." + this);
            }
            weakActivity = new WeakReference<>(activity);
        }
    }

    //Android13上直接就是继承Binder
    private static class Token extends Binder {
        @NonNull WeakReference<ActivityRecord> mActivityRef;
        @Override
        public String toString() {
            return "Token{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + mActivityRef.get() + "}";
        }
    }

}
```

可以看到Token是ActivityRecord的静态内部类，这个Binder作用就是内部持有了个弱引用ActivityRecord，可以理解成Token是对ActivityRecord做了标识，因为这个在SystemServer进程里创建的Token后面会传递给客户端某个Activity做绑定，用来标识这个具体的Activity，后面再传给WMS，WMS也是基于这个Token来确定这个ActivityRecord。这就是Token的本质，本质是个binder实体，内部弱引用的方式持有ActivityRecord，即标识了ActivityRecord，传递给客户端后客户端绑定具体的Activity，再传给wms做窗口绘制。AMS,WMS就通过这个token来确定是哪个具体的Activity。  

继续mRootWindowContainer.resumeFocusedTasksTopActivities

```java
boolean resumeFocusedTasksTopActivities(
    Task targetRootTask, ActivityRecord target, ActivityOptions targetOptions,
    boolean deferPause) {
	// ...
    result = targetRootTask.resumeTopActivityUncheckedLocked(target, targetOptions,
                                                             deferPause);
	// ...
}
```

Task.java

```java
boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options,
                                         boolean deferPause) {
    // ...
    if (isFocusableAndVisible()) {
        someActivityResumed = resumeTopActivityInnerLocked(prev, options, deferPause);
    }
    // ...
}
private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options,
                                             boolean deferPause) {
    // ...
    final TaskFragment topFragment = topActivity.getTaskFragment();
    resumed[0] = topFragment.resumeTopActivity(prev, options, deferPause);
    // ...
    return resumed[0];
}
```

TaskFragment.java

```java
final boolean resumeTopActivity(ActivityRecord prev, ActivityOptions options,
                                boolean deferPause) {
    // ... 
    if (next.attachedToProcess()) {
        // 已经有进程
    }else {
        // 进程还没创建
        mTaskSupervisor.startSpecificActivity(next, true, true);
    }
    // ...
}
```

ActivityTaskSupervisor.java

```java
void startSpecificActivity(ActivityRecord r, boolean andResume, boolean checkConfig) {
    // ... 
    mService.startProcessAsync(r, knownToBeDead, isTop,
                               isTop ? HostingRecord.HOSTING_TYPE_TOP_ACTIVITY
                               : HostingRecord.HOSTING_TYPE_ACTIVITY);
}
```

##### 启动新的进程

mService即ATMS，后面就是启动新进程的流程

```java
void startProcessAsync(ActivityRecord activity, boolean knownToBeDead, boolean isTop,String hostingType) {
    final Message m = PooledLambda.obtainMessage(ActivityManagerInternal::startProcess,
          mAmInternal, activity.processName, activity.info.applicationInfo, knownToBeDead,
          isTop, hostingType, activity.intent.getComponent());
}
```

调用到AMS.startProcess

```java
public void startProcess(String processName, ApplicationInfo info, boolean knownToBeDead,
                         boolean isTop, String hostingType, ComponentName hostingName) {
    startProcessLocked(processName, info, knownToBeDead, 0 /* intentFlags */,
                       new HostingRecord(hostingType, hostingName, isTop),
                       ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE, false /* allowWhileBooting */,
                       false /* isolated */);

}

final ProcessRecord startProcessLocked(String processName,
                                       ApplicationInfo info, boolean knownToBeDead, int intentFlags,
                                       HostingRecord hostingRecord, int zygotePolicyFlags, boolean allowWhileBooting,
                                       boolean isolated) {
    return mProcessList.startProcessLocked(processName, info, knownToBeDead, intentFlags,
                                           hostingRecord, zygotePolicyFlags, allowWhileBooting, isolated, 0 /* isolatedUid */,
                                           false /* isSdkSandbox */, 0 /* sdkSandboxClientAppUid */,
                                           null /* sdkSandboxClientAppPackage */,
                                           null /* ABI override */, null /* entryPoint */,
                                           null /* entryPointArgs */, null /* crashHandler */);
}
```

到ProcessList.startProcessLocked

```java
ProcessRecord startProcessLocked(..)
{       // ... 
    final boolean success =
        startProcessLocked(app, hostingRecord, zygotePolicyFlags, abiOverride);
}

boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
                           int zygotePolicyFlags, String abiOverride) {
    return startProcessLocked(app, hostingRecord, zygotePolicyFlags,
                              false /* disableHiddenApiChecks */, false /* disableTestApiChecks */,
                              abiOverride);
}
boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
                           int zygotePolicyFlags, boolean disableHiddenApiChecks, boolean disableTestApiChecks,
                           String abiOverride) {
    // ...   这里指定fork后的回调类为ActivityThread
    final String entryPoint = "android.app.ActivityThread";
    return startProcessLocked(hostingRecord, entryPoint, app, uid, gids,
                              runtimeFlags, zygotePolicyFlags, mountExternal, seInfo, requiredAbi,
                              instructionSet, invokeWith, startUptime, startElapsedTime);
}
boolean startProcessLocked(...) {
        // ... 
    final Process.ProcessStartResult startResult = 
        startProcess(hostingRecord,entryPoint, app,uid, gids, runtimeFlags, zygotePolicyFlags, mountExternal,seInfo,
                     requiredAbi, instructionSet, invokeWith, startUptime);
}
private Process.ProcessStartResult startProcess(...) {
    // ... 
    if (hostingRecord.usesWebviewZygote()) {
        startResult = startWebView(...);
    } else if (hostingRecord.usesAppZygote()) {
        startResult = appZygote.getProcess().start(...);
    } else {
        startResult = Process.start(...);
    }
}
```

都会调用到ZygoteProcess.startViaZygote

```java
private Process.ProcessStartResult startViaZygote(...){
    // ... 
    return zygoteSendArgsAndGetResult(openZygoteSocketIfNeeded(abi),
                                      zygotePolicyFlags,
                                      argsForZygote);
}
private Process.ProcessStartResult zygoteSendArgsAndGetResult(...) {
    // ... 
    return attemptUsapSendArgsAndGetResult(zygoteState, msgStr);
}
private Process.ProcessStartResult attemptUsapSendArgsAndGetResult(ZygoteState zygoteState, String msgStr){
    final BufferedWriter usapWriter =
        new BufferedWriter(
        new OutputStreamWriter(usapSessionSocket.getOutputStream()),
        Zygote.SOCKET_BUFFER_SIZE);
    final DataInputStream usapReader =
        new DataInputStream(usapSessionSocket.getInputStream());

    usapWriter.write(msgStr);
    usapWriter.flush();

    Process.ProcessStartResult result = new Process.ProcessStartResult();
    result.pid = usapReader.readInt();
    result.usingWrapper = false;

    if (result.pid >= 0) {
        return result;
    } else {
        throw new ZygoteStartFailedEx("USAP specialization failed");
    }
}
}
```

最终通过Socket通信通知Zygote进程fork出指定进程，并回调ActivityThread的main方法

##### 新应用启动

所有应用的入口都是ActivityThread.main（）

```java
public final class ActivityThread extends ClientTransactionHandler{
    public static void main(String[] args) {
    	// ... 准备Looper
        Looper.prepareMainLooper();
        //new了个ActivityThread，并执行attach方法
        ActivityThread thread = new ActivityThread();
        thread.attach(false, startSeq);
        Looper.loop();
    } 
    final ApplicationThread mAppThread = new ApplicationThread();
    private void attach(boolean system, long startSeq) {
        final IActivityManager mgr = ActivityManager.getService();
        mgr.attachApplication(mAppThread, startSeq);
    }
    private class ApplicationThread extends IApplicationThread.Stub {
        public final void bindApplication(...) {
			//...
            sendMessage(H.BIND_APPLICATION, data);
        }
         public final void scheduleXxx{
            sendMessage(H.xxx, null);
        }
    }
}
```

attach后调用到AMS.attachApplication，并传了ApplicationThread，ApplicationThread本质上也是个Binder,这里相当于应用端作为服务端，给AMS传个ApplicationThread的代理，后面AMS就通过调用ApplicationThread的方法来管理具体的Activity。这里先调用attachApplication通知AMS去绑定新创建的应用。

```java
public final void attachApplication(IApplicationThread thread, long startSeq) {
    attachApplicationLocked(thread, callingPid, callingUid, startSeq);
}
private boolean attachApplicationLocked(...) {
    //...
    //1 这里对ApplicationThread绑定死亡监听，应用如果死的时候，AMS会去清理该应用相关的数据
    AppDeathRecipient adr = new AppDeathRecipient(
        app, pid, thread);
    thread.asBinder().linkToDeath(adr, 0);
    app.setDeathRecipient(adr);
    //...
    
    //2 调用应用进程的ApplicationThread.bindApplication
      thread.bindApplication(...);
    //...
    //3 ATMS.attachApplication
     didSomething = mAtmInternal.attachApplication(app.getWindowProcessController());
	//...
    return true;
}
```

AMS.attachApplication时个人比较关注这几件事：

1 先给新应用绑定死亡监听，应用死掉时去清理相关资源

2 通知客户端已经绑定好了

3 调用ATMS.attachApplication准备启动应用  

先看ApplicationThread.bindApplication，上面已经粘贴了，发了sendMessage(H.BIND_APPLICATION, data);到主线程的hander

```java
class H extends Handler {
    public void handleMessage(Message msg) {
        if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
        switch (msg.what) {
            case BIND_APPLICATION:
                AppBindData data = (AppBindData)msg.obj;
                handleBindApplication(data);
                break;
        }
    }
}
private void handleBindApplication(AppBindData data) {
    //...
    mInstrumentation = new Instrumentation();
    mInstrumentation.onCreate(data.instrumentationArgs);
    mInstrumentation.callApplicationOnCreate(app);
    //...
}

```

handleBindApplication这里new了Instrumentation，并调用其callApplicationOnCreate，即调用Application.onCreate，到这里我们应用开发的第一个入口就调用了。

```java
public class Instrumentation {
    public void callApplicationOnCreate(Application app) {
        app.onCreate();
    }
}
```

再继续看ATMS.attachApplication

```java
public boolean attachApplication(WindowProcessController wpc) throws RemoteException {
    //...
    return mRootWindowContainer.attachApplication(wpc);
}
```

到RootWindowContainer.java

```java
boolean attachApplication(WindowProcessController app) throws RemoteException {
    //...
    final PooledFunction c = PooledLambda.obtainFunction(
        RootWindowContainer::startActivityForAttachedApplicationIfNeeded, this,
        PooledLambda.__(ActivityRecord.class), app,
        rootTask.topRunningActivity());
}
private boolean startActivityForAttachedApplicationIfNeeded(ActivityRecord r,
                                                            WindowProcessController app, ActivityRecord top) {
    //...
    if (mTaskSupervisor.realStartActivityLocked(r, app,top == r && r.isFocusable() /*andResume*/, true /*checkConfig*/)) {
        mTmpBoolean = true;
    }
    //...
}
```

到了关键方法ActivityTaskSupervisor.realStartActivityLocked，到这里才是真正要启动应用

```java
boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
                                boolean andResume, boolean checkConfig) {
    //...
    //构建事务回调LaunchActivityItem 这里可以看到把ActivityRecord的token传进了ClientTransaction
    final ClientTransaction clientTransaction = ClientTransaction.obtain(proc.getThread(), r.appToken);
    clientTransaction.addCallback(LaunchActivityItem.obtain(...));

    // Set desired final state.
    final ActivityLifecycleItem lifecycleItem;
    if (andResume) {
        lifecycleItem = ResumeActivityItem.obtain(isTransitionForward);
    } else {
        lifecycleItem = PauseActivityItem.obtain();
    }
    clientTransaction.setLifecycleStateRequest(lifecycleItem);

    // Schedule transaction. 执行事务
    mService.getLifecycleManager().scheduleTransaction(clientTransaction);
    //...
}
```

这里构建了事务回调LaunchActivityItem，如果需要可见，指定最终生命周期状态ResumeActivityItem，否则就PauseActivityItem。

```java
class ClientLifecycleManager {
    void scheduleTransaction(ClientTransaction transaction) throws RemoteException {
        final IApplicationThread client = transaction.getClient();
        transaction.schedule();
    }
}
public class ClientTransaction implements Parcelable, ObjectPoolItem {
    private IApplicationThread mClient;
    public void schedule() throws RemoteException {
        mClient.scheduleTransaction(this);
    }
}
```

执行事务逻辑比较简单，最终执行ApplicationThread.scheduleTransaction，往主线程发送EXECUTE_TRANSACTION消息

```java
case EXECUTE_TRANSACTION:
final ClientTransaction transaction = (ClientTransaction) msg.obj;
mTransactionExecutor.execute(transaction);

public class TransactionExecutor {
    public void execute(ClientTransaction transaction) {
        //...
        //这里看到执行完callback的execute，继续执行下个生命周期状态的execute
        executeCallbacks(transaction);
        executeLifecycleState(transaction);
    }
    public void executeCallbacks(ClientTransaction transaction) {
         //...
        final int size = callbacks.size();
        for (int i = 0; i < size; ++i) {
            final ClientTransactionItem item = callbacks.get(i);
            //即LaunchActivityItem.execute
            item.execute(mTransactionHandler, token, mPendingActions);
        }
    }
    private void executeLifecycleState(ClientTransaction transaction) {
        final ActivityLifecycleItem lifecycleItem = transaction.getLifecycleStateRequest();
        //即ResumeActivityItem.execute
        lifecycleItem.execute(mTransactionHandler, token, mPendingActions);
    }
}
LaunchActivityItem.java
public class LaunchActivityItem extends ClientTransactionItem {
    public void execute(ClientTransactionHandler client, IBinder token, PendingTransactionActions pendingActions) {
        ActivityClientRecord r = new ActivityClientRecord(token, mIntent...);
        client.handleLaunchActivity(r, pendingActions, null /* customIntent */);
    }
}
ResumeActivityItem.java
public class ResumeActivityItem extends ActivityLifecycleItem {
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,PendingTransactionActions pendingActions) {
        client.handleResumeActivity(r, true, mIsForward,"RESUME_ACTIVITY");
    }
}
```

这里看到拿到callback并执行其execute，即先执行LaunchActivityItem.execute，之后执行ResumeActivityItem.execute，这两个方法分别执行了ActivityThread的handleLaunchActivity和handleResumeActivity  

先看ActivityThread.handleLaunchActivity

```java
public Activity handleLaunchActivity(ActivityClientRecord r,
                                     PendingTransactionActions pendingActions, Intent customIntent) {
    //...
    final Activity a = performLaunchActivity(r, customIntent);
    //...
}
private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    //... 
    // Activity通过classload.load类加载的方式加载出来
    Activity activity = null;
    java.lang.ClassLoader cl = appContext.getClassLoader();
    activity = mInstrumentation.newActivity(cl, component.getClassName(), r.intent);

    //...
    //调用activity.attach
  	activity.attach(appContext, this, getInstrumentation(), r.token,
                        r.ident, app, r.intent, r.activityInfo, title, r.parent,
                        r.embeddedID, r.lastNonConfigurationInstances, config,
                        r.referrer, r.voiceInteractor, window, r.configCallback,
                        r.assistToken, r.shareableActivityToken);
    //...
    //通过Instrumentation调用activity.onCreate方法
    mInstrumentation.callActivityOnCreate(activity, r.state);
     //...
}
```

performLaunchActivity这里先通过类加载器初始化Activity，并执行activity.attach，再通过Instrumentation调用activity.onCreate方法。  

Activity.attach

```java
final void attach(...) {
    mWindow = new PhoneWindow(this, window, activityConfigCallback);
     //...
    mWindow.setWindowManager(
        (WindowManager)context.getSystemService(Context.WINDOW_SERVICE),
        mToken, mComponent.flattenToString(),
        (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
}
```

attach的时候new了PhoneWindow，并在设置setWindowManager时把token传了过去  

而在Activity的onCreate方法里我们一般会调用setContentView

```java
//Activity.class    
public void setContentView(@LayoutRes int layoutResID) {
    getWindow().setContentView(layoutResID);
    initWindowDecorActionBar();
}
//PhoneWindow.class
public void setContentView(int layoutResID) {
    if (mContentParent == null) {
        installDecor();
    }
    //...
}
private void installDecor() {
    if (mDecor == null) {
        mDecor = generateDecor(-1);
    }
}
protected DecorView generateDecor(int featureId) {
    //...
    return new DecorView(context, featureId, this, getAttributes());
}
```

这时候会new个DecorView，这个DecorView还没和ViewRootImpl关联起来，只是创建了个根view。（ViewRootImpl此时还没创建）

继续看先看ActivityThread.handleResumeActivity  

```java
public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest,
                                 boolean isForward, String reason) {
    if (!performResumeActivity(r, finalStateRequest, reason)) {
        return;
    }
     //...
    final Activity a = r.activity;
    if (r.window == null && !a.mFinished && willBeVisible) {
        r.window = r.activity.getWindow();
        View decor = r.window.getDecorView();
        decor.setVisibility(View.INVISIBLE);
        ViewManager wm = a.getWindowManager();
        a.mDecor = decor;
        if (a.mVisibleFromClient) {
            if (!a.mWindowAdded) {
                //把mWindowAdded设为true
                a.mWindowAdded = true;
                //重要，具体view绘制流程
                wm.addView(decor, l);
            }
        }
    }
     //...
    if (r.activity.mVisibleFromClient) {
        r.activity.makeVisible();
    }
    //...
}
Activity.java
void makeVisible() {
    //前面已经把mWindowAdded设为true
    if (!mWindowAdded) {
        ViewManager wm = getWindowManager();
        wm.addView(mDecor, getWindow().getAttributes());
        mWindowAdded = true;
    }
    //直接把decorview设为可见，此时Activity才真正可见
    mDecor.setVisibility(View.VISIBLE);
}
```

handleResumeActivity方法比较关键，先performResumeActivity去回调activity.onResume，再wm.addView去绘制view，最后activity.makeVisible使Activity可见。

```java
public boolean performResumeActivity(ActivityClientRecord r, boolean finalStateRequest,String reason) {
    //...
    r.activity.performResume(r.startsNotResumed, reason);
    //...
}
Activity.class
final void performResume(boolean followedByPause, String reason) {
    //...
    //里面会判断是不是stop了，只有stop才会执行restart
    performRestart(true /* start */, reason);
    //...
    //回调Activity.onResume
    mInstrumentation.callActivityOnResume(this);
}
```

注意的是执行完Activity.onResume后Activity并没有可见，这时候view还没绘制，真正可见需要执行activity.makeVisible  

继续看WindowManagerImpl.addView

```java
//WindowManagerImpl.java
@Override
public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
    mGlobal.addView(view, params, mContext.getDisplayNoVerify(), mParentWindow,
                    mContext.getUserId());
}
//WindowManagerGlobal.java
public void addView(View view, ViewGroup.LayoutParams params,
                    Display display, Window parentWindow, int userId) {
    //...
    //parentWindow.adjustLayoutParamsForSubWindow先把token传到LayoutParams里
    final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
    if (parentWindow != null) {
        parentWindow.adjustLayoutParamsForSubWindow(wparams);
    } 
    root = new ViewRootImpl(view.getContext(), display);
    mViews.add(view);
    mRoots.add(root);
    root.setView(view, wparams, panelParentView, userId);
}

//Window.java
void adjustLayoutParamsForSubWindow(WindowManager.LayoutParams wp) {
    if (wp.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
        wp.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
        //窗口类型是子窗口 （1000-1999） token用父窗口的token
		wp.token = decor.getWindowToken();
    }else if (wp.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW &&
              wp.type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
		//窗口类型是系统窗口 （2000-2999）没往LayoutParams里设置token
    }else {
        //一般应用窗口（1-99）
        //如果（Window）mContainer为空，就用一开始activity.attach时通过mWindow.setWindowManager传过来的token
        if (wp.token == null) {
            wp.token = mContainer == null ? mAppToken : mContainer.mAppToken;
        }
    }
}
```

addView先parentWindow.adjustLayoutParamsForSubWindow先把token传到LayoutParams里，然后new了ViewRootImpl，并调用其setView来开始view绘制流程。

```java
//ViewRootImpl.java
public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView, int userId) {
    //把LayoutParams参数copy到mWindowAttributes
    mWindowAttributes.copyFrom(attrs);
    //...
    //view绘制
    requestLayout();
    //...
    //添加窗口
    res = mWindowSession.addToDisplayAsUser(mWindow, mWindowAttributes...);
    //...
    // 把DecorView的parent设为ViewRootImpl，即把DecorView与ViewRootImpl关联起来
    view.assignParent(this);
    //...
}
```

ViewRootImpl.setView是view绘制的入口，这里不详细展开。token这时候通过copyFrom传给mWindowAttributes，然后addToDisplayAsUser传给WMS。  

##### 子线程更新UI

跑个题，能否在子线程更新UI？这个牵涉到view绘制时线程检查，即在ViewRootImpl.requestLayout()

```java
//ViewRootImpl.java
public ViewRootImpl(...) {
    //...
    //由于在主线程new的ViewRootImpl，mThread即主线程
    mThread = Thread.currentThread();
    //...
}
public void requestLayout() {
    if (!mHandlingLayoutInLayoutRequest) {
        checkThread();
        mLayoutRequested = true;
        scheduleTraversals();
    }
}
void checkThread() {
    if (mThread != Thread.currentThread()) {
        throw new CalledFromWrongThreadException(
            "Only the original thread that created a view hierarchy can touch its views.");
    }
}
```

要更新UI，比如更新TextView，Button（继承TextView），都会调用View.requestLayout，然后一直调用父view的requestLayout，直到DecorView，DecorView的parent是ViewRootImpl，即最终调用到ViewRootImpl.requestLayout。这里会检查当前线程是否和初始化ViewRootImpl时的线程即主线程一样，不一样的话就抛异常。所以要在子线程更新UI，就想办法绕开checkThread。比如执行完Activity的onResume后才执行WindowManagerImpl.addView>WindowManagerGlobal.addView(new了ViewRootImpl)>ViewRootImpl.setView，这时候才view.assignParent(this);把DecorView和ViewRootImpl绑定起来，所以，在其绑定parent之前更新UI即可绕过线程检查。即只要在activty.onResume调用完之前（即在onResume里）在异步线程更新UI都可以。
