package com.paul.test;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.paul.test.server.IPaulAidlInterface;

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
