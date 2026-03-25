package com.example.brokerfi.xc;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.example.brokerfi.R;
import com.example.brokerfi.config.GameConfig;
import com.example.brokerfi.xc.net.ABIUtils;
import com.example.brokerfi.xc.net.MyCallBack;
import com.example.brokerfi.xc.net.OkhttpUtils;
import com.example.brokerfi.xc.net.RequestIdGenerator;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;

public class GameJoinActivity extends AppCompatActivity {
    private EditText etGameId, etStakeAmount;
    private Button btnJoin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_join);

        etGameId = findViewById(R.id.et_game_id);
        etStakeAmount = findViewById(R.id.et_stake_amount);
        btnJoin = findViewById(R.id.btn_join);

        // 默认质押10 BKC
        etStakeAmount.setText("10");

        btnJoin.setOnClickListener(v -> joinGame());
    }

    private void joinGame() {
        try {
            // 1. 获取输入参数
            long gameId = Long.parseLong(etGameId.getText().toString().trim());
            BigInteger stakeAmount = new BigInteger(etStakeAmount.getText().toString().trim())
                    .multiply(BigInteger.TEN.pow(18)); // 转换为wei单位

            // 2. 先通过GameFactory获取游戏房间地址
            String getRoomData = ABIUtils.encodeGetGameRoom(BigInteger.valueOf(gameId));
            JSONObject getRoomParams = new JSONObject();
            getRoomParams.put("from", getCurrentAccount());
            getRoomParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            getRoomParams.put("data", getRoomData);
            getRoomParams.put("value", "0x0");

            JSONArray getRoomJsonParams = new JSONArray();
            getRoomJsonParams.put(getRoomParams);

            JSONObject getRoomRequest = new JSONObject();
            getRoomRequest.put("jsonrpc", "2.0");
            getRoomRequest.put("method", "eth_call");
            getRoomRequest.put("params", getRoomJsonParams);
            getRoomRequest.put("id", RequestIdGenerator.getNextId());

            // 3. 调用eth_call获取游戏房间地址
            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, getRoomRequest.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject response = new JSONObject(result);
                        if (response.has("result")) {
                            String roomAddress = ABIUtils.decodeAddress(response.getString("result"));
                            if (roomAddress.equals("0x0000000000000000000000000000000000000000")) {
                                runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "游戏房间不存在", Toast.LENGTH_SHORT).show());
                                return;
                            }
                            // 4. 调用GameRoom的joinGame函数（带质押金额）
                            joinGameRoom(roomAddress, stakeAmount);
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

    // 调用GameRoom的joinGame函数（质押BKC）
    private void joinGameRoom(String roomAddress, BigInteger stakeAmount) {
        try {
            String data = ABIUtils.encodeJoinGame(); // joinGame无参数，仅需函数选择器
            JSONObject txParams = new JSONObject();
            txParams.put("from", getCurrentAccount());
            txParams.put("to", roomAddress);
            txParams.put("data", data);
            txParams.put("value", "0x" + stakeAmount.toString(16)); // 质押金额（wei）
            txParams.put("gas", "0x600000");

            JSONArray params = new JSONArray();
            params.put(txParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_sendTransaction");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject response = new JSONObject(result);
                        if (response.has("result")) {
                            runOnUiThread(() -> {
                                Toast.makeText(GameJoinActivity.this, "加入游戏成功！等待游戏开始", Toast.LENGTH_LONG).show();
                                // 跳转至游戏对局页面
                                Intent intent = new Intent(GameJoinActivity.this, GameBattleActivity.class);
                                intent.putExtra("roomAddress", roomAddress);
                                startActivity(intent);
                            });
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

    private String getCurrentAccount() {
        return StorageUtil.getCurrentAccount(this);
    }
}