package com.example.brokerfi.xc;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.brokerfi.R;
import com.example.brokerfi.config.GameConfig;
import com.example.brokerfi.xc.net.ABIUtils;
import com.example.brokerfi.xc.net.OkhttpUtils;
import com.example.brokerfi.xc.net.RequestIdGenerator;
import com.example.brokerfi.xc.net.MyCallBack;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;

public class GameRoomWaitActivity extends AppCompatActivity {

    // 控件
    private TextView tvGameId, tvGameState, tvPlayerCount, tvStakeAmount, tvWaitTip;
    private Button btnStartGame;

    // 游戏信息
    private String txHash;
    private BigInteger gameId;
    private String roomAddress;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isWaiting = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_room_wait);

        // 绑定控件
        tvGameId = findViewById(R.id.tv_game_id);
        tvGameState = findViewById(R.id.tv_game_state);
        tvPlayerCount = findViewById(R.id.tv_player_count);
        tvStakeAmount = findViewById(R.id.tv_stake_amount);
        tvWaitTip = findViewById(R.id.tv_wait_tip);
        btnStartGame = findViewById(R.id.btn_start_game);

        // 获取上一页传过来的交易哈希
        Intent intent = getIntent();
        txHash = intent.getStringExtra("txHash");

        // 初始化：从交易哈希获取游戏ID
        getGameIdFromTx();

        // 开始游戏按钮（房主可用）
        btnStartGame.setOnClickListener(v -> startGame());

        // 定时刷新房间状态
        startRefreshLoop();
    }

    /**
     * 从交易哈希解析出游戏ID
     */
    private void getGameIdFromTx() {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getTransactionReceipt");
            JSONArray params = new JSONArray();
            params.put(txHash);
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("result")) {
                            JSONObject receipt = res.getJSONObject("result");
                            String logs = receipt.getJSONArray("logs").toString();
                            // 从日志解析 gameId（你合约的事件输出）
                            gameId = new BigInteger("1"); // 临时，正式版从事件解析
                            runOnUiThread(() -> tvGameId.setText("游戏ID：" + gameId.toString()));
                            // 获取房间地址
                            getGameRoomAddress();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 获取游戏房间地址
     */
    private void getGameRoomAddress() {
        try {
            String data = ABIUtils.encodeGetGameRoom(gameId);
            JSONObject params = new JSONObject();
            params.put("from", StorageUtil.getCurrentAccount(this));
            params.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            params.put("data", data);
            params.put("value", "0x0");

            JSONArray jsonArray = new JSONArray();
            jsonArray.put(params);

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_call");
            req.put("params", jsonArray);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        roomAddress = ABIUtils.decodeAddress(res.getString("result"));
                        runOnUiThread(() -> {
                            Toast.makeText(GameRoomWaitActivity.this, "房间已创建", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 定时刷新房间信息
     */
    private void startRefreshLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isWaiting) return;
                refreshRoomInfo();
                handler.postDelayed(this, 2000); // 2秒刷新一次
            }
        }, 1000);
    }

    /**
     * 刷新房间：玩家数、状态、质押
     */
    private void refreshRoomInfo() {
        // 你已有成熟的合约调用逻辑
        runOnUiThread(() -> {
            tvGameState.setText("状态：等待加入");
            tvPlayerCount.setText("当前玩家：1/4");
            tvStakeAmount.setText("单局质押：10 BKC");
        });
    }

    /**
     * 房主：开始游戏
     */
    private void startGame() {
        btnStartGame.setEnabled(false);
        isWaiting = false;

        // 跳转到游戏对局页面
        Intent intent = new Intent(GameRoomWaitActivity.this, GameBattleActivity.class);
        intent.putExtra("roomAddress", roomAddress);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isWaiting = false;
        handler.removeCallbacksAndMessages(null);
    }
}