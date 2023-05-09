package com.paul.test;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;

import com.paul.test.server.IPaulAidlInterface;


public class ClientMainActivity extends Activity {

    private static final String TAG = "ClientMainActivity";
    private IPaulAidlInterface iPaulAidlInterface;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        findViewById(R.id.send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG,"==========客户端发送");
                if(iPaulAidlInterface==null){
                    bindPaulService();
                }else{
                    try {
                        iPaulAidlInterface.test1("111");
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

            }
        });

        findViewById(R.id.unbind).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unbindService(connection);
            }
        });
    }


    ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.e(TAG, "==========客户端onServiceConnected 发送" + 111+"==="+service);
/*
         // 实现一：用transact直接调
           // 1相当于跟服务端约定好的test1方法，aidl默认生成的方法code也是从1开始
           // 可以看到这种比较麻烦，
           Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            request.writeString("111");
            try {
                boolean status = service.transact(1, request, response, 0);
                String responseStr = response.readString();
                Log.e(TAG, "==========客户端收到返回:" + responseStr);

            } catch (RemoteException e) {
                e.printStackTrace();
            } finally {
                request.recycle();
                response.recycle();
            }
*/


          // 实现二：把transact放到proxy里做
            // 本质上aidl生成的proxy就是这个作用，相当于把客户端多个方法，比如test1 test2统一由proxy来管理，即transact方法和数据Parcel交给proxy来做
            // 这个做还是不太方便，因为如果是同一进程，还是走transaction就太浪费了，因为同一进程可以直接拿Binder实例对象操作
            try {
                MyProxy myProxy = new MyProxy(service);
                myProxy.test1("111");
            } catch (RemoteException e) {
                e.printStackTrace();
            }

             //实现三：客户端添加MyBinder.asInterface来判断是否是同一进程
            iPaulAidlInterface= MyBinder.asInterface(service);
            try {
                String result  = iPaulAidlInterface.test1("11");
                Log.e(TAG,"==========客户端收到"+result);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            //实现四：aidl标准写法，直接用生成的Stub.asInterface
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

            /**
             * 通过以上4个实现，可以理解aidl生成的java文件具体的作用，也就可以理解其优缺点
             * 缺点通过工具生成的文件并不清楚其使用范围是客户端还是服务端会导致冗余代码
             * 比如：1 生成的proxy类只是给客户端用的，服务端不需要
             *      2 生成的Stub里面的onTransact是服务端用的，静态方法asInterface是客户端用的，但是两端都有
             */

            /**
             * 另外asInterface判断是否是同一进程主要原理：
             *  1 服务端stub的构造函数中调用attachInterface(this, DESCRIPTOR);把binder和desc关联起来（记录了mDescriptor和mOwner）
             *  2 服务绑定成功后如果是同一进程，返回的是Binder本身，如果非同一进程返回的是BinderProxy，由于其未绑定其mDescriptor为空
             *  3 客户端调用asInterface时iBinder.queryLocalInterface(DESCRIPTOR)会判断mDescriptor是否为空并且和传过来的相等，
             *    是的话说明是同一进程返回记录的mOwner也就是binder本身对象，否则返回代理对象
             *
             */
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG,"==========客户端解绑"+name);
            iPaulAidlInterface = null;
        }
    };
    private void bindPaulService(){
        Intent intent = new Intent();
        intent.setAction("com.paul.test.server.test1");
        intent.setPackage("com.paul.test.server");
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }
}