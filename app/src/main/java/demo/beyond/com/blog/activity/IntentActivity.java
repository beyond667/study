package demo.beyond.com.blog.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.R;
import demo.beyond.com.blog.service.ServiceActivity;

/**
 *
 *
 */
public class IntentActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intent);
        ButterKnife.bind(this);
    }

    @OnClick({R.id.start1,R.id.start2,R.id.start3})
    void clickView(View view) {
        Intent intent;
        switch (view.getId()) {
            case R.id.start1:
                 intent = new Intent("com.jrmf360.action.ENTER");
//                intent.setData(Uri.parse("jrmf://jrmf360.com:8888"));
                startActivity(intent);
                 break;
            case R.id.start2:
                 intent = new Intent();
                intent.setAction("com.jrmf360.action.ENTER");
//                intent.setData(Uri.parse("jrmf://jrmf360.com:8888/first?message=Hello FirstActivity"));
                startActivity(intent);
                break;
            case R.id.start3:
                intent = new Intent();
                intent.setAction("com.jrmf360.action.ENTER1");
//                intent.setData(Uri.parse("jrmf://jrmf360.com:8888/second?message=Hello SecondActivity"));
                startActivity(intent);
                break;
            default:
                break;
        }
    }

}
