package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;


public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    //public Interpreter tflite;



    // 제스처 이름을 저장한 배열
   // public static String[] gesture = {"원그리기", "대각선↘↖", "대각선↙↗", "주먹 앞으로", "주먹 펼치기", "좌우 움직임", "상하 움직임"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        // 프래그먼트 인스턴스를 참조합니다.
        //TerminalFragment fragment = (TerminalFragment) getSupportFragmentManager().findFragmentById(R.id.fragment);

        // 프래그먼트의 TextView를 참조합니다.
        //predictText = fragment.getTextView();

        //예측한 textView
        //TextView predictText = (TextView)

        //모델 로드
       // tflite = getTfliteInterpreter("cnn-lstm.tflite");


    }


    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

}
