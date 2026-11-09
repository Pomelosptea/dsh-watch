�1�3package com.zerotier.one.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Locale;

/**
 * Watch-sized ZeroTier launcher. It replaces the phone/tablet NetworkListActivity
 * but keeps the original package, manifest, VpnService and native node.
 *
 * Design goals:
 *  - one 466x466 round screen, no XML resources, no AppCompat dependency;
 *  - one primary action: connect / disconnect;
 *  - network ID is entered once and stored;
 *  - all status comes from the original ZeroTierOneService via its public API.
 */
public class NetworkListActivity extends Activity {

    private static final String SERVICE_CLASS = "com.zerotier.one.service.ZeroTierOneService";
    private static final String EXTRA_NETWORK_ID = "com.zerotier.one.network_id";
    private static final int REQ_VPN = 0x5A31;
    private static final int BG = 0xFF05060A;
    private static final int BLUE = 0xFF4D6BFE;
    private static final int CYAN = 0xFF79E2FF;
    private static final int GREEN = 0xFF63E6A6;
    private static final int RED = 0xFFFF7A8A;
    private static final int MUTED = 0xFF8B93AA;
    private static final int FAINT = 0xFF5B6377;

    private WatchView view;
    private Object service;
    private boolean bound;
    private long networkId;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int joinAttempts;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 900L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                Method getService = binder.getClass().getMethod("getService");
                getService.setAccessible(true);
                service = getService.invoke(binder);
                bound = true;
                if (networkId != 0L) {
                    joinWhenReady();
                }
                refresh();
            } catch (Throwable t) {
                view.setError("服务连接失败");
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            refresh();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        prefs = getSharedPreferences("zerotier_watch", MODE_PRIVATE);
        networkId = prefs.getLong("network_id", 0L);
        view = new WatchView();
        setContentView(view);

        Intent bind = new Intent();
        bind.setClassName(this, SERVICE_CLASS);
        try {
            bindService(bind, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            view.setError("无法绑定 ZeroTier 服务");
        }
        handler.post(poll);

        if (networkId != 0L && VpnService.prepare(this) == null) {
            // Already authorized: restore the tunnel quietly.
            handler.postDelayed(new Runnable() {
                @Override public void run() { startTunnel(); }
            }, 350L);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try { unbindService(connection); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startTunnel();
            } else {
                view.setError("霢�要允�?VPN 才能加入虚拟屢�域网");
            }
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void primaryAction() {
        if (networkId == 0L) {
            askForNetwork();
            return;
        }
        if (isTunnelRunning()) {
            disconnectTunnel();
        } else {
            connectTunnel();
        }
    }

    private void connectTunnel() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQ_VPN);
            } else {
                startTunnel();
            }
        } catch (Throwable t) {
            view.setError("系统不支�?VPNService");
        }
    }

    private void startTunnel() {
        try {
            Intent start = new Intent();
            start.setClassName(this, SERVICE_CLASS);
            start.putExtra(EXTRA_NETWORK_ID, networkId);
            startService(start);
            joinAttempts = 0;
            view.setBusy("正在启动�?);
            handler.postDelayed(new Runnable() {
                @Override public void run() { joinWhenReady(); }
            }, 600L);
        } catch (Throwable t) {
            view.setError("启动 ZeroTier 失败");
        }
    }

    private void joinWhenReady() {
        if (networkId == 0L) return;
        if (service == null) {
            if (joinAttempts++ < 8) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { joinWhenReady(); }
                }, 500L);
            }
            return;
        }
        try {
            Method join = service.getClass().getMethod("joinNetwork", long.class);
            join.invoke(service, networkId);
        } catch (Throwable ignored) {
        }
        refresh();
    }

    private void disconnectTunnel() {
        try {
            if (service != null) {
                Method stop = service.getClass().getMethod("stopZeroTier");
                stop.invoke(service);
            }
            Intent stop = new Intent();
            stop.setClassName(this, SERVICE_CLASS);
            stopService(stop);
        } catch (Throwable ignored) {
        }
        view.setBusy("正在断开�?);
        handler.postDelayed(new Runnable() {
            @Override public void run() { refresh(); }
        }, 500L);
    }

    private boolean isTunnelRunning() {
        if (service == null) return false;
        try {
            Method getNode = service.getClass().getMethod("getNode");
            Object node = getNode.invoke(service);
            return node != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        if (view == null) return;
        if (networkId == 0L) {
            view.setState("尚未加入网络", "", "", false, false);
            return;
        }
        String idText = formatId(networkId);
        if (service == null) {
            view.setState("未连�?, idText, "", false, false);
            return;
        }
        try {
            Method getNode = service.getClass().getMethod("getNode");
            Object node = getNode.invoke(service);
            if (node == null) {
                view.setState("未连�?, idText, "", false, false);
                return;
            }
            Method configsMethod = node.getClass().getMethod("networkConfigs");
            Object[] configs = (Object[]) configsMethod.invoke(node);
            if (configs == null) configs = new Object[0];
            Object match = null;
            if (networkId == 0L && configs.length > 0) {
                match = configs[0];
            } else {
                for (Object config : configs) {
                    Method getNwid = config.getClass().getMethod("getNwid");
                    long nwid = ((Number) getNwid.invoke(config)).longValue();
                    if (nwid == networkId) {
                        match = config;
                        break;
                    }
                }
            }
            if (match == null) {
                view.setState("正在获取配置", idText, "", false, false);
                return;
            }
            Method getStatus = match.getClass().getMethod("getStatus");
            Object status = getStatus.invoke(match);
            String statusName = String.valueOf(status);
            Method getAddresses = match.getClass().getMethod("getAssignedAddresses");
            Object[] addresses = (Object[]) getAddresses.invoke(match);
            String ip = "";
            if (addresses != null) {
                for (Object address : addresses) {
                    if (address instanceof InetSocketAddress) {
                        InetSocketAddress socket = (InetSocketAddress) address;
                        if (socket.getAddress() != null && socket.getAddress().getAddress().length == 4) {
                            ip = socket.getAddress().getHostAddress();
                            break;
                        }
                    }
                }
            }
            String label;
            boolean online;
            if (statusName.contains("OK")) {
                label = "已连�?;
                online = true;
            } else if (statusName.contains("REQUESTING")) {
                label = "正在获取配置";
                online = false;
            } else if (statusName.contains("AUTHENTICATION_REQUIRED")) {
                label = "等待 Central 授权";
                online = false;
            } else if (statusName.contains("ACCESS_DENIED")) {
                label = "访问被拒�?;
                online = false;
            } else if (statusName.contains("NOT_FOUND")) {
                label = "网络不存�?;
                online = false;
            } else if (statusName.contains("PORT_ERROR")) {
                label = "端口错误";
                online = false;
            } else {
                label = "连接�?;
                online = false;
            }
            view.setState(label, idText, ip, online, true);
        } catch (Throwable t) {
            view.setState("未连�?, idText, "", false, false);
        }
    }

    private void askForNetwork() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("16 位十六进制网�?ID");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(FAINT);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (networkId != 0L) input.setText(formatId(networkId));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ZeroTier 网络")
                .setMessage("输入 Central 里的 16 位网�?ID")
                .setView(input)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText().toString().replaceAll("[^0-9a-fA-F]", "");
            if (raw.length() != 16) {
                input.setError("必须�?16 位十六进�?);
                return;
            }
            try {
                networkId = Long.parseUnsignedLong(raw, 16);
                prefs.edit().putLong("network_id", networkId).apply();
                dialog.dismiss();
                view.setState("准备连接", formatId(networkId), "", false, false);
                connectTunnel();
            } catch (Throwable t) {
                input.setError("网络 ID 无效");
            }
        }));
        dialog.show();
    }

    private static String formatId(long id) {
        return String.format(Locale.US, "%016x", id);
    }

    private final class WatchView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String state = "未连�?;
        private String networkIdText = "";
        private String ip = "";
        private String error = "";
        private boolean online;
        private boolean hasNetwork;
        private long busySince;

        WatchView() {
            setBackgroundColor(BG);
            setFocusable(true);
        }

        void setState(String state, String id, String ip, boolean online, boolean hasNetwork) {
            this.state = state;
            this.networkIdText = id;
            this.ip = ip;
            this.online = online;
            this.hasNetwork = hasNetwork;
            this.error = "";
            invalidate();
        }

        void setBusy(String text) {
            this.state = text;
            this.error = "";
            this.busySince = System.currentTimeMillis();
            invalidate();
        }

        void setError(String text) {
            this.error = text;
            invalidate();
        }

        private float s() {
            return Math.min(getWidth() / 466f, getHeight() / 466f);
        }

        private float x(float base) {
            return getWidth() / 2f + (base - 233f) * s();
        }

        private float y(float base) {
            return getHeight() / 2f + (base - 233f) * s();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = s();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            // Deep navy glow, matching the DSH watch app.
            paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                    new int[]{BG, 0xFF0A1020, BG}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(online ? 0x2263E6A6 : 0x224D6BFE);
            canvas.drawCircle(cx, y(218), 118f * s, paint);

            // Outer ring.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * s);
            paint.setColor(0x33FFFFFF);
            canvas.drawCircle(cx, y(218), 92f * s, paint);
            paint.setColor(online ? 0xAA63E6A6 : 0x664D6BFE);
            canvas.drawCircle(cx, y(218), 84f * s, paint);

            // Rotating busy arc.
            if (state.startsWith("正在") || state.startsWith("准备")) {
                paint.setStrokeWidth(3f * s);
                paint.setColor(CYAN);
                float start = (System.currentTimeMillis() - busySince) / 6f % 360f;
                rect.set(cx - 84f * s, y(218) - 84f * s, cx + 84f * s, y(218) + 84f * s);
                canvas.drawArc(rect, start, 70f, false, paint);
                postInvalidateDelayed(60L);
            }

            // Title.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            paint.setTextSize(31f * s);
            canvas.drawText("ZeroTier", cx, y(70), paint);

            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            paint.setTextSize(15f * s);
            paint.setColor(MUTED);
            canvas.drawText("OPPO WATCH X2  ·  VIRTUAL LAN", cx, y(97), paint);

            // State.
            paint.setColor(online ? GREEN : Color.WHITE);
            paint.setTextSize(26f * s);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            canvas.drawText(state, cx, y(218), paint);

            paint.setColor(online ? GREEN : MUTED);
            paint.setTextSize(17f * s);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            canvas.drawText(ip.isEmpty() ? (online ? "虚拟屢�域网已就�? : "等待地址") : ip, cx, y(247), paint);

            // Network id.
            paint.setColor(FAINT);
            paint.setTextSize(14f * s);
            canvas.drawText("NETWORK", cx, y(307), paint);
            paint.setColor(hasNetwork ? MUTED : FAINT);
            paint.setTextSize(20f * s);
            canvas.drawText(networkIdText.isEmpty() ? "�?�?�? : networkIdText, cx, y(331), paint);

            // Primary button.
            float left = x(83);
            float right = x(383);
            float top = y(360);
            float bottom = y(414);
            rect.set(left, top, right, bottom);
            paint.setColor(online ? 0xFF202534 : BLUE);
            canvas.drawRoundRect(rect, 27f * s, 27f * s, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(20f * s);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            String action = hasNetwork ? (online ? "断开" : (state.startsWith("正在") || state.startsWith("准备") ? "请稍�? : "连接")) : "加入网络";
            canvas.drawText(action, cx, y(394), paint);

            // Secondary action.
            if (hasNetwork) {
                paint.setColor(FAINT);
                paint.setTextSize(14f * s);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
                canvas.drawText("更换网络 / 重新输入 ID", cx, y(441), paint);
            }

            if (!error.isEmpty()) {
                paint.setColor(RED);
                paint.setTextSize(12f * s);
                canvas.drawText(error, cx, y(454), paint);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float s = s();
                float bx = event.getX();
                float by = event.getY();
                if (bx >= x(70) && bx <= x(396) && by >= y(348) && by <= y(426)) {
                    if (hasNetwork && (state.startsWith("正在") || state.startsWith("准备"))) {
                        return true;
                    }
                    primaryAction();
                    return true;
                }
                if (hasNetwork && by >= y(427) && by <= y(462)) {
                    askForNetwork();
                    return true;
                }
            }
            return true;
        }
    }
}

