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
import com.example.brokerfi.xc.net.OkhttpUtils;
import com.example.brokerfi.xc.net.RequestIdGenerator;
import com.example.brokerfi.xc.net.MyCallBack;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;

public class GameMainActivity extends AppCompatActivity {
    // 输入控件（玩家数、质押额、小丑牌数）
    private EditText etMinPlayers, etMaxPlayers, etMinStake, etMaxStake, etJokerCount;
    private Button btnCreateGame, btnJoinGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        // 绑定控件
        etMinPlayers = findViewById(R.id.et_min_players);
        etMaxPlayers = findViewById(R.id.et_max_players);
        etMinStake = findViewById(R.id.et_min_stake);
        etMaxStake = findViewById(R.id.et_max_stake);
        etJokerCount = findViewById(R.id.et_joker_count);
        btnCreateGame = findViewById(R.id.btn_create_game);
        btnJoinGame = findViewById(R.id.btn_join_game);

        // 默认值（40张数字牌+1张小丑牌）
        etMinPlayers.setText("2");
        etMaxPlayers.setText("4");
        etMinStake.setText("10"); // 最小质押10 BKC
        etMaxStake.setText("100"); // 最大质押100 BKC
        etJokerCount.setText("1");

        // 创建游戏按钮事件
        btnCreateGame.setOnClickListener(v -> createGameRoom());

        // 加入游戏按钮事件（跳转至加入页面）
        btnJoinGame.setOnClickListener(v -> {
            Intent intent = new Intent(GameMainActivity.this, GameJoinActivity.class);
            startActivity(intent);
        });
    }

    // 创建游戏房间（调用GameFactory合约）
    private void createGameRoom() {
        try {
            // 1. 获取用户输入的配置
            int minPlayers = Integer.parseInt(etMinPlayers.getText().toString().trim());
            int maxPlayers = Integer.parseInt(etMaxPlayers.getText().toString().trim());
            BigInteger minStake = new BigInteger(etMinStake.getText().toString().trim())
                    .multiply(BigInteger.TEN.pow(18)); // 转换为wei单位（1 BKC = 1e18 wei）
            BigInteger maxStake = new BigInteger(etMaxStake.getText().toString().trim())
                    .multiply(BigInteger.TEN.pow(18));
            int jokerCount = Integer.parseInt(etJokerCount.getText().toString().trim());

            // 2. 数字牌配置（4个1-10，共40张，对应cardCounts[0]到cardCounts[9]）
            BigInteger[] cardCounts = new BigInteger[10];
            for (int i = 0; i < 10; i++) {
                cardCounts[i] = BigInteger.valueOf(4); // 每个数字4张牌
            }

            // 3. 编码合约调用数据（ABI编码）
            String data = ABIUtils.encodeCreateGameRoom(
                    minPlayers, maxPlayers, minStake, maxStake, cardCounts, jokerCount
            );

            // 4. 构造ETH RPC交易请求（eth_sendTransaction）
            JSONObject txParams = new JSONObject();
            txParams.put("from", getCurrentAccount()); // 从钱包获取当前账户地址
            txParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            txParams.put("data", data);
            txParams.put("value", "0x0"); // 创建游戏无需质押BKC
            txParams.put("gas", "0x600000"); // 足够的gas限制

            JSONArray params = new JSONArray();
            params.put(txParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_sendTransaction");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            // 5. 发送请求到BrokerChain节点
            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject response = new JSONObject(result);
                        if (response.has("result")) {
                            String txHash = response.getString("result");
                            runOnUiThread(() -> {
                                Toast.makeText(GameMainActivity.this, "创建游戏成功！交易哈希：" + txHash, Toast.LENGTH_LONG).show();
                                // 跳转至游戏房间等待页面
                                Intent intent = new Intent(GameMainActivity.this, GameRoomWaitActivity.class);
                                intent.putExtra("txHash", txHash);
                                startActivity(intent);
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "创建失败：" + response.optString("error"), Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "输入参数错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 从钱包获取当前选中的账户地址（复用钱包已有逻辑）
    private String getCurrentAccount() {
        return StorageUtil.getCurrentAccount(this); // 钱包已实现的账户存储工具
    }
}
