package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.common.util.ArrayUtils;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedBytes;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected {False, Pending, True}

    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;
    //
    private TextView predictText;
    //
    private TextView receiveText;
    private TextView sendText;
    private ControlLines controlLines;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean controlLinesEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;
    SpannableStringBuilder spn;
    // ===========================================================================
    public float[][][] features = new float[27][32][32];  //모델의 입력
    List<Byte> dataBytes = new ArrayList<Byte>();
    byte[] syncCheckBuffer = new byte[8];
    Queue<Byte> syncCheckQueue = new LinkedList<>();

    int receivedByteCnt = 0;
    int frameCnt = 0;
    static float xLimit = (float) 0.2;
    static float yLimit = (float) 0.4;
    static float zLimit = (float) 0.2;
    // public static String[] gesture = {"ForwardBack_Left", "ForwardBack_Right", "UpDown_Left", "UpDown_Right", "LeftRight", "No"};
    public static String[] gesture = {"ForwardBack(Left)", "ForwardBack(Right)", "Spin", "UpDown(Left)", "UpDown(Right)", "No"};
    // public static String[] gesture = {"ForwardBack_Left", "ForwardBack_Right", "UpDown_Left", "UpDown_Right", "centerSpread", "No"};
    // public static String[] gesture = {"ForwardBack_Left", "ForwardBack_Right", "UpDown_Left", "UpDown_Right", "leftSpread", "rightSpread", "No"};
    public Interpreter tflite;
    public long before;
    public long after;
    public long packTime = 0;
    public long dataProcTime = 0;
    public int lostPacketCnt = 0;
    // ===========================================================================

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");

    }
    // 원핫 벡터 outputs에서 가장 확률 높은 레이블 번호를 구해 반환하는 함수(argmax랑 같은 역할)
    public int getPredictedLabel(float[][] outputs) {
        int maxArg = 0;
        for(int i = 0; i < gesture.length; i++) {
            if (outputs[0][maxArg] < outputs[0][i]) maxArg = i;
        }
        return maxArg;
    }

    // softmax를 활용하여 확률
    public float[] getProbabilities(float[][] outputs) { // 확률 반환
        float[] probabilities = new float[gesture.length];  // 제스처 개수 크기로 초기화
        float sum = 0f; //
        for(int i = 0; i < gesture.length; i++) { // outputs 배열의 값을 확률 값으로 변환, sum 변수에 누적
            probabilities[i] = (float)Math.exp(outputs[0][i]);
            sum += probabilities[i];
        }
        for(int i = 0; i < gesture.length; i++) { // sum은 1이고 각각 나누어서 각각의 확률을 계산
            probabilities[i] /= sum;
        }
        return probabilities;
    }

    //모델 입력을 위한 함수
    // 입력받은 modelPath에서 모델 불러와 반환. 아래 loadModeFile 함수 활용.
    private Interpreter getTfliteInterpreter(String modelPath) {
        try {
            return new Interpreter(loadModelFile(getActivity(), modelPath));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // 모델을 읽어오는 함수로, 텐서플로 라이트 홈페이지에 있다.
    // MappedByteBuffer 바이트 버퍼를 Interpreter 객체에 전달하면 모델 해석을 할 수 있다.
    private MappedByteBuffer loadModelFile(Activity activity, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }



    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB));
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if (controlLinesEnabled && controlLines != null && connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if (controlLines != null)
            controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        //모델 로드
        tflite = getTfliteInterpreter("model_0619.tflite");
        //speedText = view.findViewById(R.id.speedText);
        predictText = view.findViewById(R.id.predictText);

        Button configBtn = (Button) view.findViewById(R.id.btnConfig);

        configBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String cfgLine = "";
                AssetManager assetManager = getResources().getAssets();
                InputStream is = null;
                try {
                    is =  assetManager.open("AOP_6m_default.cfg",AssetManager.ACCESS_BUFFER);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                    String line = "";
                    while((line=reader.readLine()) != null ){
                        cfgLine += line + "\n";
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }finally {
                    if(is != null){
                        try {
                            is.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                String[] cfgLines = cfgLine.split("\n");
                for(String line: cfgLines){
                    send(line, 100);
                }
            }
        });

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString(), 0));
        controlLines = new ControlLines(view);

        before = System.currentTimeMillis();
        curTime = System.currentTimeMillis();

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        menu.findItem(R.id.controlLines).setChecked(controlLinesEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            controlLinesEnabled = !controlLinesEnabled;
            item.setChecked(controlLinesEnabled);
            if (controlLinesEnabled) {
                controlLines.start();
            } else {
                controlLines.stop();
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        usbSerialPort = null;
    }

    private void send(String str, int delay) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if (hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            Thread.sleep(delay);
            service.write(data);
        } catch (SerialTimeoutException e) {
            status("write timeout: " + e.getMessage());
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }
    private long curTime;
    private List<float[][][]> gestures = new ArrayList<float[][][]>();
    private List<Boolean> gesture_presences = new ArrayList<Boolean>();



    private void receive(ArrayDeque<byte[]> datas) {


        int total_packet = 0;

        // sync 확인용 배열 - 이 순서대로 데이터가 전달되면 패킷 시작지점이라는 의미
        byte[] syncArr = {2, 1, 4, 3, 6, 5, 8, 7};
        spn = new SpannableStringBuilder();

        // 이전에 판단한 제스처를 저장하는 변수
        String previousGesture = "";
        int count_pre = 0;

        for (byte[] data : datas) {
            // spn.append("datas size: "+datas.size());
            for (byte b : data) {
                if (syncCheckQueue.size() == 8){
                    syncCheckQueue.remove();
                }
                syncCheckQueue.add(b);

                // spn.append(syncCheckQueue.size()+"\n");
                ArrayList list = new ArrayList(syncCheckQueue);

                byte[] test2 = new byte[8];
                for(int i=0; i<list.size(); i++){
                    test2[i] = (byte) list.get(i);
                }
                // spn.append(test2.toString());

                // sync확인용 버퍼와 데이터 버퍼에 수신 바이트 하나씩 추가
                syncCheckBuffer[(receivedByteCnt++) % 8] = b;

                dataBytes.add(b);

                //sync(패킷의 시작 알림) 확인 ---???
                ByteBuffer buffer = ByteBuffer.wrap(syncCheckBuffer);
                if(syncCheckBuffer[7] == 0)
                    continue;

                if( System.currentTimeMillis()-curTime > 3000 ){
                    spn.append("<3 sec>");
                    curTime = System.currentTimeMillis();
                }

                if(java.util.Arrays.equals(test2, syncArr)){
                    // spn.append("synced\n");
                    // sync 일치 시(새로운 패킷이 시작된다는 의미) 여태 수신한 데이터를 하나의 패킷으로 처리
                    //byte[] packet = new byte[dataBytes.size()];
                    //for (int i = 0; i < packet.length; i++) {
                    //    packet[i] = (byte) dataBytes.get(i);
                    //}
                    byte[] packet = Bytes.toArray(dataBytes);

                    // 프로젝션 데이터 임시 저장용 변수
                    float[][] feature = null;
                    after = System.currentTimeMillis();
                    //spn.append("== frame "+((frameCnt%27)+1)+" - packet received (execution time: "+(after-before)+" ms)\n");
                    packTime += (after-before);
                    before = after;

                    // 패킷 -> 투영 데이터로 변환
                    try{
                        total_packet ++;
                        feature = RawToProjectionData(packet);
                    }catch(Exception e){ // 오류 발생 시 처리
                        spn.append("Exception ERROR: " + e.getMessage()+"\n");
                        onSerialIoError(e);
                        return;
                    }

                    // 투영 데이터 처리 (feature가 null이 아니라면!)
                    if(feature != null){
                        after = System.currentTimeMillis();
                        before = after;

                        // 패킷 -> 투영 데이터 변환이 무사히 진행되었으면 27프레임짜리 시퀀스(features)에 현재 프로젝션 이미지 추가
                        features[(frameCnt%27)] = feature;
                        frameCnt = (frameCnt+1)%27;


                        if((frameCnt%27) == 0){  // 27길이의 시퀀스가 다 채워지면 모델 입력 활용(이 if문 조건을 통하여 추론 간격 조절)
                        //if((frameCnt%54) == 0) {
                            float sum = 0;

                            for (Integer length : packetLengths) {
                                sum += length;
                            }

                            int count = packetLengths.size();
                            packetLengths.clear();




/// --solution


                            if (count > 0){
                                if(sum / count < 0.5) {
                                    // 동작 없음
                                    spn.append("-got 27 frames, NO GESTURE-");
                                    predictText.setText("동작 없음");
                                } else {

                                    float[][] outputs = new float[1][gesture.length];

                                    // inputs에 대한 outputs 추론
                                    long beforeTime = System.currentTimeMillis();    // 추론 시간 측정용
                                    tflite.run(features, outputs);
                                    long afterTime = System.currentTimeMillis();

                                    //outputs에서 가장 확률 높은 레이블
                                    int output = getPredictedLabel(outputs);
                                    float[] probabilities = getProbabilities(outputs);

                                    if (gesture[output].equals("Spin")) {
                                        if (sum / count < 0.50) { //THRESHOLD 0.55 - > 0.5
                                            // 동작 없음
                                            predictText.setText("동작 없음");
                                        } else {
                                            if (!gesture[output].equals("No")) {
                                                String result = "예측된 제스처: " + gesture[output] + "\n\n확률:\n";
                                                for(int i = 0; i < gesture.length; i++) {
                                                    result += gesture[i] + ": " + String.format("%.5f", outputs[0][i]) + "\n";
                                                }
                                                spn.append("-"+gesture[output]+"-");
                                                predictText.setText(result);
                                            }

                                        }
                                    } else {
                                        if (sum / count < THRESHOLD) {
                                            // 동작 없음
                                            //spn.append("-got 27 frames, NO GESTURE-");
                                            predictText.setText("동작 없음");
                                        } else {
                                            // 포인트 갯수가 기준 이상일 경우 해당 제스처로 판단
                                            //spn.append("-got " + count + " frames, " + gesture[output] + "-");
                                            //predictText.setText(gesture[output]);

                                            if (!gesture[output].equals("No")) {
                                                String result = "예측된 제스처: " + gesture[output] + "\n\n확률:\n";
                                                for(int i = 0; i < gesture.length; i++) {
                                                    result += gesture[i] + ": " + String.format("%.5f", outputs[0][i]) + "\n";
                                                }
                                                spn.append("-"+gesture[output]+"-");
                                                predictText.setText(result);
                                            }


                                        }
                                    }

                                    gestures.clear();
                                    gesture_presences.clear();

                                    after = System.currentTimeMillis();

                                    before = after;
                                    packTime = 0;
                                    dataProcTime = 0;
                                    lostPacketCnt = 0;
                                }

                            }



// --solutuion2
                            /*

                            if (count > 0){
                                if(sum / count < THRESHOLD) {
                                    // 동작 없음
                                    //spn.append("-got 27 frames, NO GESTURE-");
                                    predictText.setText("동작 없음");
                                }else{
                                    // 동작 있음
                                    //spn.append("-got 27 frames, GESTURE-");
                                    // 모델의 output 배열
                                    float[][] outputs = new float[1][gesture.length];

                                    // inputs에 대한 outputs 추론
                                    long beforeTime = System.currentTimeMillis();    // 추론 시간 측정용
                                    tflite.run(features, outputs);
                                    long afterTime = System.currentTimeMillis();

                                    //outputs에서 가장 확률 높은 레이블
                                    int output = getPredictedLabel(outputs);
                                    float[] probabilities = getProbabilities(outputs);

                                    gestures.clear();
                                    gesture_presences.clear();

                                    after = System.currentTimeMillis();

                                    before = after;
                                    packTime = 0;
                                    dataProcTime = 0;
                                    lostPacketCnt = 0;

                                    if (!gesture[output].equals("No")) {
                                        String result = "예측된 제스처: " + gesture[output] + "\n\n확률:\n";
                                        for(int i = 0; i < gesture.length; i++) {
                                            result += gesture[i] + ": " + String.format("%.5f", outputs[0][i]) + "\n";
                                        }
                                        spn.append("-"+gesture[output]+"-");
                                        predictText.setText(result);
                                    }
                                }
                            }
*/

////////////////

                        }

                    }else{
                        //spn.append("0");
                        lostPacketCnt++;
                    }
                    // 새로운 패킷 처리를 위해 dataBytes 배열 비우기
                    dataBytes.clear();
                    receivedByteCnt = 8;
                    for(byte sync: syncArr){
                        dataBytes.add(sync);
                        //receiveText.append((CharSequence) dataBytes);
                    }
                }

            }
        }
        // spn.append("total_packet: " + total_packet + "\n");
        receiveText.append(spn);
        //receiveText.append(spn + "::");
    }

    // ========================================================================================

    public static float [][] projection(List<HashMap<String, Float>> pointFrame, int wid, int hei){

        // wid*hei 크기의 빈 이미지 생성
        float[][] pixel = new float[wid][hei];

        // projection
        if (pointFrame.size() > 0) {
            for (HashMap<String, Float> p : pointFrame) {
                float x_pos = ((p.get("x") +  xLimit) / (2 * xLimit)) * (wid - 1);
                float z_pos = ((p.get("z") +  zLimit) / (2 * zLimit)) * (hei - 1);
                float y_val = 1 - (p.get("y") / yLimit);
                pixel[Math.round(z_pos)][Math.round(x_pos)] += y_val;
            }
            // 0~1 normalization
            float max = max(pixel);
            for (int i =0; i<wid; i++) {
                for (int j=0; j<hei; j++) {
                    pixel[i][j] /= max;
                }
            }
        }

        return pixel;
    }

    // helper function to calculate power of a matrix
    public static float[][] pow(float[][] matrix, float power) {
        float[][] result = new float[matrix.length][matrix[0].length];
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[0].length; j++) {
                result[i][j] = (float) Math.pow(matrix[i][j], power);
            }
        }
        return result;
    }

    // helper function to calculate maximum value in a matrix
    public static float max(float[][] matrix) {
        float max = Float.NEGATIVE_INFINITY;
        for (float[] row : matrix) {
            for (float val : row) {
                max = Math.max(max, val);
            }
        }
        return max;
    }

    // ===================================================================================

    public HashMap<String, Object> getFrameData(byte[] arr) {
        if(arr.length < 40) {
            //spn.append("frame header lost - received packet length: " + arr.length);
            return null;
        }
        HashMap<String, Object> frameHeader = getFrameHeader(Arrays.copyOfRange(arr, 0, 40));
        HashMap<String, Object> frame = new HashMap<>();
        frame.put("header", frameHeader);
        int packetLen = (int) frameHeader.get("totalPacketLen");
        int numTLVs = (int)frameHeader.get("numTLVs");
        if ( arr.length != packetLen+8 ) {
            //spn.append("1("+packetLen+", "+arr.length+") "); // 손실 발생
            //spn.append("받은 데이터 길이: " + arr.length + " 받아야 할 데이터 길이: " + packetLen + "\n");
            return null;
        }
        //spn.append("2("+packetLen+", "+arr.length+") "); // 손실 없음

        if( Long.toHexString((long)frameHeader.get("sync")).equals("708050603040102") ) {
            arr = Arrays.copyOfRange(arr, 40, packetLen);
            if(numTLVs == 0){ // frame에 'tlvs'가 존재하지 않음 -> 감지한 포인트 클라우드 없음
                return frame;
            }

            List<HashMap> tlvs = new ArrayList<>();
            int read_pos = 0;

            for(int i=0; i<numTLVs; i++) {
                HashMap tlv = getTLV(Arrays.copyOfRange(arr, read_pos, packetLen));
                if ( tlv == null )
                    continue;
                read_pos = (int) tlv.get("length") + 8;
                tlvs.add(tlv);
            }
            frame.put("tlvs", tlvs);
            return frame;
        }
        else {
            //spn.append("sync error");
            return null;
        }
    }

    public HashMap<String, Object> getFrameHeader(byte[] arr) {
        HashMap<String, Object> header = new HashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(arr);

        header.put("sync",  buffer.order(ByteOrder.LITTLE_ENDIAN).getLong());
        header.put("version", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("totalPacketLen", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("platform", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("frameNumber", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("time", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("numDetectedObj", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("numTLVs", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        header.put("subFrameNumber", buffer.order(ByteOrder.LITTLE_ENDIAN).getInt());
        return header;
    }

    public HashMap<String, Object> getTLV(byte[] arr) {
        HashMap<String, Object> tlv = new HashMap<>();
        ByteBuffer TLVheader = ByteBuffer.wrap(Arrays.copyOfRange(arr, 0, 8));
        tlv.put("type", TLVheader.order(ByteOrder.LITTLE_ENDIAN).getInt());
        tlv.put("length", TLVheader.order(ByteOrder.LITTLE_ENDIAN).getInt());

        int type = (int) tlv.get("type");
        int length = (int) tlv.get("length");

        switch (type) {
            case 1020:
                // tlvHeader(8) + pointUnit(12) + pointStruct(4)*numberOfPoints
                int tlvHeaderLen = 8;
                int pointUnitLen = 12;
                int pointStructLen = 4;

                int numOfPoints = (int)((length - tlvHeaderLen - pointUnitLen) / pointStructLen);
                List<Object> points = new ArrayList<>();
                HashMap pointUnit = getPointUnit(Arrays.copyOfRange(arr, tlvHeaderLen, tlvHeaderLen + pointUnitLen));
                arr = Arrays.copyOfRange(arr, tlvHeaderLen + pointUnitLen, arr.length);
                for(int i=0; i < numOfPoints; i++) {
                    HashMap point = getPointCloud(Arrays.copyOfRange(arr, i*pointStructLen, (i+1)*pointStructLen));
                    points.add(point);
                }
                HashMap<String, Object> value = new HashMap<>();
                value.put("unit", pointUnit);
                value.put("points", points);
                tlv.put("value", value);
                return tlv;
            default:
                return null;
        }
    }

    public HashMap<String, Float> getPointUnit(byte[] arr) {
        HashMap<String, Float> unit = new HashMap<>();
        ByteBuffer pointUnit = ByteBuffer.wrap(arr);
        unit.put("elevationUnit", pointUnit.order(ByteOrder.LITTLE_ENDIAN).getFloat());
        unit.put("azimuthUnit", pointUnit.order(ByteOrder.LITTLE_ENDIAN).getFloat());
        unit.put("rangeUnit", pointUnit.order(ByteOrder.LITTLE_ENDIAN).getFloat());
        return unit;
    }

    public HashMap<String, Object> getPointCloud(byte[] arr) {
        HashMap<String, Object> point = new HashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(arr);
        point.put("elevation", (int)buffer.get());
        point.put("azimuth", (int)buffer.get());
        point.put("range", buffer.order(ByteOrder.LITTLE_ENDIAN).getShort());
        return point;
    }

    public List<HashMap<String, Float>> getDataforHAR(HashMap<String, Object> frame) {
        List<HashMap<String, Float>> framePointDatas = new ArrayList<>();
        List<HashMap> tlvs = (List<HashMap>) frame.get("tlvs");

        if (tlvs == null) { // 해당 프레임에서 수집된 포인트 클라우드 없을 시 빈 배열 반환
            return framePointDatas;
        }

        for (HashMap tlv : tlvs) {
            if ( ((int)tlv.get("type")) != 1020) { //포인트 클라우드 이외의 정보는 pass
                continue;
            }

            // 포인트 정보 디코드를 위한 unit 정보 가져오기
            HashMap<String, Float> unit = (HashMap<String, Float>) ((HashMap)tlv.get("value")).get("unit");

            // 포인트 정보 리스트 가져오기
            List<HashMap<String, Object>> points = (List<HashMap<String, Object>>) ((HashMap)tlv.get("value")).get("points");

            // 포인트 정보 리스트 디코딩하여 리스트(반환할 값)에 저장
            for (HashMap<String, Object> p: points) {
                // 포인트 정보 디코딩
                double r = (double)((short)p.get("range")) * (double) unit.get("rangeUnit");
                double el = (double)(int)p.get("elevation") * (double) unit.get("elevationUnit");
                double az = (double)(int)p.get("azimuth") * (double) unit.get("azimuthUnit");
                float x = (float)(r * Math.cos(el) * Math.sin(az));
                float y = (float)(r * Math.cos(el) * Math.cos(az));
                float z = (float)(r * Math.sin(el));

                // 포인트가 관심 영역 내에 존재하는지 확인
                if (Math.abs(x) > xLimit || Math.abs(y) > yLimit || Math.abs(z) > zLimit) {
                    continue;
                }

                // 관심 영역 내에 존재하는 포인트는 리스트에 추가
                HashMap<String, Float> record = new HashMap<>();
                record.put("x", x);
                record.put("y", y);
                record.put("z", z);
                framePointDatas.add(record);
            }
        }
        return framePointDatas;
    }

    private static final int TIME_WINDOW = 1500; // 일정 시간 (ms)
    private static final double THRESHOLD = 0.65; // 리스트의 길이 임계값 참고:0.7

    private final List<Integer> packetLengths = new ArrayList<>(); // 패킷 길이를 저장
    private long lastPacketTime = 0; // 마지막 패킷 수집된 시간
    public float [][] RawToProjectionData(byte[] packet){

        float [][] feature;

        // 각 패킷을 프레임 데이터로 변환 ==============================================================
        HashMap<String, Object> frame = null;
        try{
            frame = getFrameData(packet);
        }catch(Exception e ){
            e.printStackTrace();
            spn.append("getFrameData ERROR: " + e.getMessage()+"\n");
            return null;
        }
        if (frame == null){
            // null 프레임이 반환된 경우 pass(빈 패킷이거나 내용 손상 있는 경우)
            return null;
        }
        List<HashMap<String, Float>> pointDataInFrame = null;
        int averagePacketSize = 0;
        try{
            pointDataInFrame = getDataforHAR(frame); //프레임으 포인트 정보
            //spn.append("--"+pointDataInFrame.size()+"--");
            packetLengths.add(pointDataInFrame.size());

        }catch(Exception e ){
            e.printStackTrace();
            spn.append("getDataforHAR ERROR: " + e.getMessage()+"\n");
            return null;
        }

        try{
            feature = projection(pointDataInFrame, 32, 32);
        }catch(Exception e ){
            e.printStackTrace();
            spn.append("projection ERROR: " + e.getMessage()+"\n");
            return null;
        }

        return feature;
    }


    // ========================================================================================
    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }


    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        if(controlLinesEnabled)
            controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {

        //spn.append(" - onSerialRead(byte Array) - ");
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        //spn.append(" - onSerialRead(ArrayDeque) - ");

        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec 2초마다 파싱..

        private final Handler mainLooper;
        private final Runnable runnable;
        private final LinearLayout frame;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            mainLooper = new Handler(Looper.getMainLooper());
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) {
                    ctrl = "RTS";
                    usbSerialPort.setRTS(btn.isChecked());
                }
                if (btn.equals(dtrBtn)) {
                    ctrl = "DTR";
                    usbSerialPort.setDTR(btn.isChecked());
                }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            frame.setVisibility(View.VISIBLE);
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS))
                    rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS))
                    ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR))
                    dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR))
                    dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))
                    cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))
                    riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            frame.setVisibility(View.GONE);
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
        }




    }
}