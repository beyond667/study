package com.paul.test.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

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
//        return myBinder;
        return binder;
    }

    private final MyBinder myBinder = new MyBinder() {
        @Override
        public String test1(String param1) throws RemoteException {
            Log.e(TAG, "===========MyBinder=服务端收到====" + param1);
            return "222";
        }
    };

    private final IPaulAidlInterface.Stub binder = new IPaulAidlInterface.Stub() {

        @Override
        public String test1(String param1) throws RemoteException {
            Log.e(TAG, "============服务端收到====" + param1);
            return "222";
        }
    };

}