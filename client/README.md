
#### 客户端
这个项目和server项目一起，为深入理解aidl具体生成的类的作用，通过四种方式调用服务端

##### aidl环境
aidl文件：src/main/aidl/IPaulAidlInterface.java
```java
interface IPaulAidlInterface {
    String test1(String param1);
}
```

服务端MyService.java
```java
public class MyService extends Service {
    private static final String TAG = "MyService";

    public MyService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "====================onBind");
        return myBinder;
//        return binder;
    }

    //自定义的Binder
    private final MyBinder myBinder = new MyBinder() {
        @Override
        public String test1(String param1) throws RemoteException {
            Log.e(TAG, "===========MyBinder=服务端收到====" + param1);
            return "222";
        }
    };

    //aidl的写法，自动生成的Stud
    private final IPaulAidlInterface.Stub binder = new IPaulAidlInterface.Stub() {

        @Override
        public String test1(String param1) throws RemoteException {
            Log.e(TAG, "============服务端收到====" + param1);
            return "222";
        }
    };

}
```
抽象类MyBinder.java，主要通过`onTransact`来获取客户端transact发送过来的Parcel数据。
```java
public abstract class MyBinder extends Binder implements IPaulAidlInterface {
    private static final String DESCRIPTOR = "com.paul.test.server.IPaulAidlInterface";
    private static final String TAG = "MyBinder";

    public MyBinder() {
        this.attachInterface(this, DESCRIPTOR);
    }


    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.e(TAG, "============服务端onTransact====" + code+"==="+data);
        switch (code){
            case 1:
                String _arg0 = data.readString();
                java.lang.String result = this.test1(_arg0);
                reply.writeString(result);
                break;
            default:break;
        }
        return true;
    }
}
```

##### 实现一：用transact直接调
```java
        // 1相当于跟服务端约定好的test1方法，aidl默认生成的方法code也是从1开始
        // 可以看到这种比较麻烦，
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        request.writeString("111");
        try {
        boolean status = service.transact(1, request, response, 0);
        String responseStr = response.readString();
        } catch (RemoteException e) {
        e.printStackTrace();
        } finally {
        request.recycle();
        response.recycle();
        }
```

##### 实现二：客户端把transact放到proxy里做
```java
            // 本质上aidl生成的proxy就是这个作用，相当于把客户端多个方法，比如test1 test2统一由proxy来管理，即transact方法和数据Parcel交给proxy来做
            // 这个做还是不太方便，因为如果是同一进程，还是走transaction就太浪费了，因为同一进程可以直接拿Binder实例对象操作
            try {
                MyProxy myProxy = new MyProxy(service);
                myProxy.test1("111");
            } catch (RemoteException e) {
                e.printStackTrace();
            }
```

MyProxy.java
```java
public class MyProxy implements IPaulAidlInterface {
    private IBinder service;
    public MyProxy(IBinder service) {
        this.service = service;
    }

    @Override
    public IBinder asBinder() {
        return service;
    }

    @Override
    public String test1(String param1) throws RemoteException {
        Log.e("===", "============客户端proxy 发送====" + param1);
        String responseStr =null;
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        request.writeString("111");
        try {
            boolean status = service.transact(1, request, response, 0);
            responseStr = response.readString();
            Log.e("===", "==========客户端收到返回:" + responseStr);

        } catch (RemoteException e) {
            e.printStackTrace();
        } finally {
            request.recycle();
            response.recycle();
        }
        return responseStr;
    }
}
```
##### 实现三：客户端添加MyBinder.asInterface来判断是否是同一进程
```java
            iPaulAidlInterface= MyBinder.asInterface(service);
            try {
                String result  = iPaulAidlInterface.test1("11");
                Log.e(TAG,"==========客户端收到"+result);
            } catch (RemoteException e) {
                e.printStackTrace();
            }


```
客户端的MyBinder
```java
public abstract class MyBinder extends Binder implements IPaulAidlInterface {
    private static final String DESCRIPTOR = "com.paul.test.server.IPaulAidlInterface";
    private static final String TAG = "MyBinder";

    @Override
    public IBinder asBinder() {
        return this;
    }

    public static final IPaulAidlInterface asInterface(IBinder iBinder){
        if(iBinder==null){return  null;}
        IInterface iInterface = iBinder.queryLocalInterface(DESCRIPTOR);
        if(iInterface!=null && iInterface instanceof IPaulAidlInterface){
            return (IPaulAidlInterface)iInterface;
        }else{
            return new MyProxy(iBinder);
        }
    }
}
```

##### 实现四：aidl标准写法，直接用生成的Stub.asInterface方法
```java
            //直接用aidl生成的Stub（继承Binder）的asInterface，这里会判断是否是同一进程来返回IBinder实例还是其代理对象
            // 这里由于用的是IPaulAidlInterface.Stub.asInterface，服务端的onBind方法最好返回IPaulAidlInterface.Stub
            // 因为如果用aidl自动生成的，其transact会先写writeInterfaceToken，再写param1，读写顺序要一样
            iPaulAidlInterface = IPaulAidlInterface.Stub.asInterface(service);
            try {
                String result  = iPaulAidlInterface.test1("11");
                Log.e(TAG,"==========客户端收到"+result);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

```

另外asInterface判断是否是同一进程主要原理：  
* 1 服务端stub的构造函数中调用attachInterface(this, DESCRIPTOR);把binder和desc关联起来（记录了mDescriptor和mOwner）
* 2 服务绑定成功后如果是同一进程，返回的是Binder本身，如果非同一进程返回的是BinderProxy，由于其未绑定其mDescriptor为空
* 3 客户端调用asInterface时iBinder.queryLocalInterface(DESCRIPTOR)会判断mDescriptor是否为空并且和传过来的相等， 是的话说明是同一进程返回记录的mOwner也就是binder本身对象，否则返回代理对象

#### 总结
通过以上4个实现，可以理解aidl生成的java文件具体的作用，也就可以理解其优缺点。  
缺点: 通过工具生成的文件并不清楚其使用范围是客户端还是服务端会导致冗余代码，比如：
* 1 生成的proxy类只是给客户端用的，服务端不需要  
* 2 生成的Stub里面的onTransact是服务端用的，静态方法asInterface是客户端用的，但是两端都有
