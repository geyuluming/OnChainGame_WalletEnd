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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

public class GameJoinActivity extends AppCompatActivity {
    private static final String TAG = "GameJoin_DEBUG";
    private static final boolean USE_RAW_TX = true;
    private static final BigInteger GAS_FALLBACK = new BigInteger("800000", 16);
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

                        String rawResult = response.getString("result");
                        if (rawResult == null || rawResult.equals("0x") || rawResult.equals("0x0")) {
                            Log.e(TAG, "❌ 返回为空/过短：" + rawResult);
                            runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "游戏不存在或节点未同步", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        // 解析返回的房间地址
                        String roomAddress = ABIUtils.decodeAddress(rawResult);
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

            if (GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX) {
                String pk = StorageUtil.getCurrentPrivatekey(this);
                if (pk == null || pk.trim().isEmpty()) {
                    Toast.makeText(this, "未找到当前账户私钥", Toast.LENGTH_LONG).show();
                    return;
                }
                final String expectedFrom = txParams.optString("from", "");
                new Thread(() -> {
                    String json = MyUtil.sendGameContractTxViaGateway(
                            pk.trim(),
                            roomAddress,
                            data,
                            "0x" + stakeAmount.toString(16),
                            "0x800000");
                    String txHash = MyUtil.parseGatewayTxHash(json);
                    runOnUiThread(() -> {
                        if (txHash != null) {
                            Log.i(TAG, "✅ 加入游戏（网关）提交成功！Hash：" + txHash);
                            verifyTxFrom(txHash, expectedFrom);
                            Toast.makeText(GameJoinActivity.this, "加入游戏交易已提交！Hash：" + txHash, Toast.LENGTH_LONG).show();
                            Intent intent = new Intent(GameJoinActivity.this, GameRoomWaitActivity.class);
                            intent.putExtra("gameId", gameId.toString());
                            intent.putExtra("roomAddress", roomAddress);
                            startActivity(intent);
                        } else {
                            Toast.makeText(GameJoinActivity.this, "加入失败：" + MyUtil.formatGatewayError(json), Toast.LENGTH_LONG).show();
                        }
                    });
                }).start();
                return;
            }

            if (USE_RAW_TX) {
                sendJoinTxRawSigned(txParams, txHash -> {
                    String expectedFrom = txParams.optString("from", "");
                    Log.i(TAG, "✅ 加入游戏 rawTx 提交成功！Hash：" + txHash);
                    verifyTxFrom(txHash, expectedFrom);
                    runOnUiThread(() -> {
                        Toast.makeText(GameJoinActivity.this, "加入游戏交易已提交！Hash：" + txHash, Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(GameJoinActivity.this, GameRoomWaitActivity.class);
                        intent.putExtra("gameId", gameId.toString());
                        intent.putExtra("roomAddress", roomAddress);
                        startActivity(intent);
                    });
                }, () -> {
                    Log.e(TAG, "rawTx 发送失败，已停止（避免节点默认账户代发）");
                    runOnUiThread(() -> Toast.makeText(GameJoinActivity.this,
                            "rawTx 不可用；可开启 GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX 走网关",
                            Toast.LENGTH_LONG).show());
                });
                return;
            }

            sendJoinTxBySendTransaction(txParams, gameId, roomAddress);
        } catch (Exception e) {
            Log.e(TAG, "发送加入交易异常：", e);
            runOnUiThread(() -> Toast.makeText(this, "发送交易失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private interface TxHashCallback {
        void onTxHash(String txHash);
    }

    private void sendJoinTxBySendTransaction(JSONObject txParams, BigInteger gameId, String roomAddress) {
        try {
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
                            String expectedFrom = txParams.optString("from", "");
                            Log.i(TAG, "✅ 加入游戏交易提交成功！Hash：" + txHash);
                            verifyTxFrom(txHash, expectedFrom);

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
            Log.e(TAG, "sendJoinTxBySendTransaction 异常：", e);
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

    /**
     * 用本地私钥签名并通过 eth_sendRawTransaction 发送 joinGame。
     */
    private void sendJoinTxRawSigned(JSONObject txParams, TxHashCallback cb, Runnable onFallback) {
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
                runOnUiThread(() -> Toast.makeText(GameJoinActivity.this, "未找到当前账户私钥，无法签名发送", Toast.LENGTH_LONG).show());
                return;
            }
            Credentials credentials = Credentials.create(privateKeyHex);

            AtomicReference<BigInteger> chainIdRef = new AtomicReference<>(BigInteger.ZERO);
            AtomicReference<BigInteger> nonceRef = new AtomicReference<>(BigInteger.ZERO);
            AtomicReference<BigInteger> gasPriceRef = new AtomicReference<>(BigInteger.ZERO);

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
                            chainIdRef.set(new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16));
                        }
                    } catch (Exception ignored) {
                    }

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
                                } catch (Exception ignored) {
                                }

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
                                            } catch (Exception ignored) {
                                                gasPriceRef.set(BigInteger.ZERO);
                                            }

