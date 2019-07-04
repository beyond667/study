package demo.beyond.com.blog.activity;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.R;

/**
 *
 *
 */
public class LifeActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.e("===","=======onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_life);
        ButterKnife.bind(this);
    }
    @Override
    protected void onStart() {
        Log.e("===","=======onStart1");
        super.onStart();
    }
    @Override
    protected void onRestart() {
        Log.e("===","=======onRestart");
        super.onRestart();
    }

    @Override
    protected void onResume() {
        Log.e("===","=======onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.e("===","=======onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.e("===","=======onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.e("===","=======onDestroy");
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.e("===","=======onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        Log.e("===","=======onSaveInstanceState");
        super.onSaveInstanceState(outState, outPersistentState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        Log.e("===","=======onRestoreInstanceState");
        super.onRestoreInstanceState(savedInstanceState);
    }

    @OnClick({R.id.second})
    void clickView(View view) {
        Intent intent;
        switch (view.getId()) {
            case R.id.second:
                 intent = new Intent(this,SecondActivity.class);
                startActivity(intent);
                 break;
            default:
                break;
        }
    }

}
