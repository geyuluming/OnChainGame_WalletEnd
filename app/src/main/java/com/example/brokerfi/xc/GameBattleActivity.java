package com.example.brokerfi.xc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
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
import java.util.Arrays;
import java.util.List;

public class GameBattleActivity extends AppCompatActivity {
    private static final String TAG = "GameBattleActivity";

    private TextView tvLog, tvResult, tvTurn, tvTarget, tvTargetHint, tvMyCards;
    private Spinner spCardSelect;
    private Button btnTakeCard;
    private ImageView ivCardPreview;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String gameId;
    private String roomAddress;
    private List<String> playerList;
    private String myAddress = "";
    private String currentTurnPlayer = "";
    private String currentTargetPlayer = "";
    private boolean isPolling = true;
    private final BigInteger[] myCards = new BigInteger[10];
    private BigInteger myJoker = BigInteger.ZERO;
    private final BigInteger[] targetCards = new BigInteger[10];
    private BigInteger targetJoker = BigInteger.ZERO;
    /** 本轮是否已从链上读到目标手牌；未读到前不拦截抽牌，避免误禁用。 */
    private boolean targetCardsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_battle);

        tvLog = findViewById(R.id.tv_log);
        tvResult = findViewById(R.id.tv_result);
        tvTurn = findViewById(R.id.tv_turn);
        tvTarget = findViewById(R.id.tv_target);
        tvTargetHint = findViewById(R.id.tv_target_hint);
        tvMyCards = findViewById(R.id.tv_my_cards);
        spCardSelect = findViewById(R.id.sp_card_select);
        btnTakeCard = findViewById(R.id.btn_take_card);
        ivCardPreview = findViewById(R.id.iv_card_preview);

        gameId = getIntent().getStringExtra("gameId");
        roomAddress = getIntent().getStringExtra("roomAddress");
        playerList = getIntent().getStringArrayListExtra("playerList");
        myAddress = getCurrentWalletAddress();
        Arrays.fill(myCards, BigInteger.ZERO);
        Arrays.fill(targetCards, BigInteger.ZERO);

        if (playerList == null || playerList.isEmpty()) {
            Toast.makeText(this, "玩家列表为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initCardSelector();
        btnTakeCard.setOnClickListener(v -> onTakeCardClicked());

        appendLog("========== 游戏开始 ==========");
        appendLog("游戏ID：" + gameId);
        appendLog("房间地址：" + roomAddress);
        handler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            pollTurnAndCards();
            queryGameResult();
            handler.postDelayed(this, 3000);
        }
    };

    private void pollTurnAndCards() {
        try {
            String data = ABIUtils.encodeGetCurrentTurnPlayer();
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
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
                        currentTurnPlayer = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        runOnUiThread(() -> tvTurn.setText("当前回合：" + shortAddr(currentTurnPlayer)));
                        updateTakeButtonState();
                        queryTakeTarget();
                        queryMyCards();
                    } catch (Exception e) {
                        Log.e(TAG, "轮次解析异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    Log.e(TAG, "轮询失败", e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "pollTurnAndCards异常", e);
        }
    }

    private void queryTakeTarget() {
        try {
            String data = ABIUtils.encodeGetTakeTarget();
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
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
                        currentTargetPlayer = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        runOnUiThread(() -> tvTarget.setText("抽牌目标：" + shortAddr(currentTargetPlayer)));
                        queryTargetPlayerCards();
                    } catch (Exception e) {
                        Log.e(TAG, "解析目标异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryTakeTarget异常", e);
        }
    }

    /** 查询当前抽牌目标的手牌数量，用于提示可抽选项（与链上 takeCard 一致）。 */
    private void queryTargetPlayerCards() {
        if (currentTargetPlayer == null || currentTargetPlayer.length() < 10) return;
        targetCardsReady = false;
        try {
            String data = ABIUtils.encodeGetPlayerCards(currentTargetPlayer);
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
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
                        BigInteger[] cards = ABIUtils.decodeUint256Array(raw, 0, 10);
                        for (int i = 0; i < 10; i++) targetCards[i] = cards[i];
                        targetJoker = ABIUtils.decodeUint256(raw, 10);
                        targetCardsReady = true;
                        runOnUiThread(() -> {
                            tvTargetHint.setText(buildTargetHintText());
                            updateTakeButtonState();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "解析目标手牌异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryTargetPlayerCards异常", e);
        }
    }

    private String buildTargetHintText() {
        StringBuilder sb = new StringBuilder("目标可抽牌：");
        boolean any = false;
        if (targetJoker != null && targetJoker.compareTo(BigInteger.ZERO) > 0) {
            sb.append("小丑×").append(targetJoker).append(" ");
            any = true;
        }
        for (int i = 0; i < 10; i++) {
            if (targetCards[i] != null && targetCards[i].compareTo(BigInteger.ZERO) > 0) {
                sb.append(i + 1).append("号×").append(targetCards[i]).append(" ");
                any = true;
            }
        }
        if (!any) sb.append("无");
        if (myAddress.equalsIgnoreCase(currentTurnPlayer)) {
            sb.append("\n轮到你时，请在上方选择目标实际拥有的一张牌（数字或小丑）。");
        }
        return sb.toString();
    }

    private void queryMyCards() {
        try {
            String data = ABIUtils.encodeGetPlayerCards(myAddress);
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
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
                        BigInteger[] cards = ABIUtils.decodeUint256Array(raw, 0, 10);
                        for (int i = 0; i < 10; i++) myCards[i] = cards[i];
                        myJoker = ABIUtils.decodeUint256(raw, 10);
                        runOnUiThread(() -> {
                            tvMyCards.setText(buildMyCardsText());
                            updateTakeButtonState();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "解析我的手牌异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryMyCards异常", e);
        }
    }

    private void onTakeCardClicked() {
        if (!myAddress.equalsIgnoreCase(currentTurnPlayer)) {
            Toast.makeText(this, "还没轮到你", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int cardNumber = spCardSelect.getSelectedItemPosition();
            if (!canTakeSelectedCard(cardNumber)) {
                Toast.makeText(this, "目标没有这张牌，请重选", Toast.LENGTH_SHORT).show();
                return;
            }
            String data = ABIUtils.encodeTakeCard(cardNumber);

            JSONObject txParams = new JSONObject();
            txParams.put("from", myAddress);
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

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, request.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("result")) {
                            appendLog("抽牌交易已提交：" + res.getString("result"));
                        } else {
                            String err = res.optJSONObject("error") == null ? "unknown"
                                    : res.optJSONObject("error").optString("message", "unknown");
                            appendLog("抽牌失败：" + err);
                        }
                    } catch (Exception e) {
                        appendLog("抽牌返回解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("抽牌网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("抽牌调用失败：" + e.getMessage());
        }
    }

    private void queryGameResult() {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", roomAddress);
            JSONArray topics = new JSONArray();
            topics.put("0x" + ABIUtils.getEventSignatureHash("GameEnded(uint256,address[],address[])"));
            topics.put("0x" + String.format("%064x", new BigInteger(gameId)));
            filter.put("topics", topics);
            filter.put("fromBlock", "0x0");
            filter.put("toBlock", "latest");

            JSONArray params = new JSONArray();
            params.put(filter);
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        JSONArray logs = res.getJSONArray("result");
                        if (logs.length() <= 0) return;

                        isPolling = false;
                        handler.removeCallbacksAndMessages(null);
                        JSONObject log = logs.getJSONObject(0);
                        String data = log.getString("data");
                        List<String> losers = new ArrayList<>();
                        List<String> winners = new ArrayList<>();
                        ABIUtils.decodeGameEndedLosersWinners(data, losers, winners);

                        appendLog("\n========== 游戏结果 ==========");
                        appendLog("失败者：" + formatAddrList(losers));
                        appendLog("获胜者：" + formatAddrList(winners));
                        tvResult.setText("本局已结束");
                        btnTakeCard.setEnabled(false);
                        queryRewardsDistributed(winners);
                    } catch (Exception e) {
                        Log.e(TAG, "解析结果异常", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryGameResult异常", e);
        }
    }

    /**
     * StakingVault: RewardsDistributed(uint256 indexed gameId, uint256 totalRewards, uint256 fee)
     * totalRewards 为扣费后分给获胜者的奖池；fee 为协议手续费。
     */
    private void queryRewardsDistributed(List<String> winners) {
        queryRewardsDistributed(winners, 0);
    }

    private void queryRewardsDistributed(final List<String> winners, final int attempt) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", GameConfig.STAKING_VAULT_ADDRESS);
            JSONArray topics = new JSONArray();
            topics.put("0x" + ABIUtils.getEventSignatureHash("RewardsDistributed(uint256,uint256,uint256)"));
            topics.put("0x" + String.format("%064x", new BigInteger(gameId)));
            filter.put("topics", topics);
            filter.put("fromBlock", "0x0");
            filter.put("toBlock", "latest");

            JSONArray params = new JSONArray();
            params.put(filter);
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        JSONArray logs = res.optJSONArray("result");
                        if (logs == null || logs.length() <= 0) {
                            if (attempt < 4) {
                                handler.postDelayed(() -> queryRewardsDistributed(winners, attempt + 1), 2000);
                                return;
                            }
                            appendLog("\n========== 链上分配 ==========");
                            appendLog("未查到 RewardsDistributed 事件（已重试仍无，可检查 Vault 地址与 gameId）");
                            return;
                        }
                        JSONObject log = logs.getJSONObject(logs.length() - 1);
                        String data = log.getString("data");
                        BigInteger rewardPool = ABIUtils.decodeUint256(data, 0);
                        BigInteger fee = ABIUtils.decodeUint256(data, 1);
                        BigInteger totalBeforeFee = rewardPool.add(fee);

                        appendLog("\n========== 链上分配（StakingVault） ==========");
                        appendLog("本局奖池（扣费前合计）：" + fromWei(totalBeforeFee) + " BKC");
                        appendLog("协议手续费：" + fromWei(fee) + " BKC");
                        appendLog("分给获胜者池（扣费后）：" + fromWei(rewardPool) + " BKC");
                        appendLog("说明：上述获胜者池按各获胜者在 Vault 中的质押比例在赢家之间分配。");
                        if (winners != null && !winners.isEmpty()) {
                            appendLog("获胜者地址数：" + winners.size());
                        }

                        String summary = "已结束 · 手续费 " + fromWeiShort(fee) + " · 分配池 " + fromWeiShort(rewardPool);
                        runOnUiThread(() -> tvResult.setText(summary));
                    } catch (Exception e) {
                        Log.e(TAG, "解析 RewardsDistributed 异常", e);
                        appendLog("解析分配事件失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("查询 RewardsDistributed 失败：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryRewardsDistributed异常", e);
        }
    }

    private String fromWei(BigInteger wei) {
        if (wei == null) return "0";
        return wei.divide(new BigInteger("1000000000000000000")).toString() + "." +
                String.format("%018d", wei.mod(new BigInteger("1000000000000000000"))).substring(0, 4);
    }

    /** 结果条简短展示 */
    private String fromWeiShort(BigInteger wei) {
        if (wei == null) return "0";
        return wei.divide(new BigInteger("1000000000000000000")).toString() + "." +
                String.format("%018d", wei.mod(new BigInteger("1000000000000000000"))).substring(0, 2) + "…";
    }

    private void initCardSelector() {
        List<String> options = new ArrayList<>();
        options.add("小丑牌(0)");
        for (int i = 1; i <= 10; i++) options.add(i + "号牌");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCardSelect.setAdapter(adapter);
        spCardSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ivCardPreview.setImageResource(getCardResId(position));
                updateTakeButtonState();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private int getCardResId(int cardNumber) {
        if (cardNumber == 0) return R.drawable.joker_a;
        switch (cardNumber) {
            case 1: return R.drawable.spade1;
            case 2: return R.drawable.spade2;
            case 3: return R.drawable.spade3;
            case 4: return R.drawable.spade4;
            case 5: return R.drawable.spade5;
            case 6: return R.drawable.spade6;
            case 7: return R.drawable.spade7;
            case 8: return R.drawable.spade8;
            case 9: return R.drawable.spade9;
            case 10: return R.drawable.spade10;
            default: return R.drawable.joker_b;
        }
    }

    private String buildMyCardsText() {
        StringBuilder sb = new StringBuilder("我的手牌：");
        for (int i = 0; i < 10; i++) {
            if (myCards[i] != null && myCards[i].compareTo(BigInteger.ZERO) > 0) {
                sb.append(" ").append(i + 1).append("x").append(myCards[i]);
            }
        }
        if (myJoker.compareTo(BigInteger.ZERO) > 0) sb.append(" 小丑x").append(myJoker);
        if ("我的手牌：".contentEquals(sb)) sb.append("无");
        return sb.toString();
    }

    private void updateTakeButtonState() {
        boolean myTurn = myAddress.equalsIgnoreCase(currentTurnPlayer);
        int sel = spCardSelect.getSelectedItemPosition();
        boolean valid = !myTurn || canTakeSelectedCard(sel);
        btnTakeCard.setEnabled(myTurn && valid);
        if (!myTurn) btnTakeCard.setText("等待回合");
        else if (!valid) btnTakeCard.setText("目标无此牌");
        else btnTakeCard.setText(targetCardsReady ? "抽牌" : "加载中…");
    }

    /** 与链上 takeCard(uint8) 一致：0=小丑，1–10=数字牌。 */
    private boolean canTakeSelectedCard(int cardNumber) {
        if (!targetCardsReady) return true;
        if (cardNumber == 0) {
            return targetJoker != null && targetJoker.compareTo(BigInteger.ZERO) > 0;
        }
        if (cardNumber < 1 || cardNumber > 10) return false;
        BigInteger c = targetCards[cardNumber - 1];
        return c != null && c.compareTo(BigInteger.ZERO) > 0;
    }

    private String formatAddrList(List<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String a : addrs) sb.append("\n  ").append(a);
        return sb.toString();
    }

    private String shortAddr(String addr) {
        if (addr == null || addr.length() < 10) return addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }

    private void appendLog(String text) {
        runOnUiThread(() -> tvLog.setText(tvLog.getText() + "\n" + text));
    }

    private String getCurrentWalletAddress() {
        try {
            String acc = StorageUtil.getCurrentAccount(this);
            int index = (acc == null) ? 0 : Integer.parseInt(acc);
            String allKeys = StorageUtil.getPrivateKey(this);
            String[] keys = allKeys.split(";");
            String address = SecurityUtil.GetAddress(keys[index]);
            if (!address.startsWith("0x")) address = "0x" + address;
            return address;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPolling = false;
        handler.removeCallbacksAndMessages(null);
    }
}