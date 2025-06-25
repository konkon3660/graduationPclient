package com.example.graduateproject;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.graduateproject.joystick.JoystickView;
import com.example.graduateproject.utils.WebSocketClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ControlActivity extends AppCompatActivity {

    private WebSocketClient wsClient;
    private WebSocket audioSendWebSocket;
    private WebSocket audioReceiveWebSocket;

    private WebView mjpegWebView;
    private TextView distanceText, commandText, tvHorizontalAngle, tvVerticalAngle;
    private Button btnFire, btnFeedNow;
    private ToggleButton btnLaser, btnRecord, btnReceiveAudio, toggleMode;
    private View robotControlContainer, interactionContainer, sliderContainer;
    private JoystickView joystickView;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private boolean isStreamingAudio = false;
    private boolean isLaserOn = false;
    private int laserX = 90, laserY = 90;
    private final int LASER_MIN_ANGLE = 30, LASER_MAX_ANGLE = 150;
    public int laserSpeed = 5;
    private SeekBar seekBarHorizontal, seekBarVertical;
    private MaterialButton btnLaserUp, btnLaserDown, btnLaserLeft, btnLaserRight;
    private MaterialButton btnPrecision1, btnPrecision5, btnPrecision10;
    private float currentHorizontalAngle = 90f, currentVerticalAngle = 90f;
    private int currentPrecision = 5;
    private ValueAnimator angleAnimator;
    private Handler longPressHandler = new Handler();
    private boolean isLongPressing = false;
    private Runnable longPressRunnable;
    private String feedMode = "manual";  // 급식 모드 기본값
    private MaterialButtonToggleGroup precisionToggleGroup;

    // 초음파 센서 관련 변수들
    private Handler distanceHandler = new Handler(Looper.getMainLooper());
    private Runnable distanceRunnable;
    private static final int DISTANCE_CHECK_INTERVAL = 3000; // 3초마다 체크
    private boolean isDistanceMonitoring = false;

    private String lastSentDirection = "stop"; // 마지막으로 전송한 조이스틱 방향

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        // 1. 먼저 모든 UI 요소들을 초기화
        initViews();
        
        // 2. 컨테이너 뷰들 초기화
        toggleMode = findViewById(R.id.toggleMode);
        robotControlContainer = findViewById(R.id.robotControlContainer);
        interactionContainer = findViewById(R.id.interactionContainer);
        sliderContainer = findViewById(R.id.sliderContainer);
        //precisionToggleGroup = findViewById(R.id.precisionToggleGroup);

        // 3. UI 설정 메서드들 호출
        setupSeekBars();
        setupButtons();

        // 4. 초기 상태 설정 (이제 UI 요소들이 null이 아님)
        sendCommand("audio_receive_off");
        seekBarHorizontal.setProgress(laserX);
        seekBarVertical.setProgress(laserY);
        tvHorizontalAngle.setText(laserX + "°");
        tvVerticalAngle.setText(laserY + "°");

        // 5. 토글 버튼 리스너 설정
        toggleMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                interactionContainer.setVisibility(View.VISIBLE);
                robotControlContainer.setVisibility(View.GONE);
            } else {
                interactionContainer.setVisibility(View.GONE);
                robotControlContainer.setVisibility(View.VISIBLE);
            }
        });

        // 6. WebSocket 및 영상 설정
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        String wsUrl = prefs.getString("server_url", "wss://srg2361.ngrok.app/ws");
        String videoUrl = "https://srg2361.ngrok.app/mjpeg";
        wsClient = new WebSocketClient();
        wsClient.connect(wsUrl, new SimpleListener());
        connectAudioWebSockets();
        mjpegWebView.loadUrl(videoUrl);

        // 7. 초기 UI 상태 설정
        btnReceiveAudio.setChecked(false);

        // 8. 기존 WebSocket 정리
        if (audioReceiveWebSocket != null) {
            audioReceiveWebSocket.close(1000, "초기화");
            audioReceiveWebSocket = null;
        }
        stopAudioPlayback();

        // show_toast 설정값 읽기
        boolean showToast = getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("show_toast", true);
        // 급식 버튼 클릭 동작 정의 (초기에는 onResume에서 활성화 상태 설정)
        btnFeedNow.setOnClickListener(v -> {
            if (!feedMode.equals("manual")) {
                if (showToast) Toast.makeText(this, "자동 급식 모드에서는 수동 급식이 불가능합니다.", Toast.LENGTH_SHORT).show();
            } else {
                int feedAmount = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("feed_amount", 5); // 기본값 5회
                if (feedAmount == 1) {
                    sendCommand("feed");
                } else {
                    sendCommand("feed:" + feedAmount);
                }
                if (showToast) Toast.makeText(this, "급식 " + feedAmount + "회 실행", Toast.LENGTH_SHORT).show();
            }
        });
        btnLaser.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                sendCommand("laser_on");
                isLaserOn = true;
                if (showToast) Toast.makeText(this, "레이저 ON", Toast.LENGTH_SHORT).show();
            } else {
                sendCommand("laser_off");
                isLaserOn = false;
                if (showToast) Toast.makeText(this, "레이저 OFF", Toast.LENGTH_SHORT).show();
            }
        });
        btnFire.setOnClickListener(v -> {
            sendCommand("fire");
            if (showToast) Toast.makeText(this, "공 발사!", Toast.LENGTH_SHORT).show();
        });
        btnRecord.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
                if (showToast) Toast.makeText(this, "오디오 녹음 권한이 필요합니다", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isStreamingAudio) {
                startAudioStreaming();
                if (showToast) Toast.makeText(this, "음성 송신 시작", Toast.LENGTH_SHORT).show();
            } else {
                stopAudioStreaming();
                if (showToast) Toast.makeText(this, "음성 송신 중지", Toast.LENGTH_SHORT).show();
            }
        });
        btnReceiveAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                sendCommand("audio_receive_on");
                connectAudioWebSockets();
                if (showToast) Toast.makeText(this, "음성 수신 시작", Toast.LENGTH_SHORT).show();
            } else {
                sendCommand("audio_receive_off");
                if (audioReceiveWebSocket != null) {
                    audioReceiveWebSocket.close(1000, "수신 중지");
                    audioReceiveWebSocket = null;
                }
                stopAudioPlayback();
                if (showToast) Toast.makeText(this, "음성 수신 중지", Toast.LENGTH_SHORT).show();
            }
        });
        joystickView.setOnMoveListener((angle, strength) -> {
            String direction;
            if (strength < 30) direction = "stop";
            else if (angle >= 45 && angle < 135) direction = "forward";
            else if (angle >= 135 && angle < 225) direction = "left";
            else if (angle >= 225 && angle < 315) direction = "backward";
            else direction = "right";
            if (!direction.equals(lastSentDirection)) {
                sendCommand(direction);
                lastSentDirection = direction;
                if (showToast) Toast.makeText(this, "이동: " + direction, Toast.LENGTH_SHORT).show();
            }
        });
        btnLaserUp.setOnClickListener(v -> {
            laserY = clamp(laserY - laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarVertical.setProgress(laserY);
            sendLaserCommandY();
            if (showToast) Toast.makeText(this, "레이저 ↑", Toast.LENGTH_SHORT).show();
        });
        btnLaserDown.setOnClickListener(v -> {
            laserY = clamp(laserY + laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarVertical.setProgress(laserY);
            sendLaserCommandY();
            if (showToast) Toast.makeText(this, "레이저 ↓", Toast.LENGTH_SHORT).show();
        });
        btnLaserLeft.setOnClickListener(v -> {
            laserX = clamp(laserX - laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarHorizontal.setProgress(laserX);
            sendLaserCommandX();
            if (showToast) Toast.makeText(this, "레이저 ←", Toast.LENGTH_SHORT).show();
        });
        btnLaserRight.setOnClickListener(v -> {
            laserX = clamp(laserX + laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarHorizontal.setProgress(laserX);
            sendLaserCommandX();
            if (showToast) Toast.makeText(this, "레이저 →", Toast.LENGTH_SHORT).show();
        });
    }

    private void initViews() {
        // 레이저 관련
        seekBarHorizontal = findViewById(R.id.seekBarHorizontal);
        seekBarVertical = findViewById(R.id.seekBarVertical);
        tvHorizontalAngle = findViewById(R.id.tvHorizontalAngle);
        tvVerticalAngle = findViewById(R.id.tvVerticalAngle);
        btnLaserUp = findViewById(R.id.btnLaserUp);
        btnLaserDown = findViewById(R.id.btnLaserDown);
        btnLaserLeft = findViewById(R.id.btnLaserLeft);
        btnLaserRight = findViewById(R.id.btnLaserRight);
//        btnPrecision1 = findViewById(R.id.btnPrecision1);
//        btnPrecision5 = findViewById(R.id.btnPrecision5);
//        btnPrecision10 = findViewById(R.id.btnPrecision10);

        // 기존
        mjpegWebView = findViewById(R.id.mjpegWebView);
        distanceText = findViewById(R.id.distanceText);
        commandText = findViewById(R.id.commandText);
        btnLaser = findViewById(R.id.btnLaser);
        btnFeedNow = findViewById(R.id.btnFeedNow);
        btnFire = findViewById(R.id.btnFire);
        btnRecord = findViewById(R.id.btnRecord);
        btnReceiveAudio = findViewById(R.id.btnReceiveAudio);
        joystickView = findViewById(R.id.joystickView);
    }

    // 모드 상태 최신화 - 앱 복귀 시 자동 적용
    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        feedMode = prefs.getString("mode", "manual");
        btnFeedNow.setEnabled(feedMode.equals("manual"));
    }

    private void connectAudioWebSockets() {
        if (audioSendWebSocket != null) {
            audioSendWebSocket.close(1000, "재연결");
        }
        if (audioReceiveWebSocket != null) {
            audioReceiveWebSocket.close(1000, "재연결");
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build();

        Request sendRequest = new Request.Builder().url("wss://srg2361.ngrok.app/ws/audio_send").build();
        audioSendWebSocket = client.newWebSocket(sendRequest, new WebSocketListener() {
            public void onOpen(WebSocket ws, Response r) { Log.i("AudioSendWS", "송신 WS 연결됨"); }
            public void onFailure(WebSocket ws, Throwable t, Response r) { Log.e("AudioSendWS", "송신 실패: " + t); }
        });

        Request receiveRequest = new Request.Builder().url("wss://srg2361.ngrok.app/ws/audio_receive").build();
        audioReceiveWebSocket = client.newWebSocket(receiveRequest, new WebSocketListener() {
            public void onOpen(WebSocket ws, Response r) { Log.i("AudioReceiveWS", "수신 WS 연결됨"); }
            public void onMessage(WebSocket ws, ByteString bytes) { playReceivedAudio(bytes.toByteArray()); }
            public void onFailure(WebSocket ws, Throwable t, Response r) { Log.e("AudioReceiveWS", "수신 실패: " + t); }
        });
    }

    private void startAudioStreaming() {
        int sampleRate = 16000;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            Toast.makeText(this, "오디오 녹음 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            return;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        audioRecord.startRecording();
        isStreamingAudio = true;

        audioThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isStreamingAudio && audioSendWebSocket != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    try {
                        audioSendWebSocket.send(ByteString.of(buffer, 0, read));
                        Log.d("AudioRecord", "read: " + read);
                    } catch (Exception e) {
                        Log.e("AudioSendWS", "오디오 전송 중 예외", e);
                    }
                }
            }
        });
        audioThread.start();
    }

    private void stopAudioStreaming() {
        isStreamingAudio = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void playReceivedAudio(byte[] data) {
        Log.d("AudioReceive", "데이터 수신됨: 길이=" + data.length);
        if (audioTrack == null) initAudioTrack();

        if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.write(data, 0, data.length);
        } else {
            Log.e("AudioTrack", "❌ AudioTrack이 초기화되지 않음. write 생략");
        }
    }

    private void initAudioTrack() {
        int sampleRate = 16000;
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );

        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
            Log.d("AudioTrack", "✅ AudioTrack 초기화 및 재생 시작");
        } else {
            Log.e("AudioTrack", "❌ AudioTrack 초기화 실패!");
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void stopAudioPlayback() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e("AudioTrack", "오디오 해제 중 오류", e);
            }
            audioTrack = null;
        }
    }

    private void sendCommand(String command) {
        if (wsClient != null) wsClient.sendText(command);
    }

    private void sendLaserCommand() {
        String cmd = "laser_xy:" + laserX + "," + laserY;
        sendCommand(cmd);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 거리 모니터링 중지
        stopDistanceMonitoring();
        // WebView 연결 해제
        if (mjpegWebView != null) {
            mjpegWebView.loadUrl("about:blank");
            mjpegWebView.clearHistory();
            mjpegWebView.clearCache(true);
            mjpegWebView.destroy();
        }
        if (wsClient != null) wsClient.close();
        if (audioSendWebSocket != null) audioSendWebSocket.close(1000, "종료");
        if (audioReceiveWebSocket != null) audioReceiveWebSocket.close(1000, "종료");

        stopAudioStreaming();   // 마이크 중지
        stopAudioPlayback();    // 🔧 스피커 중지
        isStreamingAudio = false;
    }

    private class SimpleListener extends WebSocketClient.WebSocketListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        
        @Override
        public void onOpen() {
            handler.post(() -> {
                commandText.setText("🤖 현재 상태: 연결됨");
                // WebSocket 연결 후 초음파 센서 모니터링 시작
                startDistanceMonitoring();
            });
        }
        
        @Override
        public void onMessage(String text) {
            handler.post(() -> {
                if (text.startsWith("distance:") || text.startsWith("error:")) {
                    handleDistanceResponse(text);
                } else if (text.trim().startsWith("{") && text.trim().endsWith("}")) {
                    // JSON 메시지 처리
                    try {
                        JSONObject obj = new JSONObject(text);
                        if (obj.has("type") && "ultrasonic".equals(obj.getString("type"))) {
                            if (obj.has("distance")) {
                                double distance = obj.getDouble("distance");
                                if (distance <= 5.0) {
                                    distanceText.setText("📏 밥통 상태: 차 있음 (" + String.format("%.1f", distance) + "cm)");
                                } else {
                                    distanceText.setText("📏 밥통 상태: 비어 있음 (" + String.format("%.1f", distance) + "cm)");
                                }
                            } else if (obj.has("error")) {
                                String errorMsg = obj.getString("error");
                                distanceText.setText("📏 밥통 상태: 측정 불가 (" + errorMsg + ")");
                            }
                        }
                        // 명령 처리 결과 JSON은 로그만 남기고 UI에는 표시하지 않음
                        Log.d("WebSocket", "명령 처리 결과: " + text);
                    } catch (JSONException e) {
                        Log.e("WebSocket", "JSON 파싱 오류: " + text, e);
                    }
                } else {
                    commandText.setText("서버 응답: " + text);
                }
            });
        }
        
        @Override
        public void onClose(int code, String reason) {
            handler.post(() -> {
                commandText.setText("🤖 현재 상태: 연결 끊김");
                stopDistanceMonitoring();
            });
        }
        
        @Override
        public void onFailure(Throwable t) {
            handler.post(() -> {
                commandText.setText("🤖 현재 상태: 연결 실패");
                stopDistanceMonitoring();
            });
        }
    }

    // 초음파 센서 거리 측정 시작
    private void startDistanceMonitoring() {
        if (isDistanceMonitoring) return;
        
        isDistanceMonitoring = true;
        
        // 즉시 첫 번째 거리 측정 요청
        requestDistance();
        
        // 3초마다 거리 측정 요청
        distanceRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDistanceMonitoring) {
                    requestDistance();
                    distanceHandler.postDelayed(this, DISTANCE_CHECK_INTERVAL);
                }
            }
        };
        distanceHandler.postDelayed(distanceRunnable, DISTANCE_CHECK_INTERVAL);
    }
    
    // 초음파 센서 거리 측정 중지
    private void stopDistanceMonitoring() {
        isDistanceMonitoring = false;
        if (distanceRunnable != null) {
            distanceHandler.removeCallbacks(distanceRunnable);
            distanceRunnable = null;
        }
    }
    
    // 거리 측정 요청
    private void requestDistance() {
        try {
            JSONObject request = new JSONObject();
            request.put("type", "ultrasonic");
            request.put("action", "get_distance");
            wsClient.sendText(request.toString());
        } catch (JSONException e) {
            Log.e("Distance", "거리 측정 요청 JSON 생성 실패", e);
        }
    }
    
    // 거리 응답 처리
    private void handleDistanceResponse(String response) {
        try {
            // "distance: " 또는 "error: "로 시작하는지 확인
            if (response.startsWith("distance:")) {
                // 거리 측정 성공
                String distanceStr = response.substring("distance:".length()).trim();
                double distance = Double.parseDouble(distanceStr);
                
                if (distance <= 5.0) {
                    distanceText.setText("📏 밥통 상태: 차 있음 (" + String.format("%.1f", distance) + "cm)");
                } else {
                    distanceText.setText("📏 밥통 상태: 비어있음 (" + String.format("%.1f", distance) + "cm)");
                }
            } else if (response.startsWith("error:")) {
                // 측정 실패
                String errorMsg = response.substring("error:".length()).trim();
                distanceText.setText("📏 밥통 상태: 측정 불가 (" + errorMsg + ")");
            } else {
                // 알 수 없는 응답 형식
                distanceText.setText("📏 밥통 상태: 응답 오류");
            }
        } catch (NumberFormatException e) {
            Log.e("Distance", "거리 값 파싱 실패: " + response, e);
            distanceText.setText("📏 밥통 상태: 값 오류");
        } catch (Exception e) {
            Log.e("Distance", "거리 응답 처리 실패: " + response, e);
            distanceText.setText("📏 밥통 상태: 처리 오류");
        }
    }

    private void setupSeekBars() {
        seekBarHorizontal = findViewById(R.id.seekBarHorizontal);
        seekBarVertical = findViewById(R.id.seekBarVertical);
        tvHorizontalAngle = findViewById(R.id.tvHorizontalAngle);
        tvVerticalAngle = findViewById(R.id.tvVerticalAngle);

        seekBarHorizontal.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                laserX = progress;
                tvHorizontalAngle.setText(progress + "°");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // X값만 전송
                sendLaserCommandX();
            }
        });
        seekBarVertical.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                laserY = progress;
                tvVerticalAngle.setText(progress + "°");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // Y값만 전송
                sendLaserCommandY();
            }
        });
        tvHorizontalAngle.setText(seekBarHorizontal.getProgress() + "°");
        tvVerticalAngle.setText(seekBarVertical.getProgress() + "°");
    }

    private void setupButtons() {
        // 레이저 버튼
        btnLaser = findViewById(R.id.btnLaser);
        btnLaser.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                sendCommand("laser_on");
                isLaserOn = true;
            } else {
                sendCommand("laser_off");
                isLaserOn = false;
            }
        });
        // 공 발사 버튼
        btnFire = findViewById(R.id.btnFire);
        btnFire.setOnClickListener(v -> sendCommand("fire"));
        // 급식 버튼
        btnFeedNow = findViewById(R.id.btnFeedNow);
        btnFeedNow.setOnClickListener(v -> {
            if (!feedMode.equals("manual")) {
                Toast.makeText(this, "자동 급식 모드에서는 수동 급식이 불가능합니다.", Toast.LENGTH_SHORT).show();
            } else {
                // SettingActivity에서 저장된 급식 횟수 읽기
                int feedAmount = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("feed_amount", 5); // 기본값 5회
                
                if (feedAmount == 1) {
                    sendCommand("feed");
                } else {
                    sendCommand("feed:" + feedAmount);
                }
                
                Toast.makeText(this, "급식 " + feedAmount + "회 실행", Toast.LENGTH_SHORT).show();
            }
        });
        // 음성 송신 토글
        btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
                return;
            }
            if (!isStreamingAudio) {
                startAudioStreaming();
            } else {
                stopAudioStreaming();
            }
        });
        // 음성 수신 토글
        btnReceiveAudio = findViewById(R.id.btnReceiveAudio);
        btnReceiveAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                sendCommand("audio_receive_on");
                connectAudioWebSockets();
            } else {
                sendCommand("audio_receive_off");
                if (audioReceiveWebSocket != null) {
                    audioReceiveWebSocket.close(1000, "수신 중지");
                    audioReceiveWebSocket = null;
                }
                stopAudioPlayback();
            }
        });
        // 조이스틱
        joystickView = findViewById(R.id.joystickView);
        joystickView.setOnMoveListener((angle, strength) -> {
            String direction;
            if (strength < 30) direction = "stop";
            else if (angle >= 45 && angle < 135) direction = "forward";
            else if (angle >= 135 && angle < 225) direction = "left";
            else if (angle >= 225 && angle < 315) direction = "backward";
            else direction = "right";
            if (!direction.equals(lastSentDirection)) {
                sendCommand(direction);
                lastSentDirection = direction;
            }
        });
        // 레이저 미세조정 버튼(상하좌우)
        btnLaserUp = findViewById(R.id.btnLaserUp);
        btnLaserDown = findViewById(R.id.btnLaserDown);
        btnLaserLeft = findViewById(R.id.btnLaserLeft);
        btnLaserRight = findViewById(R.id.btnLaserRight);
        btnLaserUp.setOnClickListener(v -> {
            laserY = clamp(laserY - laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarVertical.setProgress(laserY);
            sendLaserCommandY();
        });
        btnLaserDown.setOnClickListener(v -> {
            laserY = clamp(laserY + laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarVertical.setProgress(laserY);
            sendLaserCommandY();
        });
        btnLaserLeft.setOnClickListener(v -> {
            laserX = clamp(laserX - laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarHorizontal.setProgress(laserX);
            sendLaserCommandX();
        });
        btnLaserRight.setOnClickListener(v -> {
            laserX = clamp(laserX + laserSpeed, LASER_MIN_ANGLE, LASER_MAX_ANGLE);
            seekBarHorizontal.setProgress(laserX);
            sendLaserCommandX();
        });
    }

    // X값만 전송
    private void sendLaserCommandX() {
        sendCommand("laser_x:" + laserX);
    }
    // Y값만 전송
    private void sendLaserCommandY() {
        sendCommand("laser_y:" + laserY);
    }
}
