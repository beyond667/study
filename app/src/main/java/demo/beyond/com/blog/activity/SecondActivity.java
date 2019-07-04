package demo.beyond.com.blog.activity;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.R;

/**
 *
 *
 */
public class SecondActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);
        ButterKnife.bind(this);
    }

    @OnClick({R.id.back})
    void clickView(View view) {
        Intent intent;
        switch (view.getId()) {
            case R.id.back:
                finish();
                 break;
            default:
                break;
        }
    }

}
