package com.example.brokerfi.xc;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
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
import java.math.BigDecimal;
import java.math.BigInteger;

public class GameJoinActivity extends AppCompatActivity {
    private static final String TAG = "GameJoin_DEBUG";
    private EditText etGameId, etStakeAmount;
    private Button btnJoin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_join);
        Log.i(TAG, "========== 加入游戏页面创建 ==========");

        etGameId = findViewById(R.id.et_game_id);
        etStakeAmount = findViewById(R.id.et_stake_amount);
        btnJoin = findViewById(R.id.btn_join);

        // 默认质押1 BKC
        etStakeAmount.setText("1");
        Log.d(TAG, "默认质押金额：1 BKC");

        btnJoin.setOnClickListener(v -> joinGame());
    }

    private void joinGame() {
        try {
            Log.i(TAG, "========== 开始加入游戏流程 ==========");

            // 1. 获取输入参数
            String gameIdStr = etGameId.getText().toString().trim();
            String stakeStr = etStakeAmount.getText().toString().trim();

            if (gameIdStr.isEmpty() || stakeStr.isEmpty()) {
                Toast.makeText(this, "游戏ID和质押金额不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d(TAG, "输入游戏ID：" + gameIdStr);
            Log.d(TAG, "输入质押金额(wei)：" + stakeStr);

            BigInteger gameId = new BigInteger(gameIdStr);
            BigInteger stakeAmount = toWei(stakeStr);
            Log.i(TAG, "✅ 参数解析完成：gameId=" + gameId + ", stake=" + stakeAmount);

            // 2. 查询游戏房间地址（调用GameFactory.getGameRoom）
            queryGameRoomAddress(gameId, stakeAmount);

        } catch (Exception e) {
            Log.e(TAG, "加入游戏参数异常：", e);
            runOnUiThread(() -> Toast.makeText(this, "参数错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // 查询游戏房间地址
    private void queryGameRoomAddress(BigInteger gameId, BigInteger stakeAmount) {
        try {
            Log.i(TAG, "========== 查询游戏房间地址 ==========");
            // ABI编码getGameRoom调用
            String data = ABIUtils.encodeGetGameRoom(gameId);
            Log.d(TAG, "getGameRoom call data：" + data);

            JSONObject callParams = new JSONObject();
            callParams.put("from", getCurrentWalletAddress());
            callParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            callParams.put("data", data);
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_call");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            Log.d(TAG, "eth_call请求：" + request.toString());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        Log.i(TAG, "getGameRoom返回：" + result);
                        JSONObject response = new JSONObject(result);

                        if (!response.has("result") || response.isNull("result")) {
                            Log.e(TAG, "❌ 无返回结果");
                            runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "获取房间失败", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        // 解析返回的房间地址
                        String roomAddress = ABIUtils.decodeAddress(response.getString("result"));
                        Log.i(TAG, "✅ 解析房间地址：" + roomAddress);

                        if (roomAddress.equals("0x0000000000000000000000000000000000000000")) {
                            Log.e(TAG, "❌ 房间地址为空，游戏不存在");
                            runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "游戏房间不存在", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        // 3. 调用GameRoom.joinGame（带质押金额）
                        joinGameRoom(roomAddress, gameId, stakeAmount);

                    } catch (Exception e) {
                        Log.e(TAG, "解析房间地址失败：", e);
                        runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "解析房间地址失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "查询房间网络错误：", e);
                    runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询房间异常：", e);
        }
    }

    // 调用GameRoom.joinGame（带质押）
    private void joinGameRoom(String roomAddress, BigInteger gameId, BigInteger stakeAmount) {
        try {
            Log.i(TAG, "========== 发送加入游戏交易 ==========");
            Log.i(TAG, "房间地址：" + roomAddress);
            Log.i(TAG, "游戏ID：" + gameId);
            Log.i(TAG, "质押金额(wei)：" + stakeAmount);

            // ABI编码joinGame（无参数，质押通过value传递）
            String data = ABIUtils.encodeJoinGameV2();
            Log.d(TAG, "joinGame data：" + data);

            JSONObject txParams = new JSONObject();
            txParams.put("from", getCurrentWalletAddress());
            txParams.put("to", roomAddress);
            txParams.put("data", data);
            txParams.put("value", "0x" + stakeAmount.toString(16)); // 质押金额
            txParams.put("gas", "0x800000"); // 提高gas上限

            JSONArray params = new JSONArray();
            params.put(txParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_sendTransaction");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            Log.d(TAG, "发送交易请求：" + request.toString());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        Log.i(TAG, "加入游戏交易返回：" + result);
                        JSONObject response = new JSONObject(result);

                        if (response.has("result")) {
                            String txHash = response.getString("result");
                            Log.i(TAG, "✅ 加入游戏交易提交成功！Hash：" + txHash);

                            runOnUiThread(() -> {
                                Toast.makeText(GameJoinActivity.this, "加入游戏交易已提交！Hash：" + txHash, Toast.LENGTH_LONG).show();
                                // 跳转至游戏等待页面
                                Intent intent = new Intent(GameJoinActivity.this, GameRoomWaitActivity.class);
                                intent.putExtra("gameId", gameId.toString());
                                intent.putExtra("roomAddress", roomAddress);
                                startActivity(intent);
                            });
                        } else {
                            String errorMsg = response.optJSONObject("error").optString("message");
                            Log.e(TAG, "❌ 加入游戏失败：" + errorMsg);
                            runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "加入失败：" + errorMsg, Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析交易结果异常：", e);
                        runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "加入游戏网络错误：", e);
                    runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "发送加入交易异常：", e);
            runOnUiThread(() -> Toast.makeText(this, "发送交易失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // 获取当前钱包地址
    private String getCurrentWalletAddress() {
        try {
            String acc = StorageUtil.getCurrentAccount(this);
            int index = (acc == null) ? 0 : Integer.parseInt(acc);
            String allKeys = StorageUtil.getPrivateKey(this);
            String[] keys = allKeys.split(";");
            String address = SecurityUtil.GetAddress(keys[index]);
            if (!address.startsWith("0x")) {
                address = "0x" + address;
            }
            Log.d(TAG, "当前钱包地址：" + address);
            return address;
        } catch (Exception e) {
            Log.e(TAG, "获取钱包地址失败：", e);
            return "";
        }
    }

    private BigInteger toWei(String bkcStr) {
        BigDecimal bkc = new BigDecimal(bkcStr);
        BigDecimal wei = bkc.multiply(new BigDecimal("1000000000000000000"));
        return wei.toBigInteger();
    }
}