package demo.beyond.com.blog.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

//import demo.beyond.com.blog.IMyAidlInterface;


public class AIDLService extends Service{
    private static final String TAG = "AIDLService";

//    private IMyAidlInterface.Stub stub = new IMyAidlInterface.Stub() {
//
//        @Override
//        public void operation(int param1, int param2) {
//            Log.e(TAG, "=========operation 被调用");
////            return param1 * param2;
//        }
//    };

    public AIDLService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Toast.makeText(this,"服务绑定成功",Toast.LENGTH_LONG).show();
        return null;
    }
}
