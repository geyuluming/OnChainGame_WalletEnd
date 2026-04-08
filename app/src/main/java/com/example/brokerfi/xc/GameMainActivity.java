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
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Locale;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;
import java.util.concurrent.atomic.AtomicReference;

public class GameMainActivity extends AppCompatActivity {
    private static final String TAG = "GameMainActivity";
    private EditText etMinPlayers, etMaxPlayers, etMinStake, etMaxStake, etJokerCount, etMyStake;
    private Button btnCreateGame, btnJoinGame;
    private static final BigInteger GAS_FALLBACK = new BigInteger("800000", 16);
    private static final BigInteger GAS_BUFFER_BPS = BigInteger.valueOf(12_000L); // +20%
    // true：本地 Web3j 签名 + eth_sendRawTransaction（需节点正确返回 txHash；若只回 {"jsonrpc","id"} 请改 false 或开网关）。
    // false：直连 eth_sendTransaction（部分节点会忽略 from，仅适合确认合约能否上链）。
    private static final boolean USE_RAW_TX = false;

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
        etMyStake = findViewById(R.id.et_my_stake);
        btnCreateGame = findViewById(R.id.btn_create_game);
        btnJoinGame = findViewById(R.id.btn_join_game);
        Log.d(TAG, "控件绑定完成");

        // 默认值适配合约（2-8人，小丑牌最大8张）
        etMinPlayers.setText("2");
        etMaxPlayers.setText("4");
        etMinStake.setText("1"); // 1 BKC
        etMaxStake.setText("100"); // 100 BKC
        etJokerCount.setText("1");
        etMyStake.setText("1");
        Log.d(TAG, "输入框默认值已设置");

        // 启动时检测：Vault.factory 是否已指向 GameFactory，否则 createGameRoom 会 reverted
        checkVaultFactoryConfigured();

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

    @Override
    protected void onResume() {
        super.onResume();
        checkVaultFactoryConfigured();
    }

