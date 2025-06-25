package com.example.graduateproject;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.graduateproject.utils.WebSocketClient;
import com.google.android.material.slider.Slider;

import org.json.JSONException;
import org.json.JSONObject;

public class SettingActivity extends AppCompatActivity {

    private EditText editAddress, editInterval;
    private Switch switchToast, switchAutoFeed;
    private Button btnSaveAddress, btnSaveFeedSetting;
    private LinearLayout intervalContainer;
    private Slider sliderAmount;
    private TextView textAmountValue;

    private final String PREF_NAME = "app_settings";
    private final String KEY_SERVER_URL = "server_url";
    private final String KEY_SHOW_TOAST = "show_toast";
    private final String KEY_FEED_AMOUNT = "feed_amount";
    private final String KEY_FEED_INTERVAL = "feed_interval";
    private final String KEY_AUTO_FEED = "auto_feed";

    private boolean receivedAck = false;
    private final int RESPONSE_TIMEOUT_MS = 2000;

    private SharedPreferences prefs;
    private WebSocketClient wsClient;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);

        // View 바인딩
        editAddress = findViewById(R.id.editAddress);
        editInterval = findViewById(R.id.editInterval);
        btnSaveAddress = findViewById(R.id.btnSaveAddress);
        btnSaveFeedSetting = findViewById(R.id.btnSaveFeedSetting);
        switchToast = findViewById(R.id.switchToast);
        switchAutoFeed = findViewById(R.id.switchAutoFeed);
        sliderAmount = findViewById(R.id.sliderAmount);
        textAmountValue = findViewById(R.id.textAmountValue);
        intervalContainer = findViewById(R.id.intervalContainer);

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        // 이전 값 로드
        editAddress.setText(prefs.getString(KEY_SERVER_URL, ""));
        switchToast.setChecked(prefs.getBoolean(KEY_SHOW_TOAST, true));
        switchAutoFeed.setChecked(prefs.getBoolean(KEY_AUTO_FEED, false));

        int savedAmount = prefs.getInt(KEY_FEED_AMOUNT, 5);
        int savedInterval = prefs.getInt(KEY_FEED_INTERVAL, 480); // 기본값 8시간 = 480분
        sliderAmount.setValue(savedAmount);
        editInterval.setText(String.valueOf(savedInterval));
        textAmountValue.setText(String.valueOf(savedAmount));

        // 자동 급식 모드에 따른 UI 업데이트
        updateIntervalContainerVisibility(switchAutoFeed.isChecked());

        switchAutoFeed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateIntervalContainerVisibility(isChecked);
            prefs.edit().putBoolean(KEY_AUTO_FEED, isChecked).apply();
        });

        switchToast.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SHOW_TOAST, isChecked).apply();
        });

        sliderAmount.addOnChangeListener((slider, value, fromUser) -> {
            textAmountValue.setText(String.valueOf((int) value));
        });

        btnSaveAddress.setOnClickListener(v -> {
            String rawUrl = editAddress.getText().toString().trim();
            if (rawUrl.isEmpty()) {
                Toast.makeText(this, "서버 주소를 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            String finalUrl = rawUrl.startsWith("ws://") || rawUrl.startsWith("wss://") ?
                    rawUrl : "wss://" + rawUrl;
            prefs.edit().putString(KEY_SERVER_URL, finalUrl).apply();
            Toast.makeText(this, "주소 저장 완료", Toast.LENGTH_SHORT).show();

            connectWebSocket(); // 저장 후 연결 시도
        });

        btnSaveFeedSetting.setOnClickListener(v -> {
            int amount = (int) sliderAmount.getValue();
            boolean auto = switchAutoFeed.isChecked();
            
            int interval = 0;
            if (auto) {
                String intervalText = editInterval.getText().toString().trim();
                if (intervalText.isEmpty()) {
                    Toast.makeText(this, "급식 간격을 입력해주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    interval = Integer.parseInt(intervalText);
                    if (interval < 1 || interval > 1440) {
                        Toast.makeText(this, "급식 간격은 1분에서 1440분(24시간) 사이여야 합니다.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "올바른 숫자를 입력해주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            prefs.edit()
                    .putInt(KEY_FEED_AMOUNT, amount)
                    .putInt(KEY_FEED_INTERVAL, interval)
                    .putBoolean(KEY_AUTO_FEED, auto)
                    .putString("mode", auto ? "auto" : "manual")
                    .apply();

            receivedAck = false;

            try {
                JSONObject json = new JSONObject();
                json.put("mode", auto ? "auto" : "manual");
                json.put("amount", amount);
                if (auto) {
                    json.put("interval", interval);
                }

                Log.d("SettingWS", "WebSocket 상태 확인 - wsClient: " + (wsClient != null) + 
                        ", isConnected: " + (wsClient != null ? wsClient.isConnected() : "N/A"));

                if (wsClient != null && wsClient.isConnected()) {
                    Log.d("SettingWS", "메시지 전송: " + json.toString());
                    wsClient.sendText(json.toString());

                    new Handler().postDelayed(() -> {
                        if (!receivedAck) {
                            Log.w("SettingWS", "서버 응답 타임아웃");
                            Toast.makeText(this, "서버에 전송을 완료했습니다.", Toast.LENGTH_SHORT).show();
                        }
                    }, RESPONSE_TIMEOUT_MS);
                } else {
                    Log.w("SettingWS", "WebSocket 연결 없음 - 재연결 시도");
                    connectWebSocket(); // 재연결 시도
                    Toast.makeText(this, "WebSocket 연결이 없습니다. 재연결을 시도합니다.", Toast.LENGTH_SHORT).show();
                }

            } catch (JSONException e) {
                Log.e("SettingWS", "JSON 생성 오류", e);
                e.printStackTrace();
            }
        });

        connectWebSocket(); // 앱 시작 시 연결
    }

    private void updateIntervalContainerVisibility(boolean isAutoMode) {
        if (isAutoMode) {
            intervalContainer.setVisibility(View.VISIBLE);
        } else {
            intervalContainer.setVisibility(View.GONE);
        }
    }

    private void connectWebSocket() {
        String url = prefs.getString(KEY_SERVER_URL, "");
        Log.d("SettingWS", "연결 시도 URL: " + url);
        
        if (url.isEmpty()) {
            Log.w("SettingWS", "서버 주소 없음");
            return;
        }

        if (wsClient != null) {
            Log.d("SettingWS", "기존 연결 해제");
            wsClient.disconnect();
        }

        Log.d("SettingWS", "새 WebSocket 연결 시도");
        wsClient = new WebSocketClient();
        wsClient.connect(url, new WebSocketClient.WebSocketListener() {
            @Override
            public void onOpen() {
                Log.i("SettingWS", "✅ WebSocket 연결 성공");
            }

            @Override
            public void onMessage(String message) {
                Log.d("SettingWS", "서버 응답: " + message);
                if (message.startsWith("ack:")) {
                    receivedAck = true;
                    mainHandler.post(() -> Toast.makeText(SettingActivity.this, message, Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onClose(int code, String reason) {
                Log.i("SettingWS", "WebSocket 연결 종료: " + reason);
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e("SettingWS", "❌ WebSocket 연결 실패: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wsClient != null) {
            wsClient.disconnect();
        }
    }
}