                                            try {
                                                BigInteger nonce = nonceRef.get();
                                                BigInteger gasPrice = gasPriceRef.get();
                                                BigInteger chainId = chainIdRef.get();

                                                Log.i(TAG, String.format(Locale.US,
                                                        "准备发送 join rawTx: nonce=%s gasPrice=%s gasLimit=%s value=%s chainId=%s to=%s",
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

                                                OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, sendReq.toString(), new MyCallBack() {
                                                    @Override
                                                    public void onSuccess(String result) {
                                                        try {
                                                            JSONObject resp = new JSONObject(result);
                                                            if (resp.has("result")) {
                                                                cb.onTxHash(resp.getString("result"));
                                                            } else {
                                                                String err = resp.optJSONObject("error") == null ? "unknown"
                                                                        : resp.optJSONObject("error").optString("message", "unknown");
                                                                Log.e(TAG, "sendRawTransaction 失败：" + err);
                                                                if (onFallback != null) onFallback.run();
                                                            }
                                                        } catch (Exception e) {
                                                            Log.e(TAG, "解析 sendRawTransaction 返回失败", e);
                                                            if (onFallback != null) onFallback.run();
                                                        }
                                                    }

                                                    @Override
                                                    public Void onError(Exception e) {
                                                        Log.e(TAG, "sendRawTransaction 网络错误", e);
                                                        if (onFallback != null) onFallback.run();
                                                        return null;
                                                    }
                                                });
                                            } catch (Exception e) {
                                                Log.e(TAG, "签名/发送 rawTx 异常", e);
                                                if (onFallback != null) onFallback.run();
                                            }
                                        }

                                        @Override
                                        public Void onError(Exception e) {
                                            if (onFallback != null) onFallback.run();
                                            return null;
                                        }
                                    });
                                } catch (Exception e) {
                                    if (onFallback != null) onFallback.run();
                                }
                            }

                            @Override
                            public Void onError(Exception e) {
                                if (onFallback != null) onFallback.run();
                                return null;
                            }
                        });
                    } catch (Exception e) {
                        if (onFallback != null) onFallback.run();
                    }
                }

                @Override
                public Void onError(Exception e) {
                    // chainId 拉不到也继续（chainId=0）
                    onSuccess("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x0\"}");
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "sendJoinTxRawSigned 异常", e);
            if (onFallback != null) onFallback.run();
        }
    }

    /**
     * 交易提交后校验链上实际 from。
     * 若与当前选中钱包不一致，通常表示节点忽略了 eth_sendTransaction 的 from 字段，
     * 使用了默认解锁账户来代发。
     */
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
                        Log.i(TAG, "地址校验：expectedFrom=" + expectedFrom + ", actualFrom=" + actualFrom + ", txHash=" + txHash);
                        if (expectedFrom != null && !expectedFrom.isEmpty()
                                && actualFrom != null && !actualFrom.isEmpty()
                                && !expectedFrom.equalsIgnoreCase(actualFrom)) {
                            runOnUiThread(() -> Toast.makeText(
                                    GameJoinActivity.this,
                                    "警告：链上交易发送地址与当前钱包不一致（节点可能使用默认解锁账户代发）",
                                    Toast.LENGTH_LONG
                            ).show());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "地址校验解析异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "地址校验网络错误", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "verifyTxFrom 异常", e);
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