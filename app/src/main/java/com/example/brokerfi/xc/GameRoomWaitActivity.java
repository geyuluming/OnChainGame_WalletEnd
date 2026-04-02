package com.example.brokerfi.xc;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.brokerfi.R;
import com.example.brokerfi.config.GameConfig;
import com.example.brokerfi.xc.net.ABIUtils;
import com.example.brokerfi.xc.net.MyCallBack;
import com.example.brokerfi.xc.net.OkhttpUtils;
import com.example.brokerfi.xc.net.RequestIdGenerator;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class GameRoomWaitActivity extends AppCompatActivity {
    private static final String TAG = "GameRoomWait_FINAL";

    private TextView tvGameId, tvGameState, tvPlayerCount, tvStakeAmount, tvWaitTip, tvPlayerList;
    private Button btnStartGame;

    private String txHash;
    private BigInteger gameId;
    private String roomAddress;
    private String hostAddress;

    // 玩家数据（适配合约Player结构）
    private List<String> playerList = new ArrayList<>();
    private Map<String, BigInteger> playerStakeMap = new HashMap<>();
    private BigInteger totalStake = BigInteger.ZERO;
    private String gameStatus = "等待玩家";
    private int minPlayers = 2;
    private int maxPlayers = 4;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isWaiting = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_room_wait);
        Log.i(TAG, "========== 游戏等待页面创建 ==========");

        // 绑定控件
        tvGameId = findViewById(R.id.tv_game_id);
        tvGameState = findViewById(R.id.tv_game_state);
        tvPlayerCount = findViewById(R.id.tv_player_count);
        tvStakeAmount = findViewById(R.id.tv_stake_amount);
        tvWaitTip = findViewById(R.id.tv_wait_tip);
        tvPlayerList = findViewById(R.id.tv_player_list);
        btnStartGame = findViewById(R.id.btn_start_game);

        // 获取传递的参数
        txHash = getIntent().getStringExtra("txHash");
        String gameIdStr = getIntent().getStringExtra("gameId");
        roomAddress = getIntent().getStringExtra("roomAddress");
        hostAddress = getCurrentWalletAddress();

        Log.d(TAG, "页面参数：");
        Log.d(TAG, "txHash：" + txHash);
        Log.d(TAG, "gameId：" + gameIdStr);
        Log.d(TAG, "roomAddress：" + roomAddress);
        Log.d(TAG, "host：" + hostAddress);

        // 初始化gameId
        if (gameIdStr != null) {
            gameId = new BigInteger(gameIdStr);
            tvGameId.setText("游戏ID：" + gameId);
        } else if (txHash != null) {
            getGameInfoFromTx(); // 从交易哈希解析gameId
        } else {
            Toast.makeText(this, "游戏ID为空", Toast.LENGTH_SHORT).show();
            finish();
        }

        // 初始化按钮状态
        btnStartGame.setEnabled(false);
        btnStartGame.setOnClickListener(v -> startGame());

        // 查询游戏配置（min/max玩家数）
        queryGameConfig();

        // 启动事件监听
        startEventSyncLoop();
    }

    // 从交易收据解析游戏信息
    private void getGameInfoFromTx() {
        try {
            Log.i(TAG, "========== 从交易哈希解析游戏信息 ==========");
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
                        Log.d(TAG, "交易收据：" + result);
                        JSONObject res = new JSONObject(result);
                        JSONObject receipt = res.getJSONObject("result");
                        JSONArray logs = receipt.getJSONArray("logs");

                        // 解析GameRoomCreated事件
                        for (int i = 0; i < logs.length(); i++) {
                            JSONObject log = logs.getJSONObject(i);
                            JSONArray topics = log.getJSONArray("topics");
                            if (topics.length() < 4) continue;

                            gameId = new BigInteger(topics.getString(1).substring(2), 16);
                            hostAddress = "0x" + topics.getString(2).substring(26);
                            roomAddress = "0x" + topics.getString(3).substring(26);

                            Log.i(TAG, "解析交易获取：");
                            Log.i(TAG, "gameId：" + gameId);
                            Log.i(TAG, "host：" + hostAddress);
                            Log.i(TAG, "roomAddress：" + roomAddress);

                            runOnUiThread(() -> {
                                tvGameId.setText("游戏ID：" + gameId);
                                Toast.makeText(GameRoomWaitActivity.this, "房间信息加载完成", Toast.LENGTH_SHORT).show();
                            });
                            break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析交易信息异常：", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "获取交易收据失败：", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "解析交易信息异常：", e);
        }
    }

    // 查询游戏配置（min/max玩家数、质押范围）
    private void queryGameConfig() {
        try {
            Log.i(TAG, "========== 查询游戏配置 ==========");
            if (gameId == null) return;

            // 调用GameFactory.getGameConfigs
            String data = ABIUtils.encodeGetGameConfig(gameId);
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
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

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        Log.d(TAG, "游戏配置返回：" + result);
                        JSONObject res = new JSONObject(result);
                        String configData = res.getString("result");

                        // 解析GameConfig
                        minPlayers = Integer.parseInt(ABIUtils.decodeUint256(configData, 0).toString());
                        maxPlayers = Integer.parseInt(ABIUtils.decodeUint256(configData, 1).toString());
                        BigInteger minStake = ABIUtils.decodeUint256(configData, 2);
                        BigInteger maxStake = ABIUtils.decodeUint256(configData, 3);
                        int jokerCount = Integer.parseInt(ABIUtils.decodeUint256(configData, 4).toString());

                        Log.i(TAG, "游戏配置解析完成：");
                        Log.i(TAG, "minPlayers：" + minPlayers);
                        Log.i(TAG, "maxPlayers：" + maxPlayers);
                        Log.i(TAG, "minStake：" + fromWei(minStake) + " BKC");
                        Log.i(TAG, "maxStake：" + fromWei(maxStake) + " BKC");
                        Log.i(TAG, "jokerCount：" + jokerCount);

                        runOnUiThread(() -> {
                            tvWaitTip.setText("等待至少" + minPlayers + "名玩家加入（最大" + maxPlayers + "人）");
                            updateUI();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "解析游戏配置异常：", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "查询游戏配置失败：", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询游戏配置异常：", e);
        }
    }

    // 启动事件同步循环（监听PlayerJoined和GameStarted事件）
    private void startEventSyncLoop() {
        handler.postDelayed(() -> {
            if (!isWaiting) return;

            Log.d(TAG, "========== 同步游戏事件 ==========");
            // 1. 监听PlayerJoined事件
            fetchPlayerJoinedEvents();
            // 2. 监听GameStarted事件
            fetchGameStartedEvents();
            // 3. 更新UI
            updateUI();

            handler.postDelayed(this::startEventSyncLoop, 3000); // 每3秒同步一次
        }, 1000);
    }

    // 监听PlayerJoined事件
    private void fetchPlayerJoinedEvents() {
        try {
            if (roomAddress == null || gameId == null) return;

            Log.d(TAG, "监听PlayerJoined事件，room：" + roomAddress + ", gameId：" + gameId);

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", roomAddress);
            JSONArray topics = new JSONArray();
            // PlayerJoined事件签名：PlayerJoined(uint256,address,uint256)
            topics.put("0x" + ABIUtils.getEventSignatureHash("PlayerJoined(uint256,address,uint256)"));
            topics.put("0x" + String.format("%064x", gameId)); // gameId索引

            filter.put("topics", topics);
            filter.put("fromBlock", "0x0");
            filter.put("toBlock", "latest");

            JSONArray params = new JSONArray();
            params.put(filter);
            req.put("params", params);
            req.put("id", 999);

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        Log.d(TAG, "PlayerJoined事件返回：" + result);
                        JSONObject res = new JSONObject(result);
                        JSONArray logs = res.getJSONArray("result");

                        // 重置玩家数据
                        playerList.clear();
                        playerStakeMap.clear();
                        totalStake = BigInteger.ZERO;

                        // 解析所有PlayerJoined事件
                        for (int i = 0; i < logs.length(); i++) {
                            JSONObject log = logs.getJSONObject(i);
                            JSONArray tp = log.getJSONArray("topics");
                            String data = log.getString("data");

                            // 解析玩家地址和质押金额
                            String player = "0x" + tp.getString(2).substring(26);
                            BigInteger stake = new BigInteger(data.substring(2), 16);

                            // 添加玩家
                            if (!playerList.contains(player)) {
                                playerList.add(player);
                                playerStakeMap.put(player, stake);
                                totalStake = totalStake.add(stake);
                                Log.d(TAG, "玩家加入：" + player + "，质押：" + fromWei(stake) + " BKC");
                            }
                        }

                        Log.i(TAG, "当前玩家数：" + playerList.size() + "，总质押：" + fromWei(totalStake) + " BKC");

                        refreshStakesFromVault();

                    } catch (Exception e) {
                        Log.e(TAG, "解析PlayerJoined事件异常：", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "获取PlayerJoined事件失败：", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "监听PlayerJoined事件异常：", e);
        }
    }

    // 监听GameStarted事件
    private void fetchGameStartedEvents() {
        try {
            if (roomAddress == null || gameId == null) return;

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", roomAddress);
            JSONArray topics = new JSONArray();
            // GameStarted事件签名：GameStarted(uint256)
            topics.put("0x" + ABIUtils.getEventSignatureHash("GameStarted(uint256)"));
            topics.put("0x" + String.format("%064x", gameId)); // gameId索引

            filter.put("topics", topics);
            filter.put("fromBlock", "0x0");
            filter.put("toBlock", "latest");

            JSONArray params = new JSONArray();
            params.put(filter);
            req.put("params", params);
            req.put("id", 1000);

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        JSONArray logs = res.getJSONArray("result");

                        if (logs.length() > 0) {
                            // 游戏已开始
                            gameStatus = "游戏中";
                            isWaiting = false;
                            Log.i(TAG, "✅ 检测到GameStarted事件，游戏已开始！");

                            runOnUiThread(() -> {
                                Toast.makeText(GameRoomWaitActivity.this, "游戏开始！", Toast.LENGTH_SHORT).show();
                                // 跳转至游戏战斗页面
                                Intent intent = new Intent(GameRoomWaitActivity.this, GameBattleActivity.class);
                                intent.putExtra("gameId", gameId.toString());
                                intent.putExtra("roomAddress", roomAddress);
                                intent.putExtra("playerList", new ArrayList<>(playerList));
                                startActivity(intent);
                                finish();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析GameStarted事件异常：", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "获取GameStarted事件失败：", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "监听GameStarted事件异常：", e);
        }
    }

    /**
     * 以 StakingVault.getPlayerStake 为准刷新每位玩家质押与列表展示（与链上奖池一致）。
     */
    private void refreshStakesFromVault() {
        if (gameId == null) return;
        if (playerList.isEmpty()) {
            runOnUiThread(() -> tvPlayerList.setText("暂无玩家"));
            return;
        }
        final AtomicInteger pending = new AtomicInteger(playerList.size());
        for (final String player : playerList) {
            queryStakeForPlayer(player, new StakeCallback() {
                @Override
                public void onStake(BigInteger stake) {
                    playerStakeMap.put(player, stake);
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            totalStake = BigInteger.ZERO;
                            for (String p : playerList) {
                                totalStake = totalStake.add(playerStakeMap.getOrDefault(p, BigInteger.ZERO));
                            }
                            tvPlayerList.setText(buildPlayerListText());
                            tvStakeAmount.setText("总质押：" + fromWei(totalStake) + " BKC");
                            updateUI();
                        });
                    }
                }
            });
        }
    }

    private interface StakeCallback {
        void onStake(BigInteger amount);
    }

    private void queryStakeForPlayer(String player, StakeCallback cb) {
        try {
            String data = ABIUtils.encodeGetPlayerStake(gameId, player);
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
            callParams.put("to", GameConfig.STAKING_VAULT_ADDRESS);
            callParams.put("data", data);
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);
            params.put("latest");

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_call");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        String raw = res.optString("result", "0x");
                        BigInteger stake = new BigInteger(raw.startsWith("0x") ? raw.substring(2) : raw, 16);
                        cb.onStake(stake);
                    } catch (Exception e) {
                        Log.e(TAG, "解析质押失败 " + player, e);
                        cb.onStake(BigInteger.ZERO);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    cb.onStake(BigInteger.ZERO);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryStakeForPlayer异常", e);
            cb.onStake(BigInteger.ZERO);
        }
    }

    private String buildPlayerListText() {
        if (playerList.isEmpty()) return "暂无玩家";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < playerList.size(); i++) {
            String p = playerList.get(i);
            BigInteger st = playerStakeMap.getOrDefault(p, BigInteger.ZERO);
            sb.append(i + 1).append(". ").append(shortAddr(p)).append("  ")
                    .append(fromWei(st)).append(" BKC\n");
        }
        return sb.toString().trim();
    }

    private String shortAddr(String addr) {
        if (addr == null || addr.length() < 10) return addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }

    // 更新UI
    private void updateUI() {
        runOnUiThread(() -> {
            tvGameState.setText("状态：" + gameStatus);
            tvPlayerCount.setText("玩家：" + playerList.size() + "/" + maxPlayers + " (最少" + minPlayers + "人)");
            tvStakeAmount.setText("总质押：" + fromWei(totalStake) + " BKC");

            // 房主才能启动游戏，且玩家数≥最小玩家数
            boolean isHost = hostAddress.equalsIgnoreCase(getCurrentWalletAddress());
            boolean canStart = playerList.size() >= minPlayers && isHost && gameStatus.equals("等待玩家");
            btnStartGame.setEnabled(canStart);
            btnStartGame.setText(isHost ? "开始游戏（房主）" : "等待房主开始");

            Log.d(TAG, "UI更新：玩家数=" + playerList.size() + ", 可开始=" + canStart + ", 房主=" + isHost);
        });
    }

    // 房主启动游戏（调用GameRoom._startGame）
    private void startGame() {
        try {
            Log.i(TAG, "========== 房主启动游戏 ==========");
            btnStartGame.setEnabled(false);

            // ABI编码调用_startGame
            String data = ABIUtils.encodeStartGame();
            JSONObject txParams = new JSONObject();
            txParams.put("from", getCurrentWalletAddress());
            txParams.put("to", roomAddress);
            txParams.put("data", data);
            txParams.put("value", "0x0");
            txParams.put("gas", "0x800000");

            JSONArray params = new JSONArray();
            params.put(txParams);

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("method", "eth_sendTransaction");
            request.put("params", params);
            request.put("id", RequestIdGenerator.getNextId());

            Log.d(TAG, "启动游戏交易：" + request.toString());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("result")) {
                            String txHash = res.getString("result");
                            Log.i(TAG, "启动游戏交易提交成功！Hash：" + txHash);
                            runOnUiThread(() -> Toast.makeText(GameRoomWaitActivity.this, "启动游戏交易已提交！", Toast.LENGTH_SHORT).show());
                        } else {
                            String error = res.optJSONObject("error").optString("message");
                            Log.e(TAG, "启动游戏失败：" + error);
                            runOnUiThread(() -> {
                                Toast.makeText(GameRoomWaitActivity.this, "启动失败：" + error, Toast.LENGTH_SHORT).show();
                                btnStartGame.setEnabled(true);
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析启动游戏结果异常：", e);
                        runOnUiThread(() -> {
                            Toast.makeText(GameRoomWaitActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                            btnStartGame.setEnabled(true);
                        });
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "启动游戏网络错误：", e);
                    runOnUiThread(() -> {
                        Toast.makeText(GameRoomWaitActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        btnStartGame.setEnabled(true);
                    });
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "启动游戏异常：", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "启动游戏失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                btnStartGame.setEnabled(true);
            });
        }
    }

    // Wei转换为BKC
    private String fromWei(BigInteger wei) {
        if (wei == null) return "0";
        return wei.divide(new BigInteger("1000000000000000000")).toString() + "." +
                String.format("%018d", wei.mod(new BigInteger("1000000000000000000"))).substring(0, 4);
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
            return address;
        } catch (Exception e) {
            Log.e(TAG, "获取钱包地址失败：", e);
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isWaiting = false;
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "========== 游戏等待页面销毁 ==========");
    }
}