    private void checkVaultFactoryConfigured() {
        try {
            String from = getCurrentWalletAddress();
            String data = ABIUtils.encodeVaultFactory();
            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
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
                        String factoryAddr = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        boolean ok = factoryAddr != null
                                && factoryAddr.equalsIgnoreCase(GameConfig.GAME_FACTORY_ADDRESS);

                        runOnUiThread(() -> {
                            btnCreateGame.setEnabled(ok);
                            if (!ok) {
                                Toast.makeText(GameMainActivity.this,
                                        "检测到未配置金库工厂地址：请先用 feeReceiver 调用 StakingVault.setFactory(GameFactory)",
                                        Toast.LENGTH_LONG).show();
                            }
                        });

                        if (!ok) {
                            Log.e(TAG, "Vault.factory 未指向 GameFactory。vault.factory=" + factoryAddr
                                    + " expected=" + GameConfig.GAME_FACTORY_ADDRESS);
                            queryVaultFeeReceiverForHint();
                        } else {
                            Log.i(TAG, "Vault.factory 已配置为 GameFactory：" + factoryAddr);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析 Vault.factory 失败", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "读取 Vault.factory 网络错误", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "checkVaultFactoryConfigured 异常", e);
        }
    }

    private void queryVaultFeeReceiverForHint() {
        try {
            String from = getCurrentWalletAddress();
            String data = ABIUtils.encodeVaultFeeReceiver();
            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
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
                        String feeReceiver = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        Log.i(TAG, "Vault.feeReceiver=" + feeReceiver + "（需要该地址发 setFactory）");
                    } catch (Exception e) {
                        Log.e(TAG, "解析 Vault.feeReceiver 失败", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryVaultFeeReceiverForHint 异常", e);
        }
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
            String myStakeStr = etMyStake.getText().toString().trim();
            if (myStakeStr.isEmpty()) {
                Toast.makeText(this, "请填写本人质押（BKC）", Toast.LENGTH_SHORT).show();
                return;
            }
            BigInteger myStakeWei = toWei(myStakeStr);
            if (myStakeWei.compareTo(minStake) < 0 || myStakeWei.compareTo(maxStake) > 0) {
                Toast.makeText(this, "本人质押须在最小与最大质押之间", Toast.LENGTH_SHORT).show();
                return;
            }

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
                    GameConfig.VRF_REQUEST_CONFIRMATIONS,
                    GameConfig.ECVRF_RELAY_ADDRESS
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
            // 先不直接写死 gas：先 estimateGas，失败时再 fallback

            // 6. 关键前置检查：
            // - GameFactory.stakingVault() 必须与配置的 STAKING_VAULT_ADDRESS 一致，否则 createGameRoom 内部 registerGameRoom 会打到另一个 Vault 并 revert
            // - Vault.factory() 必须指向这个 GameFactory，否则 Vault.registerGameRoom 会 revert("only factory")
            precheckFactoryAndVaultThenEstimateAndSend(txParams);
        } catch (Exception e) {
            Log.e(TAG, "创建游戏整体异常：", e);
            Toast.makeText(this, "参数错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void precheckFactoryAndVaultThenEstimateAndSend(JSONObject txParams) {
        try {
            String from = txParams.optString("from", "");
            // 1) 读 GameFactory.stakingVault()
            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
            callParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            callParams.put("data", ABIUtils.encodeFactoryStakingVault());
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);
            params.put("latest");

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_call");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            String err = formatRpcError(res.optJSONObject("error"));
                            Log.e(TAG, "读取 GameFactory.stakingVault 失败：" + err);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "读取工厂配置失败：" + err, Toast.LENGTH_LONG).show());
                            return;
                        }
                        String vaultFromFactory = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        if (vaultFromFactory == null) vaultFromFactory = "";
                        Log.i(TAG, "GameFactory.stakingVault=" + vaultFromFactory + " config.vault=" + GameConfig.STAKING_VAULT_ADDRESS);
                        if (!vaultFromFactory.equalsIgnoreCase(GameConfig.STAKING_VAULT_ADDRESS)) {
                            String msg = "合约地址不匹配：GameFactory 绑定的 Vault=" + vaultFromFactory
                                    + "，但配置里是 " + GameConfig.STAKING_VAULT_ADDRESS
                                    + "。请更新 GameConfig.STAKING_VAULT_ADDRESS 或重新部署匹配的一组合约。";
                            Log.e(TAG, msg);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, msg, Toast.LENGTH_LONG).show());
                            return;
                        }

                        // 2) 读 Vault.factory()
                        precheckVaultFactoryThenEstimateAndSend(txParams);
                    } catch (Exception e) {
                        Log.e(TAG, "解析 GameFactory.stakingVault 异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "读取 GameFactory.stakingVault 网络错误", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "precheckFactoryAndVaultThenEstimateAndSend 异常", e);
        }
    }

    private void precheckVaultFactoryThenEstimateAndSend(JSONObject txParams) {
        try {
            String from = txParams.optString("from", "");
            String data = ABIUtils.encodeVaultFactory();

            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
            callParams.put("to", GameConfig.STAKING_VAULT_ADDRESS);
            callParams.put("data", data);
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);
            params.put("latest");

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_call");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            String err = formatRpcError(res.optJSONObject("error"));
                            Log.e(TAG, "读取 Vault.factory 失败：" + err);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "读取金库配置失败：" + err, Toast.LENGTH_LONG).show());
                            return;
                        }

                        String factoryAddr = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        Log.i(TAG, "Vault.factory=" + factoryAddr + " expected=" + GameConfig.GAME_FACTORY_ADDRESS);
                        if (factoryAddr == null || !factoryAddr.equalsIgnoreCase(GameConfig.GAME_FACTORY_ADDRESS)) {
                            String msg = "金库未授权该工厂：Vault.factory=" + factoryAddr
                                    + "，需要为 " + GameConfig.GAME_FACTORY_ADDRESS
                                    + "。请用 feeReceiver 调用 StakingVault.setFactory(GameFactory)。";
                            Log.e(TAG, msg);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, msg, Toast.LENGTH_LONG).show());
                            // 顺便打出 feeReceiver 提示
                            queryVaultFeeReceiverForHint();
                            return;
                        }

                        // 3) 再读取当前 gameIdCounter / room 映射，排除 “room exists” 等状态类回滚
                        logFactoryAndVaultRoomStateThenEstimateAndSend(txParams);
                    } catch (Exception e) {
                        Log.e(TAG, "解析 Vault.factory 异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "读取 Vault.factory 网络错误", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "precheckVaultFactoryThenEstimateAndSend 异常", e);
        }
    }

    private void logFactoryAndVaultRoomStateThenEstimateAndSend(JSONObject txParams) {
        try {
            String from = txParams.optString("from", "");

            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
            callParams.put("to", GameConfig.GAME_FACTORY_ADDRESS);
            callParams.put("data", ABIUtils.encodeFactoryGameIdCounter());
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);
            params.put("latest");

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_call");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            Log.w(TAG, "读取 gameIdCounter 失败：" + formatRpcError(res.optJSONObject("error")));
                            estimateAndSend(txParams);
                            return;
                        }
                        BigInteger gid = ABIUtils.decodeUint256(res.optString("result", "0x"), 0);
                        Log.i(TAG, "GameFactory.gameIdCounter(current)=" + gid);

                        // 读 factory.gameRooms(gid)
                        java.util.concurrent.atomic.AtomicReference<String> factoryRoomRef = new java.util.concurrent.atomic.AtomicReference<>("0x");
                        java.util.concurrent.atomic.AtomicReference<String> vaultRoomRef = new java.util.concurrent.atomic.AtomicReference<>("0x");

                        readRoomMapping("GameFactory.gameRooms", GameConfig.GAME_FACTORY_ADDRESS, gid, from, addr -> {
                            factoryRoomRef.set(addr);
                            // 读 vault.gameRooms(gid)
                            readRoomMapping("StakingVault.gameRooms", GameConfig.STAKING_VAULT_ADDRESS, gid, from, vAddr -> {
                                vaultRoomRef.set(vAddr);

                                // 如果 Vault 已经有 room，但 Factory 映射为空，则 createGameRoom 会在 registerGameRoom 处永远回滚，
                                // 并导致 gameIdCounter 因回滚无法自增 → 形成“卡死在同一个 gameId”的状态。
                                if (isNonZeroAddress(vAddr) && !isNonZeroAddress(addr)) {
                                    String msg = "检测到 Vault.gameRooms(" + gid + ") 已存在：" + vAddr
                                            + "，但 GameFactory.gameRooms(" + gid + ") 为空。此时 createGameRoom 会因 \"room exists\" 回滚，gameIdCounter 也无法前进。"
                                            + " 处理方式：直接加入该房间，或重新部署/升级 GameFactory（允许跳过已占用 gameId），或重新部署一个新的 Vault。";
                                    Log.e(TAG, msg);
                                    runOnUiThread(() -> Toast.makeText(GameMainActivity.this, msg, Toast.LENGTH_LONG).show());
                                    return;
                                }

                                estimateAndSend(txParams);
                            });
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "解析 gameIdCounter 异常（继续 estimate/send）", e);
                        estimateAndSend(txParams);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "读取 gameIdCounter 网络错误（继续 estimate/send）", e);
                    estimateAndSend(txParams);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "logFactoryAndVaultRoomStateThenEstimateAndSend 异常（继续 estimate/send）", e);
            estimateAndSend(txParams);
        }
    }

    private interface Done { void run(); }
    private interface AddressDone { void run(String addr); }

    private void readRoomMapping(String label, String contractAddr, BigInteger gameId, String from, AddressDone done) {
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", from);
            callParams.put("to", contractAddr);
            callParams.put("data", ABIUtils.encodeGameRooms(gameId));
            callParams.put("value", "0x0");

            JSONArray params = new JSONArray();
            params.put(callParams);
            params.put("latest");

            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_call");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    String decoded = "0x";
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            Log.w(TAG, "读取 " + label + " 失败：" + formatRpcError(res.optJSONObject("error")));
                        } else {
                            decoded = ABIUtils.decodeAddress(res.optString("result", "0x"));
                            Log.i(TAG, label + "(" + gameId + ")=" + decoded);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "解析 " + label + " 异常", e);
                    }
                    done.run(decoded);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "读取 " + label + " 网络错误", e);
                    done.run("0x");
                    return null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "构造读取 " + label + " 异常", e);
            done.run("0x");
        }
    }

    private static boolean isNonZeroAddress(String addr) {
        if (addr == null) return false;
        return !addr.equalsIgnoreCase("0x0000000000000000000000000000000000000000");
    }

    private void estimateAndSend(JSONObject txParams) {
        try {
            // estimateGas：很多节点会在此返回更具体的 revert 信息
            JSONObject estimateReq = new JSONObject();
            estimateReq.put("jsonrpc", "2.0");
            estimateReq.put("method", "eth_estimateGas");
            JSONArray estimateParams = new JSONArray();
            estimateParams.put(txParams);
            estimateReq.put("params", estimateParams);
            estimateReq.put("id", RequestIdGenerator.getNextId());

            Log.i(TAG, "RPC estimateGas 请求体：" + estimateReq.toString());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, estimateReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String estimateResult) {
                    try {
                        Log.d(TAG, "estimateGas 原始响应：" + estimateResult);
                        JSONObject estimateRes = new JSONObject(estimateResult);
                        if (estimateRes.has("error")) {
                            String err = formatRpcError(estimateRes.optJSONObject("error"));
                            Log.e(TAG, "estimateGas 失败（多半会 reverted）：" + err);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "创建失败（revert）：" + err, Toast.LENGTH_LONG).show());
                            return;
                        }

                        String gasHex = estimateRes.optString("result", null);
                        BigInteger estimated = gasHex == null ? BigInteger.ZERO : new BigInteger(gasHex.substring(2), 16);
                        BigInteger buffered = (estimated.compareTo(BigInteger.ZERO) > 0) ? addGasBuffer(estimated) : BigInteger.ZERO;
                        // 合约包含 new GameRoom（部署）+ 多次 SSTORE + 外部调用，部分节点 estimateGas 偏低。
                        // 为避免 OOG 被包装成 execution reverted，gas 取 max(buffered, fallback)。
                        BigInteger gasToUse = buffered.max(GAS_FALLBACK);
                        txParams.put("gas", "0x" + gasToUse.toString(16));
                        Log.i(TAG, "estimateGas=" + estimated + " 使用 gas=" + gasToUse);

                        // BrokerChain 本地节点对写方法 eth_call 会返回 write protection，这里不再阻塞，直接发送
                        sendCreateTx(txParams);
                    } catch (Exception e) {
                        Log.e(TAG, "estimateGas 解析异常", e);
                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "estimateGas 解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "estimateGas 网络错误", e);
                    runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "estimateGas 网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "estimateAndSend 异常", e);
        }
    }

    private void handleCallThenSend(JSONObject txParams, String callLatestResult, JSONObject callReqPending) {
        try {
            Log.d(TAG, "eth_call(latest) 原始响应：" + callLatestResult);
            JSONObject res = new JSONObject(callLatestResult);
            if (res.has("error")) {
                String err = formatRpcError(res.optJSONObject("error"));
                Log.e(TAG, "eth_call(latest) 已 reverted：" + err);
                runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "创建失败（call reverted）：" + err, Toast.LENGTH_LONG).show());
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 eth_call(latest) 异常（继续 pending/c 及发送）", e);
        }

        // 再试一次 pending（有些 revert 只在 pending 暴露）
        OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, callReqPending.toString(), new MyCallBack() {
            @Override
            public void onSuccess(String callPendingResult) {
                try {
                    Log.d(TAG, "eth_call(pending) 原始响应：" + callPendingResult);
                    JSONObject res = new JSONObject(callPendingResult);
                    if (res.has("error")) {
                        String err = formatRpcError(res.optJSONObject("error"));
                        Log.e(TAG, "eth_call(pending) 已 reverted：" + err);
                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "创建失败（pending call reverted）：" + err, Toast.LENGTH_LONG).show());
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析 eth_call(pending) 异常（继续发送交易）", e);
                }
                sendCreateTx(txParams);
            }

            @Override
            public Void onError(Exception e) {
                Log.e(TAG, "eth_call(pending) 网络错误，继续发送交易", e);
                sendCreateTx(txParams);
                return null;
            }
        });
    }

    private static JSONObject buildEthCallRequest(JSONObject txParams, String blockTag) throws Exception {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("method", "eth_call");
        JSONArray params = new JSONArray();
        params.put(txParams);
        params.put(blockTag);
        req.put("params", params);
        req.put("id", RequestIdGenerator.getNextId());
        return req;
    }

    private void sendCreateTx(JSONObject txParams) {
        try {
            if (GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX) {
                sendCreateTxBrokerGateway(txParams);
                return;
            }
            if (USE_RAW_TX) {
                sendCreateTxRawSigned(txParams);
                return;
            }
            JSONArray sendParams = new JSONArray();
            sendParams.put(txParams);
            JSONObject sendReq = new JSONObject();
            sendReq.put("jsonrpc", "2.0");
            sendReq.put("method", "eth_sendTransaction");
            sendReq.put("params", sendParams);
            sendReq.put("id", RequestIdGenerator.getNextId());
            Log.i(TAG, "RPC sendTransaction 请求体：" + sendReq.toString());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, sendReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "========== 创建游戏请求返回 ==========");
                    Log.d(TAG, "原始响应：" + result);
                    try {
                        JSONObject response = new JSONObject(result);
                        if (response.has("result")) {
                            String txHash = response.getString("result");
                            Log.i(TAG, "创建游戏交易提交成功！Hash：" + txHash);
                            // 校验链上实际 from，避免节点忽略 from 字段
                            String expectedFrom = sendParams.getJSONObject(0).optString("from", "");
                            verifyTxFrom(txHash, expectedFrom, "createGame");
                            queryGameRoomInfo(txHash);
                            runOnUiThread(() ->
                                    Toast.makeText(GameMainActivity.this, "创建游戏交易已提交！Hash：" + txHash, Toast.LENGTH_LONG).show()
                            );
                        } else {
                            String err = formatRpcError(response.optJSONObject("error"));
                            Log.e(TAG, "创建游戏失败：" + err);
                            runOnUiThread(() ->
                                    Toast.makeText(GameMainActivity.this, "创建失败：" + err, Toast.LENGTH_LONG).show()
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析响应异常：", e);
                        runOnUiThread(() ->
                                Toast.makeText(GameMainActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show()
                        );
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "创建游戏网络错误：", e);
                    runOnUiThread(() ->
                            Toast.makeText(GameMainActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "sendCreateTx 构造/发送异常", e);
            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "发送失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    /** 与转账/NFT 相同：HTTPS 网关 + 当前账户 ECDSA，避免本地节点忽略 from。 */
    private void sendCreateTxBrokerGateway(JSONObject txParams) {
        String pk = StorageUtil.getCurrentPrivatekey(this);
        if (pk == null || pk.trim().isEmpty()) {
            Toast.makeText(this, "未找到当前账户私钥", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (!txParams.has("gas") || txParams.optString("gas", "").isEmpty()) {
                txParams.put("gas", "0x" + GAS_FALLBACK.toString(16));
            }
        } catch (JSONException e) {
            Log.e(TAG, "设置 gas 失败", e);
            Toast.makeText(this, "构造交易参数失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final String expectedFrom = txParams.optString("from", "");
        new Thread(() -> {
            String json = MyUtil.sendGameContractTxViaGateway(
                    pk.trim(),
                    txParams.optString("to"),
                    txParams.optString("data"),
                    txParams.optString("value", "0x0"),
                    txParams.optString("gas"));
            String txHash = MyUtil.parseGatewayTxHash(json);
            runOnUiThread(() -> {
                if (txHash != null) {
                    Log.i(TAG, "网关创建游戏提交成功！Hash：" + txHash);
                    verifyTxFrom(txHash, expectedFrom, "createGameGateway");
                    queryGameRoomInfo(txHash);
                    Toast.makeText(GameMainActivity.this, "创建游戏交易已提交！", Toast.LENGTH_LONG).show();
                } else {
                    String err = MyUtil.formatGatewayError(json);
                    Log.e(TAG, "网关创建失败：" + err);
                    Toast.makeText(GameMainActivity.this, "创建失败：" + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * 使用本地私钥签名并通过 eth_sendRawTransaction 发送。
     * 适配节点对 eth_sendTransaction / 写调用的限制场景。
     */
    private void sendCreateTxRawSigned(JSONObject txParams) {
        try {
            String from = txParams.optString("from", "");
            String to = txParams.optString("to", "");
            String data = txParams.optString("data", "0x");
            String valueHex = txParams.optString("value", "0x0");
            String gasHex = txParams.optString("gas", null);

            BigInteger gasLimit = (gasHex != null && gasHex.startsWith("0x"))
                    ? new BigInteger(gasHex.substring(2), 16)
                    : GAS_FALLBACK;
            BigInteger valueWei = (valueHex != null && valueHex.startsWith("0x"))
                    ? new BigInteger(valueHex.substring(2), 16)
                    : BigInteger.ZERO;

            String privateKeyHex = getCurrentPrivateKeyHex();
            if (privateKeyHex.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "未找到当前账户私钥，无法签名发送", Toast.LENGTH_LONG).show());
                return;
            }

            Credentials credentials = Credentials.create(privateKeyHex);
            if (!from.isEmpty() && !credentials.getAddress().equalsIgnoreCase(from)) {
                Log.w(TAG, "from 与私钥地址不一致：from=" + from + " pkAddress=" + credentials.getAddress());
            }

            AtomicReference<BigInteger> chainIdRef = new AtomicReference<>(BigInteger.ZERO);
            AtomicReference<BigInteger> nonceRef = new AtomicReference<>(BigInteger.ZERO);
            AtomicReference<BigInteger> gasPriceRef = new AtomicReference<>(BigInteger.ZERO);

            // 1) chainId
            JSONObject chainIdReq = new JSONObject();
            chainIdReq.put("jsonrpc", "2.0");
            chainIdReq.put("method", "eth_chainId");
            chainIdReq.put("params", new JSONArray());
            chainIdReq.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, chainIdReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String chainIdResult) {
                    try {
                        JSONObject res = new JSONObject(chainIdResult);
                        if (res.has("result")) {
                            String hex = res.optString("result", "0x0");
                            BigInteger cid = new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
                            chainIdRef.set(cid);
                        } else {
                            Log.w(TAG, "eth_chainId 未返回 result，使用 0（可能导致签名不被接受）");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "解析 eth_chainId 失败，使用 0", e);
                    }

                    // 2) nonce (pending)
                    try {
                        JSONObject nonceReq = new JSONObject();
                        nonceReq.put("jsonrpc", "2.0");
                        nonceReq.put("method", "eth_getTransactionCount");
                        JSONArray params = new JSONArray();
                        params.put(from.isEmpty() ? credentials.getAddress() : from);
                        params.put("pending");
                        nonceReq.put("params", params);
                        nonceReq.put("id", RequestIdGenerator.getNextId());

                        OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, nonceReq.toString(), new MyCallBack() {
                            @Override
                            public void onSuccess(String nonceResult) {
                                try {
                                    JSONObject r = new JSONObject(nonceResult);
                                    String hex = r.optString("result", "0x0");
                                    nonceRef.set(new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16));
                                } catch (Exception e) {
                                    Log.e(TAG, "解析 nonce 失败", e);
                                }

                                // 3) gasPrice
                                try {
                                    JSONObject gpReq = new JSONObject();
                                    gpReq.put("jsonrpc", "2.0");
                                    gpReq.put("method", "eth_gasPrice");
                                    gpReq.put("params", new JSONArray());
                                    gpReq.put("id", RequestIdGenerator.getNextId());

                                    OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, gpReq.toString(), new MyCallBack() {
                                        @Override
                                        public void onSuccess(String gpResult) {
                                            try {
                                                JSONObject r = new JSONObject(gpResult);
                                                String hex = r.optString("result", "0x0");
                                                gasPriceRef.set(new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16));
                                            } catch (Exception e) {
                                                Log.w(TAG, "解析 gasPrice 失败，使用 0", e);
                                                gasPriceRef.set(BigInteger.ZERO);
                                            }

                                            // 4) sign + sendRaw
                                            try {
                                                BigInteger nonce = nonceRef.get();
                                                BigInteger gasPrice = gasPriceRef.get();
                                                BigInteger chainId = chainIdRef.get();

                                                Log.i(TAG, String.format(Locale.US,
                                                        "准备发送 rawTx: nonce=%s gasPrice=%s gasLimit=%s value=%s chainId=%s to=%s",
                                                        nonce.toString(), gasPrice.toString(), gasLimit.toString(),
                                                        valueWei.toString(), chainId.toString(), to));

                                                RawTransaction raw = RawTransaction.createTransaction(
                                                        nonce, gasPrice, gasLimit, to, valueWei, data
                                                );

                                                byte[] signed = (chainId.compareTo(BigInteger.ZERO) > 0)
                                                        ? TransactionEncoder.signMessage(raw, chainId.longValue(), credentials)
                                                        : TransactionEncoder.signMessage(raw, credentials);
                                                String rawHex = Numeric.toHexString(signed);

                                                JSONObject sendReq = new JSONObject();
                                                sendReq.put("jsonrpc", "2.0");
                                                sendReq.put("method", "eth_sendRawTransaction");
                                                JSONArray p = new JSONArray();
                                                p.put(rawHex);
                                                sendReq.put("params", p);
                                                sendReq.put("id", RequestIdGenerator.getNextId());

                                                Log.i(TAG, "RPC sendRawTransaction 请求体：{method:eth_sendRawTransaction, rawLen=" + rawHex.length() + "}");

                                                OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, sendReq.toString(), new MyCallBack() {
                                                    @Override
                                                    public void onSuccess(String result) {
                                                        Log.i(TAG, "========== sendRawTransaction 返回 ==========");
                                                        Log.d(TAG, "原始响应：" + result);
                                                        try {
                                                            JSONObject resp = new JSONObject(result);
                                                            if (resp.has("result")) {
                                                                String txHash = resp.getString("result");
                                                                Log.i(TAG, "rawTx 已提交！Hash：" + txHash);
                                                                queryGameRoomInfo(txHash);
                                                                runOnUiThread(() ->
                                                                        Toast.makeText(GameMainActivity.this, "已提交交易：" + txHash, Toast.LENGTH_LONG).show()
                                                                );
                                                            } else {
                                                                // 一些节点会返回非标准结构（甚至只有 id），这里做更强兜底
                                                                String err = formatRpcError(resp.optJSONObject("error"));
                                                                if ("unknown error".equals(err)) {
                                                                    err = "RPC 未返回 result/error，原始响应=" + resp.toString();
                                                                }
                                                                Log.e(TAG, "sendRawTransaction 失败：" + err);
                                                                final String toastErr = err;
                                                                probeRpcCapabilities();
                                                                // 不再回退 eth_sendTransaction：会再次走 raw 或导致节点默认账户代发、与钱包地址不一致。
                                                                runOnUiThread(() -> Toast.makeText(GameMainActivity.this,
                                                                        "rawTx 失败：" + toastErr
                                                                                + "。请在 GameConfig 将 USE_HTTP_GATEWAY_FOR_GAME_TX 设为 true（与转账同网关），"
                                                                                + "或将 USE_RAW_TX 设为 false 仅用直连（仅适合单机调试）。",
                                                                        Toast.LENGTH_LONG).show());
                                                            }
                                                        } catch (Exception e) {
                                                            Log.e(TAG, "解析 sendRawTransaction 返回失败", e);
                                                        }
                                                    }

                                                    @Override
                                                    public Void onError(Exception e) {
                                                        Log.e(TAG, "sendRawTransaction 网络错误", e);
                                                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                                                        return null;
                                                    }
                                                });
                                            } catch (Exception e) {
                                                Log.e(TAG, "签名/发送 rawTx 异常", e);
                                                runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "签名失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                                            }
                                        }

                                        @Override
                                        public Void onError(Exception e) {
                                            Log.e(TAG, "eth_gasPrice 网络错误", e);
                                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "获取 gasPrice 失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                                            return null;
                                        }
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "请求 gasPrice 异常", e);
                                }
                            }

                            @Override
                            public Void onError(Exception e) {
                                Log.e(TAG, "eth_getTransactionCount 网络错误", e);
                                runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "获取 nonce 失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                                return null;
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "构造 nonceReq 异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "eth_chainId 网络错误（继续使用 0）", e);
                    // 继续执行后续步骤：直接走一次 onSuccess 的路径（chainId=0）
                    onSuccess("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x0\"}");
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "sendCreateTxRawSigned 异常", e);
            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "发送异常：" + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void probeRpcCapabilities() {
        try {
            JSONObject vReq = new JSONObject();
            vReq.put("jsonrpc", "2.0");
            vReq.put("method", "web3_clientVersion");
            vReq.put("params", new JSONArray());
            vReq.put("id", RequestIdGenerator.getNextId());

            JSONObject mReq = new JSONObject();
            mReq.put("jsonrpc", "2.0");
            mReq.put("method", "rpc_modules");
            mReq.put("params", new JSONArray());
            mReq.put("id", RequestIdGenerator.getNextId());

            JSONObject bnReq = new JSONObject();
            bnReq.put("jsonrpc", "2.0");
            bnReq.put("method", "eth_blockNumber");
            bnReq.put("params", new JSONArray());
            bnReq.put("id", RequestIdGenerator.getNextId());

            JSONObject netReq = new JSONObject();
            netReq.put("jsonrpc", "2.0");
            netReq.put("method", "net_version");
            netReq.put("params", new JSONArray());
            netReq.put("id", RequestIdGenerator.getNextId());

            // 合约代码存在性探测（如果是 EVM RPC，应该返回 0x... bytecode 或 0x）
            JSONObject codeReq = new JSONObject();
            codeReq.put("jsonrpc", "2.0");
            codeReq.put("method", "eth_getCode");
            JSONArray codeParams = new JSONArray();
            codeParams.put(GameConfig.GAME_FACTORY_ADDRESS);
            codeParams.put("latest");
            codeReq.put("params", codeParams);
            codeReq.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, vReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "web3_clientVersion 响应：" + result);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "web3_clientVersion 失败", e);
                    return null;
                }
            });

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, mReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "rpc_modules 响应：" + result);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "rpc_modules 失败", e);
                    return null;
                }
            });

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, bnReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "eth_blockNumber 响应：" + result);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "eth_blockNumber 失败", e);
                    return null;
                }
            });

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, netReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "net_version 响应：" + result);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "net_version 失败", e);
                    return null;
                }
            });

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, codeReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    Log.i(TAG, "eth_getCode(GameFactory) 响应：" + result);
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "eth_getCode 失败", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "probeRpcCapabilities 异常", e);
        }
    }

    private String getCurrentPrivateKeyHex() {
        try {
            String acc = StorageUtil.getCurrentAccount(this);
            int index = (acc == null) ? 0 : Integer.parseInt(acc);
            String allKeys = StorageUtil.getPrivateKey(this);
            if (allKeys == null || allKeys.trim().isEmpty()) return "";
            String[] keys = allKeys.split(";");
            if (index < 0 || index >= keys.length) return "";
            String pk = keys[index].trim();
            if (pk.startsWith("0x") || pk.startsWith("0X")) pk = pk.substring(2);
            return pk;
        } catch (Exception e) {
            Log.e(TAG, "获取私钥失败", e);
            return "";
        }
    }

    private static BigInteger addGasBuffer(BigInteger estimated) {
        // estimated * (1 + 0.20) = estimated * 12000 / 10000
        return estimated.multiply(GAS_BUFFER_BPS).divide(BigInteger.valueOf(10_000L));
    }

    /**
     * 统一解析 JSON-RPC error，尽量打印 message/code/data。
     * 注意：不同节点 error.data 结构不一致，这里做 toString 兜底。
     */
    private static String formatRpcError(JSONObject errObj) {
        if (errObj == null) return "unknown error";
        int code = errObj.optInt("code", 0);
        String message = errObj.optString("message", "");
        Object data = errObj.opt("data");
        if (data == null) {
            return "code=" + code + ", message=" + message;
        }
        return "code=" + code + ", message=" + message + ", data=" + String.valueOf(data);
    }

    /**
     * 创建房间收据确认后：第二笔交易加入房间（质押通过 msg.value），成功后再进入等待页。
     */
    private void joinCreatedRoom(String roomAddress, BigInteger gameId, String createTxHash, String roomHostAddress) {
        try {
            String myStakeStr = etMyStake.getText().toString().trim();
            if (myStakeStr.isEmpty()) {
                Toast.makeText(this, "请填写本人质押后再加入", Toast.LENGTH_SHORT).show();
                return;
            }
            BigInteger minStake = toWei(etMinStake.getText().toString().trim());
            BigInteger maxStake = toWei(etMaxStake.getText().toString().trim());
            BigInteger myStakeWei = toWei(myStakeStr);
            if (myStakeWei.compareTo(minStake) < 0 || myStakeWei.compareTo(maxStake) > 0) {
                Toast.makeText(this, "本人质押须在最小与最大质押之间", Toast.LENGTH_SHORT).show();
                return;
            }

            String data = ABIUtils.encodeJoinGameV2();
            String from = getCurrentWalletAddress();
            JSONObject txParams = new JSONObject();
            txParams.put("from", from);
            txParams.put("to", roomAddress);
            txParams.put("data", data);
            txParams.put("value", "0x" + myStakeWei.toString(16));
            txParams.put("gas", "0x800000");

            if (GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX) {
                String pk = StorageUtil.getCurrentPrivatekey(this);
                if (pk == null || pk.trim().isEmpty()) {
                    Toast.makeText(this, "未找到当前账户私钥", Toast.LENGTH_LONG).show();
                    return;
                }
                new Thread(() -> {
                    String json = MyUtil.sendGameContractTxViaGateway(
                            pk.trim(),
                            roomAddress,
                            data,
                            "0x" + myStakeWei.toString(16),
                            "0x800000");
                    String joinTx = MyUtil.parseGatewayTxHash(json);
                    runOnUiThread(() -> {
                        if (joinTx != null) {
                            Log.i(TAG, "加入房间交易已提交（网关）：" + joinTx);
                            verifyTxFrom(joinTx, from, "joinCreatedRoomGateway");
                            Toast.makeText(GameMainActivity.this, "已提交加入房间", Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(GameMainActivity.this, GameRoomWaitActivity.class);
                            intent.putExtra("txHash", createTxHash);
                            intent.putExtra("gameId", gameId.toString());
                            intent.putExtra("roomAddress", roomAddress);
                            intent.putExtra("hostAddress", roomHostAddress);
                            startActivity(intent);
                        } else {
                            Toast.makeText(GameMainActivity.this, "加入失败：" + MyUtil.formatGatewayError(json), Toast.LENGTH_LONG).show();
                        }
                    });
                }).start();
                return;
            }

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
                            String joinTx = response.getString("result");
                            Log.i(TAG, "加入房间交易已提交：" + joinTx);
                            verifyTxFrom(joinTx, from, "joinCreatedRoom");
                            runOnUiThread(() -> {
                                Toast.makeText(GameMainActivity.this, "已提交加入房间", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(GameMainActivity.this, GameRoomWaitActivity.class);
                                intent.putExtra("txHash", createTxHash);
                                intent.putExtra("gameId", gameId.toString());
                                intent.putExtra("roomAddress", roomAddress);
                                intent.putExtra("hostAddress", roomHostAddress);
                                startActivity(intent);
                            });
                        } else {
                            String err = response.optJSONObject("error") == null ? "unknown"
                                    : response.optJSONObject("error").optString("message", "unknown");
                            Log.e(TAG, "加入房间失败：" + err);
                            runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "加入失败：" + err, Toast.LENGTH_LONG).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析加入结果异常", e);
                        runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "解析失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "加入房间网络错误", e);
                    runOnUiThread(() -> Toast.makeText(GameMainActivity.this, "网络错误：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "joinCreatedRoom 异常", e);
            Toast.makeText(this, "加入参数错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
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

                                // 创建成功后第二笔交易：joinGame（带质押），再进等待页
                                final BigInteger gid = gameId;
                                final String room = roomAddress;
                                final String host = hostAddress;
                                final String createTx = txHash;
                                runOnUiThread(() -> joinCreatedRoom(room, gid, createTx, host));
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

    private void verifyTxFrom(String txHash, String expectedFrom, String scene) {
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
                        Log.i(TAG, "地址校验(" + scene + ") expectedFrom=" + expectedFrom + ", actualFrom=" + actualFrom + ", txHash=" + txHash);
                        if (expectedFrom != null && !expectedFrom.isEmpty()
                                && actualFrom != null && !actualFrom.isEmpty()
                                && !expectedFrom.equalsIgnoreCase(actualFrom)) {
                            runOnUiThread(() -> Toast.makeText(
                                    GameMainActivity.this,
                                    "警告：链上交易发送地址与当前钱包不一致（节点可能使用默认解锁账户代发）",
                                    Toast.LENGTH_LONG
                            ).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "地址校验解析异常(" + scene + ")", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "verifyTxFrom 异常(" + scene + ")", e);
        }
    }
}