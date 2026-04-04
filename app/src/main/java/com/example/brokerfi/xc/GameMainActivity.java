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
import java.math.BigInteger;
import java.math.BigDecimal;

public class GameMainActivity extends AppCompatActivity {
    private static final String TAG = "GameMainActivity";
    private EditText etMinPlayers, etMaxPlayers, etMinStake, etMaxStake, etJokerCount;
    private Button btnCreateGame, btnJoinGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);
        Log.d(TAG, "========== 页面创建完成 ==========");

        // 绑定控件
        etMinPlayers = findViewById(R.id.et_min_players);
        etMaxPlayers = findViewById(R.id.et_max_players);
        etMinStake = findViewById(R.id.et_min_stake);
        etMaxStake = findViewById(R.id.et_max_stake);
        etJokerCount = findViewById(R.id.et_joker_count);
        btnCreateGame = findViewById(R.id.btn_create_game);
        btnJoinGame = findViewById(R.id.btn_join_game);
        Log.d(TAG, "控件绑定完成");

        // 默认值适配合约（2-8人，小丑牌最大8张）
        etMinPlayers.setText("2");
        etMaxPlayers.setText("4");
        etMinStake.setText("1"); // 1 BKC
        etMaxStake.setText("100"); // 100 BKC
        etJokerCount.setText("1");
        Log.d(TAG, "输入框默认值已设置");

        // 创建游戏按钮事件
        btnCreateGame.setOnClickListener(v -> {
            Log.d(TAG, "========== 点击【创建游戏】按钮 ==========");
            createGameRoom();
        });

        // 加入游戏按钮事件
        btnJoinGame.setOnClickListener(v -> {
            Log.d(TAG, "========== 点击【加入游戏】按钮 ==========");
            Intent intent = new Intent(GameMainActivity.this, GameJoinActivity.class);
            startActivity(intent);
        });
    }

    // 创建游戏房间（适配新GameFactory合约）
    private void createGameRoom() {
        try {
            Log.i(TAG, "开始执行创建游戏逻辑...");

            // 1. 获取用户输入的配置
            String minPlayersStr = etMinPlayers.getText().toString().trim();
            String maxPlayersStr = etMaxPlayers.getText().toString().trim();
            String minStakeStr = etMinStake.getText().toString().trim();
            String maxStakeStr = etMaxStake.getText().toString().trim();
            String jokerCountStr = etJokerCount.getText().toString().trim();

            Log.d(TAG, "用户原始输入：");
            Log.d(TAG, "最小玩家数：" + minPlayersStr);
            Log.d(TAG, "最大玩家数：" + maxPlayersStr);
            Log.d(TAG, "最小质押(BKC)：" + minStakeStr);
            Log.d(TAG, "最大质押(BKC)：" + maxStakeStr);
            Log.d(TAG, "小丑牌数量：" + jokerCountStr);

            // 2. 解析参数（适配合约类型）
            BigInteger minPlayers = new BigInteger(minPlayersStr);
            BigInteger maxPlayers = new BigInteger(maxPlayersStr);
            BigInteger minStake = toWei(minStakeStr);
            BigInteger maxStake = toWei(maxStakeStr);
            BigInteger jokerCount = new BigInteger(jokerCountStr);

            // 3. 卡牌配置（合约要求偶数张，最大20张/数字）
            BigInteger[] cardCounts = new BigInteger[10];
            for (int i = 0; i < 10; i++) {
                cardCounts[i] = BigInteger.valueOf(4); // 每个数字4张（偶数，符合合约要求）
                Log.d(TAG, "卡牌" + (i+1) + "数量：" + cardCounts[i]);
            }

            Log.i(TAG, "参数解析完成：");
            Log.i(TAG, "minPlayers = " + minPlayers);
            Log.i(TAG, "maxPlayers = " + maxPlayers);
            Log.i(TAG, "minStake = " + minStake);
            Log.i(TAG, "maxStake = " + maxStake);
            Log.i(TAG, "jokerCount = " + jokerCount);

            // 4. ABI编码（适配新createGameRoom参数）
            Log.i(TAG, "开始执行 ABI 编码...");
            String data = ABIUtils.encodeCreateGameRoomV2(
                    minPlayers, maxPlayers, minStake, maxStake, jokerCount, cardCounts,
                    GameConfig.VRF_COORDINATOR_ADDRESS,
                    GameConfig.VRF_SUBSCRIPTION_ID,
                    GameConfig.VRF_KEY_HASH_HEX,
                    GameConfig.VRF_CALLBACK_GAS_LIMIT,
                    GameConfig.VRF_REQUEST_CONFIRMATIONS
            );
            Log.i(TAG, "ABI 编码完成，data = " + data);

            // 5. 构造ETH RPC交易请求
            String currentAccount = getCurrentWalletAddress();
            Log.i(TAG, "当前钱包账户：" + currentAccount);
            Log.i(TAG, "游戏工厂合约地址：" + GameConfig.GAME_FACTORY_ADDRESS);

            JSONObject txParams = new JSONObject();
            txParams.put("from", currentAccount);
            txParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            txParams.put("data", data);
            txParams.put("value", "0x0"); // 创建游戏无需质押
            txParams.put("gas", "0x800000"); // 提高gas适配合约

            JSONArray params = new JSONArray();
            params.put(txParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_sendTransaction");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            Log.i(TAG, "RPC 请求体：" + request.toString());

            // 6. 发送请求到BrokerChain节点
            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "========== 创建游戏请求成功 ==========");
                    Log.d(TAG, "原始响应：" + result);

                    try {
                        JSONObject response = new JSONObject(result);
                        if (response.has("result")) {
                            String txHash = response.getString("result");
                            Log.i(TAG, "创建游戏交易提交成功！Hash：" + txHash);
                            // 查询交易收据获取gameId和room地址
                            queryGameRoomInfo(txHash);

                            runOnUiThread(() -> {
                                Toast.makeText(GameMainActivity.this, "创建游戏交易已提交！Hash：" + txHash, Toast.LENGTH_LONG).show();
                            });
                        } else {
                            String errorMsg = response.optString("error");
                            Log.e(TAG, "创建游戏失败：" + errorMsg);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "创建失败：" + errorMsg, Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析响应异常：", e);
                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "创建游戏网络错误：", e);
                    runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "创建游戏整体异常：", e);
            Toast.makeText(this, "参数错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 查询交易收据获取gameId和room地址
    private void queryGameRoomInfo(String txHash) {
        try {
            Log.i(TAG, "========== 查询游戏房间信息 ==========");
            Log.i(TAG, "交易Hash：" + txHash);

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
                        Log.d(TAG, "交易收据响应：" + result);
                        JSONObject res = new JSONObject(result);
                        JSONObject receipt = res.getJSONObject("result");
                        JSONArray logs = receipt.getJSONArray("logs");

                        // 解析GameRoomCreated事件获取gameId和room地址
                        for (int i = 0; i < logs.length(); i++) {
                            JSONObject log = logs.getJSONObject(i);
                            String topic0 = log.getJSONArray("topics").getString(0);
                            // 匹配GameRoomCreated事件签名哈希
                            if (topic0.equals("0x" + ABIUtils.getEventSignatureHash("GameRoomCreated(uint256,address,address,(uint256,uint256,uint256,uint256,uint256,uint256[10]))"))) {
                                String gameIdHex = log.getJSONArray("topics").getString(1);
                                String hostHex = log.getJSONArray("topics").getString(2);
                                String roomHex = log.getJSONArray("topics").getString(3);

                                BigInteger gameId = new BigInteger(gameIdHex.substring(2), 16);
                                String roomAddress = "0x" + roomHex.substring(26);
                                String hostAddress = "0x" + hostHex.substring(26);

                                Log.i(TAG, "解析到游戏信息：");
                                Log.i(TAG, "gameId：" + gameId);
                                Log.i(TAG, "roomAddress：" + roomAddress);
                                Log.i(TAG, "host：" + hostAddress);

                                // 跳转至等待页面
                                runOnUiThread(() -> {
                                    Intent intent = new Intent(GameMainActivity.this, GameRoomWaitActivity.class);
                                    intent.putExtra("txHash", txHash);
                                    intent.putExtra("gameId", gameId.toString());
                                    intent.putExtra("roomAddress", roomAddress);
                                    startActivity(intent);
                                });
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析游戏信息异常：", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "查询游戏信息网络错误：", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询游戏信息异常：", e);
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