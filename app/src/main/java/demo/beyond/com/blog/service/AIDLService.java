package demo.beyond.com.blog.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import demo.beyond.com.blog.IMyAidlInterface;
import demo.beyond.com.blog.IMyListener;

public class AIDLService extends Service{
    private static final String TAG = "AIDLService";
    public AIDLService() {
    }

    private IMyAidlInterface.Stub stub = new IMyAidlInterface.Stub() {
        @Override
        public void operation(int param1, int param2, IMyListener listener) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            try {
                listener.onSuccess(param1*param2);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

        }


    };

    @Override
    public IBinder onBind(Intent intent) {
        Toast.makeText(this,"服务绑定成功",Toast.LENGTH_LONG).show();
        return stub;
    }
}