package com.paul.test;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.paul.test.server.IPaulAidlInterface;

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
