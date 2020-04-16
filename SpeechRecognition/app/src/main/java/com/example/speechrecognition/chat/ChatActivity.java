package com.example.speechrecognition.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;
import com.example.speechrecognition.R;
import com.example.speechrecognition.client.ClientService;
import com.example.speechrecognition.server.ServerService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity implements com.baidu.speech.EventListener{

    private static final String TAG = "ChatActivity";

    private TextView tvName, tvAddress;

    protected VoiceDialogManager dialogManager = new VoiceDialogManager(ChatActivity.this);
    private EventManager asr;

    private Button recognitionBTN;

    private String recognitionRES = null;

//    private Button btnSend;

    private List<Chat> list;

    private RecyclerView rvChat;

    private ChatAdapter chatAdapter;

    private BluetoothDevice device;
    //uuid为null时，来自服务的聊天框，isClient=false
    private String uuid;

    private boolean isClient;

    private Handler handler;

    private ClientService clientService;

    private ServerService serverService;

    private volatile static boolean exit = false;

    private void start(){
        Map<String,Object> params = new LinkedHashMap<>();//传递Map<String,Object>的参数，会将Map自动序列化为json
        String event;
        event = SpeechConstant.ASR_START;
        params.put(SpeechConstant.ACCEPT_AUDIO_VOLUME, false);//回调当前音量
        String json;
        json = new JSONObject(params).toString();//demo用json数据来做数据交换的方式
        asr.send(event, json, null, 0, 0);// 初始化EventManager对象,这个实例只能创建一次，就是我们上方创建的asr，此处开始传入
    }

    private void stop(){
//        txtResult.append("识别结果：");
        asr.send(SpeechConstant.ASR_STOP, null, null, 0, 0);//此处停止
    }

    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_chat);

        asr = EventManagerFactory.create(this,"asr");  // 注册自己的输出事件类
        asr.registerListener(this);  // 调用 EventListener 中 onEvent方法

        initPermission();
        initPreData();

        bindView();

        initView();

//        setListener();

        setRecognitionListener();

        registerReceiver();

        initData();
    }

    private void registerReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, intentFilter);
    }

    // 语音识别
    private void setRecognitionListener(){
        recognitionBTN.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        recognitionBTN.setText("松开识别");
                        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                        vibrator.vibrate(new long[] {100, 100}, -1);
                        dialogManager.init();
                        start();
                        break;
                    case MotionEvent.ACTION_UP :
                        stop();
                        dialogManager.dismiss();
                        send(recognitionRES);
                        recognitionBTN.setText("按住说话");
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void send(String text) {
        if (text == null) {
            Log.i(TAG, "识别失败！");
        }
        else {
            while (text.startsWith("\n")) {
                text = text.substring(2, text.length());
            }
            while (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 2);
            }

            if (text.length() > 0) {
                if (isClient) {
                    clientService.write(text);
                } else {
                    serverService.write(text);
                }
                recognitionRES = null;
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private void initData() {
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case ChatService.WRITE_DATA_SUCCESS:
                        Log.i(TAG, "write success");
                        String selfText = (String) msg.obj;
                        Chat selfChat = new Chat(selfText, true);
                        list.add(selfChat);
                        chatAdapter.notifyItemChanged(list.size() - 1);
                        rvChat.scrollToPosition(list.size()-1);
                        break;
                    case ChatService.WRITE_DATA_FAIL:
                        Log.i(TAG, "write fail");
                        exitChatDialog("对方已经退出聊天，即将停止聊天", false);
                    case ChatService.READ_DATA_FAIL:
                        exitChatDialog("对方已经退出聊天，即将停止聊天", false);
                        break;

                    case ChatService.READ_DATA_SUCCESS:
                        Log.i(TAG, "read success");
                        String text = (String) msg.obj;
                        Chat chat = new Chat(text, false);
                        list.add(chat);
                        chatAdapter.notifyItemChanged(list.size() - 1);
                        rvChat.scrollToPosition(list.size()-1);
                        break;
                }
            }
        };

        if (isClient) {
            clientService = ClientService.getInstance(handler);

            clientService.connect(device, uuid);
        } else {
            serverService = ServerService.getInstance(handler);
        }

    }

    private void initPreData() {
        device = getIntent().getParcelableExtra("device");

        uuid = getIntent().getStringExtra("uuid");

        if (uuid == null) {
            isClient = false;
        } else {
            isClient = true;
        }

        if (device == null) {
            Toast.makeText(this, "对方已经退出连接!", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void bindView() {

        tvName = findViewById(R.id.tvName);

        tvAddress = findViewById(R.id.tvAddress);

        rvChat = findViewById(R.id.rvChat);

        recognitionBTN = findViewById(R.id.recognitionBTN);

//        btnSend = findViewById(R.id.btnSend);

    }

    private void initView() {
        if (device.getName() == null) {
            tvName.setText("外星人");
        } else {
            tvName.setText(device.getName());
        }

        tvAddress.setText(device.getAddress());

        list = new ArrayList<>();

        chatAdapter = new ChatAdapter(this, list);

        rvChat.setLayoutManager(new LinearLayoutManager(this));

        rvChat.setAdapter(chatAdapter);

    }

    BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, intent.getAction());
            if (intent.getAction().equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                if (clientService != null) {
                    clientService.cancel();
                }
                exitChatDialog("当前连接已断开，请重新连接", false);
            } else if (intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    exitChatDialog("当前连接已断开，请重新连接", false);
                }
            }
        }
    };

//    @Override
//    protected void onDestroy() {
//        super.onDestroy();
//        unregisterReceiver(bluetoothReceiver);
//        if (isClient) {
//            clientService.cancel();
//        }
//
//    }

    public void exitChatDialog(String text, boolean cancelable) {
        if (exit) {
            return;
        }

        exit = true;


        AlertDialog dialog = new AlertDialog.Builder(this)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                        if (isClient) {
                            clientService.cancel();
                        }
                    }
                })
                .setMessage(text).create();


        dialog.setCancelable(cancelable);


        dialog.show();

        if (isClient) {
            clientService.cancel();
        }


    }

    @Override
    public void onBackPressed() {
        exitChatDialog("退出当前聊天？", false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
        asr.unregisterListener(this);//退出事件管理器
        // 必须与registerListener成对出现，否则可能造成内存泄露
        unregisterReceiver(bluetoothReceiver);
        if (isClient) {
            clientService.cancel();
        }
    }
    public void onEvent(String name, String params, byte[] data, int offset, int length) {
        if (recognitionRES == null) {
            if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL)){  // 识别结果参数
                if (params.contains("\"final_result\"")){  // 语义结果值
                    try {
                        JSONObject json = new JSONObject(params);
                        recognitionRES = json.getString("best_result");  // 取得key的识别结果
                        Toast.makeText(getApplicationContext(), "识别成功！", Toast.LENGTH_LONG);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        else {
            Log.i(TAG, "previous recognition does't set null!");
        }
//        if (resultTxt != null){
//            resultTxt += "\n";
//            recognitionRES += resultTxt;
//        }
    }

    private void initPermission() {
        String permissions[] = {Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        ArrayList<String> toApplyList = new ArrayList<>();

        for (String perm :permissions){
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
                toApplyList.add(perm);
                //进入到这里代表没有权限.
            }
        }
        String tmpList[] = new String[toApplyList.size()];
        if (!toApplyList.isEmpty()){
            ActivityCompat.requestPermissions(this, toApplyList.toArray(tmpList), 123);
        }

    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 此处为android 6.0以上动态授权的回调，用户自行实现。
    }
}
