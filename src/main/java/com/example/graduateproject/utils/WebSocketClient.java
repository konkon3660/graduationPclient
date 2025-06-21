package com.example.graduateproject.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketClient {

    private static WebSocketClient instance;
    private WebSocket webSocket;
    private final OkHttpClient client = new OkHttpClient();
    private WebSocketListener listener;
    private boolean isConnected = false;

    public static WebSocketClient getInstance() {
        if (instance == null) {
            instance = new WebSocketClient();
        }
        return instance;
    }

    public void connect(String url, WebSocketListener listener) {
        this.listener = listener;
        this.isConnected = false;
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                isConnected = true;
                if (listener != null) {
                    listener.onOpen();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (listener != null) {
                    listener.onMessage(text);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (listener != null) {
                    listener.onMessage(bytes.utf8());
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                isConnected = false;
                if (listener != null) {
                    listener.onClose(code, reason);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                isConnected = false;
                if (listener != null) {
                    listener.onClose(code, reason);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                isConnected = false;
                if (listener != null) {
                    listener.onFailure(t);
                }
            }
        });
    }

    public void send(String string) {
        sendText(string);
    }

    public void sendText(String text) {
        if (webSocket != null) {
            webSocket.send(text);
        }
    }

    public void sendBytes(byte[] bytes) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(bytes));
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "Closed by client");
            isConnected = false;
        }
    }

    public void disconnect() {
        close();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public static abstract class WebSocketListener {
        public void onOpen() {}
        public void onMessage(String message) {}
        public void onClose(int code, String reason) {}
        public void onFailure(Throwable t) {}
    }
}
