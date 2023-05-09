package com.paul.test.server;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

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
