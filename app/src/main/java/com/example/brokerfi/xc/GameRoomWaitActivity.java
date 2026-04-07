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
    private String currentWalletAddress;

    // 玩家数据（适配合约Player结构）
    private List<String> playerList = new ArrayList<>();
    private Map<String, BigInteger> playerStakeMap = new HashMap<>();
    private BigInteger totalStake = BigInteger.ZERO;
    private String gameStatus = "等待玩家";
    private int minPlayers = 2;
    private int maxPlayers = 4;
    /** 链上 GameRoom.roomOwner()，用于判断谁可点「开始」 */
    private String roomOwnerOnChain = "";

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
        currentWalletAddress = getCurrentWalletAddress();
        hostAddress = currentWalletAddress;

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
        queryRoomOwner();

        // 启动事件监听
        startEventSyncLoop();
    }

    private void queryRoomOwner() {
        try {
            if (roomAddress == null || roomAddress.length() < 10) return;
            String data = ABIUtils.encodeRoomOwner();
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
            callParams.put("to", roomAddress);
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
                        roomOwnerOnChain = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        runOnUiThread(() -> updateUI());
                    } catch (Exception e) {
                        Log.e(TAG, "解析 roomOwner 异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryRoomOwner 异常", e);
        }
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
            if (roomOwnerOnChain == null || roomOwnerOnChain.length() < 10
                    || roomOwnerOnChain.equalsIgnoreCase("0x0000000000000000000000000000000000000000")) {
                queryRoomOwner();
            }
            // RPC 可能不支持 eth_getLogs：改为 eth_call 轮询房间状态
            fetchPlayersByCall();
            fetchGameStateByCall();
            // 3. 更新UI
            updateUI();

            handler.postDelayed(this::startEventSyncLoop, 3000); // 每3秒同步一次
        }, 1000);
    }

    /** eth_call GameRoom.getPlayers() 获取玩家列表（替代 eth_getLogs PlayerJoined） */
    private void fetchPlayersByCall() {
        try {
            if (roomAddress == null || gameId == null) return;
            String data = ABIUtils.encodeGetPlayers();
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
            callParams.put("to", roomAddress);
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
                        List<String> addrs = ABIUtils.decodeAddressArray(raw, 0);
                        playerList.clear();
                        playerStakeMap.clear();
                        totalStake = BigInteger.ZERO;
                        for (String p : addrs) {
                            if (p == null || p.length() < 10) continue;
                            if (!playerList.contains(p)) playerList.add(p);
                        }
                        // 质押金额在本合约 playerData.stakeAmount 中（joinGame() 并不会写入 Vault.gameStakes）
                        refreshStakesFromRoomPlayerData();
                    } catch (Exception e) {
                        Log.e(TAG, "fetchPlayersByCall 解析异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "fetchPlayersByCall 网络错误", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchPlayersByCall 异常", e);
        }
    }

    /** eth_call GameRoom.gameState() 判断是否已开局（替代 eth_getLogs GameStarted） */
    private void fetchGameStateByCall() {
        try {
            if (roomAddress == null || gameId == null) return;
            String data = ABIUtils.encodeGameState(); // 旧版写死 selector 也能用
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
            callParams.put("to", roomAddress);
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
                        BigInteger st = new BigInteger(raw.startsWith("0x") ? raw.substring(2) : raw, 16);
                        // enum GameState { PENDING(0), DEALING(1), PLAYING(2), ENDED(3) }
                        if (st.compareTo(BigInteger.valueOf(2)) >= 0) {
                            gameStatus = "游戏中";
                            isWaiting = false;
                            runOnUiThread(() -> {
                                Toast.makeText(GameRoomWaitActivity.this, "游戏开始！", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(GameRoomWaitActivity.this, GameBattleActivity.class);
                                intent.putExtra("gameId", gameId.toString());
                                intent.putExtra("roomAddress", roomAddress);
                                intent.putExtra("playerList", new ArrayList<>(playerList));
                                startActivity(intent);
                                finish();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "fetchGameStateByCall 解析异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchGameStateByCall 异常", e);
        }
    }

    // eth_getLogs 在部分节点不可用，保留旧方法删除/弃用

    /**
     * 以 GameRoom.playerData(address).stakeAmount 为准刷新每位玩家质押与列表展示。
     *
     * 注意：当前合约实现中 joinGame() 仅在 GameRoom 内记录 stakeAmount，
     * 并不会调用 StakingVault.stake/stakeFor 写入 gameStakes，因此用 Vault 查询会始终为 0。
     */
    private void refreshStakesFromRoomPlayerData() {
        if (gameId == null) return;
        if (playerList.isEmpty()) {
            runOnUiThread(() -> tvPlayerList.setText("暂无玩家"));
            return;
        }
        final AtomicInteger pending = new AtomicInteger(playerList.size());
        for (final String player : playerList) {
            queryStakeForPlayerFromRoom(player, new StakeCallback() {
                @Override
                public void onStake(BigInteger stake) {
                    playerStakeMap.put(player, stake);
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            totalStake = BigInteger.ZERO;
                            for (String p : playerList) {
                                BigInteger stakeValue = playerStakeMap.get(p);
// 判空，如果key不存在则使用默认值 BigInteger.ZERO
                                if (stakeValue == null) {
                                    stakeValue = BigInteger.ZERO;
                                }
                                totalStake = totalStake.add(stakeValue);
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

    private void queryStakeForPlayerFromRoom(String player, StakeCallback cb) {
        try {
            if (roomAddress == null || roomAddress.length() < 10) {
                cb.onStake(BigInteger.ZERO);
                return;
            }
            // GameRoom.playerData(address) 是 public mapping getter
            String data = ABIUtils.encodePlayerData(player);
            JSONObject callParams = new JSONObject();
            callParams.put("from", hostAddress);
            callParams.put("to", roomAddress);
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
                        // Player: (addr, stakeAmount, isActive, isOut, handCards[10], jokerCount)
                        // stakeAmount 位于 index=1
                        BigInteger stake = ABIUtils.decodeUint256(raw, 1);
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
            BigInteger st = playerStakeMap.get(p);
            if (st == null) {
                st = BigInteger.ZERO;
            }
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

            boolean isOwner = (roomOwnerOnChain != null && roomOwnerOnChain.length() >= 42
                    && !roomOwnerOnChain.equalsIgnoreCase("0x0000000000000000000000000000000000000000"))
                    ? getCurrentWalletAddress().equalsIgnoreCase(roomOwnerOnChain)
                    : hostAddress.equalsIgnoreCase(getCurrentWalletAddress());
            // 满员由链上自动开局；仅 min≤人数<max 时房主可手动开始
            boolean canStart = playerList.size() >= minPlayers
                    && playerList.size() < maxPlayers
                    && isOwner
                    && gameStatus.equals("等待玩家");
            btnStartGame.setEnabled(canStart);
            if (!isOwner) {
                btnStartGame.setText("等待房主开始");
            } else if (playerList.size() >= maxPlayers) {
                btnStartGame.setText("已满员，将自动开局");
            } else if (playerList.size() < minPlayers) {
                btnStartGame.setText("人数不足，无法开始");
            } else {
                btnStartGame.setText("开始游戏（房主）");
            }

            Log.d(TAG, "UI更新：玩家数=" + playerList.size() + ", 可开始=" + canStart + ", 房主=" + isOwner);
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
                            String expectedFrom = txParams.optString("from", "");
                            Log.i(TAG, "启动游戏交易提交成功！Hash：" + txHash);
                            verifyTxFrom(txHash, expectedFrom);
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

    private void verifyTxFrom(String txHash, String expectedFrom) {
        try {
            JSONArray params = new JSONArray();
            params.put(txHash);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getTransactionByHash");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        JSONObject tx = res.optJSONObject("result");
                        if (tx == null) return;
                        String actualFrom = tx.optString("from", "");
                        Log.i(TAG, "地址校验(startGame)：expectedFrom=" + expectedFrom + ", actualFrom=" + actualFrom + ", txHash=" + txHash);
                        if (expectedFrom != null && !expectedFrom.isEmpty()
                                && actualFrom != null && !actualFrom.isEmpty()
                                && !expectedFrom.equalsIgnoreCase(actualFrom)) {
                            runOnUiThread(() -> Toast.makeText(
                                    GameRoomWaitActivity.this,
                                    "警告：链上交易发送地址与当前钱包不一致（节点可能使用默认解锁账户代发）",
                                    Toast.LENGTH_LONG
                            ).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "地址校验解析异常(startGame)", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "verifyTxFrom(startGame) 异常", e);
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