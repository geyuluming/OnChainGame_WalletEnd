package com.example.brokerfi.xc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 战斗页：自己手牌正面扇出；他人牌背 +「?」，顺序与链上 handDisplaySeed 及 multiset 一致（与本人视角相同）。
 * 轮到自己时点击「下家」对应行的牌背发起 takeCard。
 */
public class GameBattleActivity extends AppCompatActivity {
    private static final String TAG = "GameBattleActivity";
    /** 与合约 enum GameState 一致：ENDED = 3 */
    private static final BigInteger GAME_STATE_ENDED = BigInteger.valueOf(3);
    /** 与 StakingVault 保持一致：FEE_RATE = 10/1000 (1%) */
    private static final BigInteger VAULT_FEE_RATE_PER_MILLE = BigInteger.TEN;
    private static final BigInteger PER_MILLE_BASE = BigInteger.valueOf(1000);

    private TextView tvLog, tvResult, tvTurn, tvTargetHint;
    private LinearLayout llOpponents, llMyHand;
    private ScrollView scrollBattleRoot;
    private LinearLayout layoutRewardPanel;
    private TextView tvRewardStatus, tvRewardLinePool, tvRewardLineFee, tvRewardLineWinner, tvRewardLineExtra;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String gameId;
    private String roomAddress;
    private List<String> playerList;
    private String myAddress = "";
    private String currentTurnPlayer = "";
    private String currentTargetPlayer = "";
    private boolean isPolling = true;
    private boolean gameOver = false;
    private volatile boolean isSendingTake = false;
    // 为了避免 eth_call 轮询过快导致刷新手牌链路被不断打断，
    // 增加锁：刷新期间不再触发新的 queryTakeTarget 刷新。
    private volatile boolean isRefreshingHands = false;
    private volatile long refreshHandsStartedAtMs = 0L;
    /** 丢弃过期的异步刷新链，避免轮询重叠导致 UI 错乱 */
    private volatile int refreshSeq = 0;

    /** 终局检测 eth_call，与手牌刷新队列解耦，避免被动玩家永远等不到 queryGameResult */
    private final AtomicBoolean roomGameStatePollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean gameEndedBroadLogsInFlight = new AtomicBoolean(false);

    /** eth_call GameRoom.gameId() 缓存；链上事件 indexed topic 必须以该值为准，Intent 可能与之一致也可能不一致 */
    private volatile BigInteger chainRoomGameId = null;
    /** 最近一次终局解码出的获胜者，用于同一笔交易回执里的 Rewards 展示 */
    private List<String> lastSettledWinners = null;
    /** 本局最后一笔己方提交的抽牌 txHash，eth_getLogs 失败时用于回执解析 */
    private String lastSubmittedTakeTxHash = null;
    /** 已展示具体 BKC 数额则不再用「奖池已结清」占位覆盖 */
    private volatile boolean rewardPanelHasConcreteAmounts = false;

    private View scrollDebugLog;

    private final Map<String, PlayerHandCache> handCache = new HashMap<>();
    private final ArrayDeque<String> uiLogLines = new ArrayDeque<>();
    private static final int UI_LOG_MAX = 36;

    private static final class PlayerHandCache {
        BigInteger[] hand10 = new BigInteger[10];
        BigInteger joker = BigInteger.ZERO;
        BigInteger seed = BigInteger.ZERO;
        List<Integer> order = new ArrayList<>();
    }

    private interface StakeResultCallback {
        void onResult(BigInteger stake);
    }

    private interface JokerResultCallback {
        void onResult(BigInteger jokerCount);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_battle);

        scrollBattleRoot = findViewById(R.id.scroll_battle_root);
        scrollDebugLog = findViewById(R.id.scroll_debug_log);
        tvLog = findViewById(R.id.tv_log);
        tvResult = findViewById(R.id.tv_result);
        tvTurn = findViewById(R.id.tv_turn);
        tvTargetHint = findViewById(R.id.tv_target_hint);
        llOpponents = findViewById(R.id.ll_opponents);
        llMyHand = findViewById(R.id.ll_my_hand);
        layoutRewardPanel = findViewById(R.id.layout_reward_panel);
        tvRewardStatus = findViewById(R.id.tv_reward_status);
        tvRewardLinePool = findViewById(R.id.tv_reward_line_pool);
        tvRewardLineFee = findViewById(R.id.tv_reward_line_fee);
        tvRewardLineWinner = findViewById(R.id.tv_reward_line_winner);
        tvRewardLineExtra = findViewById(R.id.tv_reward_line_extra);

        // 防止返回栈/重入导致看到上一局残留 UI
        synchronized (handCache) {
            handCache.clear();
        }
        llOpponents.removeAllViews();
        llMyHand.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("加载中…");
        llMyHand.addView(loading);

        gameId = getIntent().getStringExtra("gameId");
        roomAddress = getIntent().getStringExtra("roomAddress");
        playerList = getIntent().getStringArrayListExtra("playerList");
        myAddress = getCurrentWalletAddress();

        if (playerList == null || playerList.isEmpty()) {
            Toast.makeText(this, "玩家列表为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        appendLog("========== 游戏开始 ==========");
        appendLog("游戏ID：" + gameId);
        appendLog("房间地址：" + roomAddress);
        lastSettledWinners = null;
        lastSubmittedTakeTxHash = null;
        rewardPanelHasConcreteAmounts = false;
        if (scrollDebugLog != null) scrollDebugLog.setVisibility(View.VISIBLE);
        fetchChainRoomGameId(() -> { });
        handler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            // 看门狗：如果刷新手牌卡住很久，允许继续轮询（避免“永远加载中”）
            if (isRefreshingHands) {
                long now = System.currentTimeMillis();
                if (now - refreshHandsStartedAtMs > 20_000L) {
                    Log.w(TAG, "手牌刷新超时，解除刷新锁以继续轮询");
                    isRefreshingHands = false;
                }
            }
            if (!gameOver) {
                pollRoomGameEndedState();
            }
            if (!isRefreshingHands) {
                pollTurnAndCards();
                queryGameResult();
            }
            handler.postDelayed(this, 3000);
        }
    };

    private void pollTurnAndCards() {
        if (gameOver) return;
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
                        queryTakeTarget();
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
                        runOnUiThread(() -> updateTargetHint());
                        isRefreshingHands = true;
                        refreshHandsStartedAtMs = System.currentTimeMillis();
                        final int seq = ++refreshSeq;
                        refreshAllPlayerHandsSequentially(0, seq);
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

    private void updateTargetHint() {
        if (gameOver) return;
        String t = shortAddr(currentTargetPlayer);
        if (myAddress.equalsIgnoreCase(currentTurnPlayer)) {
            tvTargetHint.setText("轮到你 · 下家 " + t + " · 点击其牌背抽一张（顺序与TA自己手牌一致）");
        } else {
            tvTargetHint.setText("等待 " + shortAddr(currentTurnPlayer) + " · 下家 " + t);
        }
    }

    /** 依次拉取每位玩家手牌 + 展示种子，避免并发乱序写缓存 */
    private void refreshAllPlayerHandsSequentially(int index, int seq) {
        if (seq != refreshSeq) return;
        if (gameOver || playerList == null || index >= playerList.size()) {
            if (seq != refreshSeq) return;
            runOnUiThread(this::rebuildAllHandsUi);
            return;
        }
        fetchPlayerHand(playerList.get(index), () -> refreshAllPlayerHandsSequentially(index + 1, seq), seq);
    }

    private void fetchPlayerHand(String player, Runnable next, int seq) {
        try {
            String data = ABIUtils.encodeGetPlayerCards(player);
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
                        BigInteger joker = ABIUtils.decodeUint256(raw, 10);
                        fetchHandSeed(player, cards, joker, next, seq);
                    } catch (Exception e) {
                        Log.e(TAG, "getPlayerCards " + player, e);
                        if (seq == refreshSeq && next != null) next.run();
                    }
                }

                @Override
                public Void onError(Exception e) {
                    if (seq == refreshSeq && next != null) next.run();
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchPlayerHand", e);
            if (seq == refreshSeq && next != null) next.run();
        }
    }

    private void fetchHandSeed(String player, BigInteger[] cards, BigInteger joker, Runnable next, int seq) {
        try {
            String data = ABIUtils.encodeHandDisplaySeed(player);
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
                        BigInteger seed = ABIUtils.decodeUint256(raw, 0);
                        PlayerHandCache c = new PlayerHandCache();
                        System.arraycopy(cards, 0, c.hand10, 0, 10);
                        c.joker = joker != null ? joker : BigInteger.ZERO;
                        c.seed = seed;
                        c.order = HandOrderHelper.shuffleMultiset(c.hand10, c.joker, c.seed);
                        if (seq == refreshSeq) {
                            synchronized (handCache) {
                                handCache.put(player.toLowerCase(), c);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "handDisplaySeed " + player, e);
                    }
                    if (seq == refreshSeq && next != null) next.run();
                }

                @Override
                public Void onError(Exception e) {
                    if (seq == refreshSeq && next != null) next.run();
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchHandSeed", e);
            if (seq == refreshSeq && next != null) next.run();
        }
    }

    private PlayerHandCache cacheFor(String addr) {
        if (addr == null) return null;
        synchronized (handCache) {
            return handCache.get(addr.toLowerCase());
        }
    }

    private void rebuildAllHandsUi() {
        if (gameOver) {
            isRefreshingHands = false;
            return;
        }
        llOpponents.removeAllViews();
        llMyHand.removeAllViews();

        boolean myTurn = myAddress.equalsIgnoreCase(currentTurnPlayer);
        String targetKey = currentTargetPlayer != null ? currentTargetPlayer.toLowerCase() : "";

        for (String p : playerList) {
            if (p.equalsIgnoreCase(myAddress)) continue;

            PlayerHandCache cache = cacheFor(p);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int m = dp(6);
            row.setPadding(0, m, 0, m);

            TextView label = new TextView(this);
            boolean isTarget = p.equalsIgnoreCase(currentTargetPlayer);
            label.setText(shortAddr(p) + (isTarget ? "  ← 抽牌目标" : ""));
            label.setTextSize(13f);
            row.addView(label);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout cardRow = new LinearLayout(this);
            cardRow.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(cardRow);

            if (cache == null || cache.order.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("加载中…");
                cardRow.addView(empty);
            } else {
                for (int slot = 0; slot < cache.order.size(); slot++) {
                    int cardNum = cache.order.get(slot);
                    View back = createCardBackView(p, slot, cardNum, myTurn && isTarget);
                    cardRow.addView(back);
                }
            }
            row.addView(hsv);
            llOpponents.addView(row);
        }

        PlayerHandCache mine = cacheFor(myAddress);
        if (mine == null || mine.order.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("我的手牌加载中…");
            llMyHand.addView(tv);
        } else {
            for (int cardNum : mine.order) {
                llMyHand.addView(createCardFrontView(cardNum));
            }
        }
        // 刷新链完成，释放锁
        isRefreshingHands = false;
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View createCardBackView(String ownerAddr, int slotIndex, int cardNumber, boolean clickable) {
        int w = dp(46);
        int h = dp(70);
        FrameLayout fl = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        fl.setLayoutParams(lp);
        fl.setBackgroundResource(R.drawable.card_back_bg);

        TextView tv = new TextView(this);
        tv.setText("?");
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fl.addView(tv, tlp);

        fl.setAlpha(clickable ? 1f : 0.88f);
        // 始终接收点击：即便当前不可抽，也给出明确提示，避免“点击没反应”的体验。
        fl.setOnClickListener(v -> {
            pulseView(v);
            onOpponentCardBackClicked(ownerAddr, slotIndex, cardNumber);
        });
        return fl;
    }

    private ImageView createCardFrontView(int cardNumber) {
        ImageView iv = new ImageView(this);
        int w = dp(50);
        int h = dp(74);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageResource(getCardResId(cardNumber));
        return iv;
    }

    private void onOpponentCardBackClicked(String ownerAddr, int slotIndex, int cardNumber) {
        if (gameOver) {
            Toast.makeText(this, "本局已结束，无法再抽牌", Toast.LENGTH_SHORT).show();
            appendLog("点击抽牌被拦截：gameOver=true");
            return;
        }
        if (isSendingTake) {
            Toast.makeText(this, "正在抽牌中，请稍等", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!myAddress.equalsIgnoreCase(currentTurnPlayer)) {
            Toast.makeText(this, "还没轮到你", Toast.LENGTH_SHORT).show();
            appendLog("点击抽牌被拦截：!turn，currentTurn=" + shortAddr(currentTurnPlayer) + ", me=" + shortAddr(myAddress));
            return;
        }
        if (!ownerAddr.equalsIgnoreCase(currentTargetPlayer)) {
            Toast.makeText(this, "只能抽下家的牌", Toast.LENGTH_SHORT).show();
            appendLog("点击抽牌被拦截：!target，clicked=" + shortAddr(ownerAddr) + ", target=" + shortAddr(currentTargetPlayer));
            return;
        }
        PlayerHandCache c = cacheFor(ownerAddr);
        if (c == null || slotIndex < 0 || slotIndex >= c.order.size()) {
            Toast.makeText(this, "手牌数据未就绪", Toast.LENGTH_SHORT).show();
            appendLog("点击抽牌被拦截：cache未就绪或slot越界，slot=" + slotIndex);
            return;
        }
        if (c.order.get(slotIndex) != cardNumber) {
            Toast.makeText(this, "请重试", Toast.LENGTH_SHORT).show();
            appendLog("点击抽牌被拦截：slot/card不一致，slot=" + slotIndex + ", expect=" + c.order.get(slotIndex) + ", got=" + cardNumber);
            return;
        }
        appendLog("[SNAPSHOT] targetBefore " + shortAddr(ownerAddr) + " " + briefHandFromCache(ownerAddr));
        isSendingTake = true;
        appendLog("点击抽牌：target=" + shortAddr(ownerAddr) + ", slot=" + slotIndex + ", card=" + cardNumber);
        sendTakeCard(ownerAddr, cardNumber);
    }

    private void pulseView(View v) {
        v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(60).start())
                .start();
    }

    private void sendTakeCard(String target, int cardNumber) {
        try {
            String data = ABIUtils.encodeTakeCard(target, cardNumber);
            appendLog("[TAKE] mode=" + (GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX ? "gateway-send" : "rpc-send")
                    + ", rpc=" + GameConfig.BROKERCHAIN_RPC
                    + ", room=" + shortAddr(roomAddress)
                    + ", me=" + shortAddr(myAddress)
                    + ", turn=" + shortAddr(currentTurnPlayer)
                    + ", target=" + shortAddr(currentTargetPlayer));
            probeRpcChainSnapshot();
            precheckTakeCardThenSend(target, cardNumber, data);
        } catch (Exception e) {
            appendLog("抽牌调用失败：" + e.getMessage());
            isSendingTake = false;
        }
    }

    /** 先 eth_call 预检 takeCard，提前拿到 revert 原因，避免“点击无反馈”。 */
    private void precheckTakeCardThenSend(String target, int cardNumber, String data) {
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", data);
            callParams.put("value", "0x0");

            JSONArray callRpcParams = new JSONArray();
            callRpcParams.put(callParams);
            callRpcParams.put("latest");

            JSONObject callReq = new JSONObject();
            callReq.put("jsonrpc", "2.0");
            callReq.put("method", "eth_call");
            callReq.put("params", callRpcParams);
            callReq.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, callReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            String err = formatRpcError(res.optJSONObject("error"));
                            appendLog("抽牌预检失败（revert）：" + err);
                            runOnUiThread(() -> Toast.makeText(GameBattleActivity.this, "抽牌失败：" + err, Toast.LENGTH_LONG).show());
                            isSendingTake = false;
                            return;
                        }
                        appendLog("抽牌预检通过：target=" + shortAddr(target) + ", card=" + cardNumber);
                        sendTakeCardTransaction(target, cardNumber, data);
                    } catch (Exception e) {
                        appendLog("抽牌预检解析失败：" + e.getMessage());
                        isSendingTake = false;
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("抽牌预检网络错误：" + e.getMessage());
                    isSendingTake = false;
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("抽牌预检调用失败：" + e.getMessage());
            isSendingTake = false;
        }
    }

    private void sendTakeCardTransaction(String target, int cardNumber, String data) {
        try {
            JSONObject txParams = new JSONObject();
            txParams.put("from", myAddress);
            txParams.put("to", roomAddress);
            txParams.put("data", data);
            txParams.put("value", "0x0");
            txParams.put("gas", "0x800000");

            if (GameConfig.USE_HTTP_GATEWAY_FOR_GAME_TX) {
                String pk = StorageUtil.getCurrentPrivatekey(this);
                if (pk == null || pk.trim().isEmpty()) {
                    appendLog("无当前私钥，无法走网关抽牌");
                    isSendingTake = false;
                    return;
                }
                String sender = "";
                try {
                    sender = SecurityUtil.GetAddress(pk.trim());
                    if (!sender.startsWith("0x")) sender = "0x" + sender;
                } catch (Exception ignore) {}
                appendLog("[TAKE] gatewaySender=" + shortAddr(sender));
                new Thread(() -> {
                    String json = MyUtil.sendGameContractTxViaGateway(
                            pk.trim(),
                            roomAddress,
                            data,
                            "0x0",
                            "0x800000");
                    String txHash = MyUtil.parseGatewayTxHash(json);
                    runOnUiThread(() -> {
                        if (txHash != null) {
                            lastSubmittedTakeTxHash = txHash;
                            appendLog("抽牌已提交（网关）：" + txHash);
                            verifyTakeTxVisibleOnRpc(txHash);
                            watchTakeTxReceipt(txHash, target);
                        } else {
                            appendLog("抽牌失败（网关）：" + MyUtil.formatGatewayError(json));
                            appendLog("[GATEWAY-RAW] " + json);
                            debugStateAfterGatewayFail(target);
                        }
                        isSendingTake = false;
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
                        JSONObject res = new JSONObject(result);
                        if (res.has("result")) {
                            String txHash = res.getString("result");
                            lastSubmittedTakeTxHash = txHash;
                            appendLog("抽牌已提交：" + txHash);
                            verifyTakeTxVisibleOnRpc(txHash);
                            watchTakeTxReceipt(txHash, target);
                        } else {
                            String err = formatRpcError(res.optJSONObject("error"));
                            appendLog("抽牌失败：" + err);
                        }
                        isSendingTake = false;
                    } catch (Exception e) {
                        appendLog("抽牌返回解析失败：" + e.getMessage());
                        isSendingTake = false;
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("抽牌网络错误：" + e.getMessage());
                    isSendingTake = false;
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("抽牌调用失败：" + e.getMessage());
            isSendingTake = false;
        }
    }

    private static String formatRpcError(JSONObject errObj) {
        if (errObj == null) return "unknown error";
        int code = errObj.optInt("code", 0);
        String message = errObj.optString("message", "");
        Object data = errObj.opt("data");
        if (data == null) return "code=" + code + ", message=" + message;
        return "code=" + code + ", message=" + message + ", data=" + String.valueOf(data);
    }

    private String briefHandFromCache(String addr) {
        PlayerHandCache c = cacheFor(addr);
        if (c == null) return "cache=none";
        int n = 0;
        for (int v : c.order) if (v >= 1 && v <= 10) n++;
        int j = 0;
        for (int v : c.order) if (v == 0) j++;
        return "numbers=" + n + ", jokers=" + j + ", cards=" + c.order;
    }

    /** 打印当前预检端（BROKERCHAIN_RPC）的链快照，便于与网关发送端比对。 */
    private void probeRpcChainSnapshot() {
        try {
            JSONObject cidReq = new JSONObject();
            cidReq.put("jsonrpc", "2.0");
            cidReq.put("method", "eth_chainId");
            cidReq.put("params", new JSONArray());
            cidReq.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, cidReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        String cid = res.optString("result", "N/A");
                        appendLog("[RPC] chainId=" + cid);
                    } catch (Exception e) {
                        appendLog("[RPC] chainId解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("[RPC] chainId网络错误：" + e.getMessage());
                    return null;
                }
            });

            JSONObject bnReq = new JSONObject();
            bnReq.put("jsonrpc", "2.0");
            bnReq.put("method", "eth_blockNumber");
            bnReq.put("params", new JSONArray());
            bnReq.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, bnReq.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        String bn = res.optString("result", "N/A");
                        appendLog("[RPC] blockNumber=" + bn);
                    } catch (Exception e) {
                        appendLog("[RPC] blockNumber解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("[RPC] blockNumber网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("[RPC] 快照探测异常：" + e.getMessage());
        }
    }

    /** 网关返回 txHash 后，用当前 BROKERCHAIN_RPC 查询可见性，判断是否同链。 */
    private void verifyTakeTxVisibleOnRpc(String txHash) {
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
                        if (tx == null) {
                            appendLog("[RPC] tx不可见（可能网关与当前RPC不同链/不同视图）: " + txHash);
                            return;
                        }
                        String from = tx.optString("from", "");
                        String to = tx.optString("to", "");
                        appendLog("[RPC] tx可见: from=" + shortAddr(from) + ", to=" + shortAddr(to));
                    } catch (Exception e) {
                        appendLog("[RPC] tx可见性解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("[RPC] tx可见性网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("[RPC] tx可见性探测异常：" + e.getMessage());
        }
    }

    private void watchTakeTxReceipt(String txHash, String target) {
        watchTakeTxReceipt(txHash, target, 0);
    }

    private void watchTakeTxReceipt(String txHash, String target, int attempt) {
        if (attempt > 12) {
            appendLog("[RECEIPT] 超时未出块：" + txHash);
            return;
        }
        try {
            JSONArray params = new JSONArray();
            params.put(txHash);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getTransactionReceipt");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject res = new JSONObject(result);
                        JSONObject receipt = res.optJSONObject("result");
                        if (receipt == null) {
                            handler.postDelayed(() -> watchTakeTxReceipt(txHash, target, attempt + 1), 1000);
                            return;
                        }
                        String status = receipt.optString("status", "N/A");
                        JSONArray logs = receipt.optJSONArray("logs");
                        int logCount = logs == null ? 0 : logs.length();
                        appendLog("[RECEIPT] status=" + status + ", logs=" + logCount + ", tx=" + txHash);
                        boolean hasCardTaken = false;
                        boolean hasGameEnded = false;
                        boolean hasRewards = false;
                        String sigCardTaken = "0x" + ABIUtils.getEventSignatureHash("CardTaken(address,address,uint8)");
                        String sigGameEnded = "0x" + ABIUtils.getEventSignatureHash("GameEnded(uint256,address[],address[])");
                        String sigRewards = "0x" + ABIUtils.getEventSignatureHash("RewardsDistributed(uint256,uint256,uint256)");
                        if (logs != null) {
                            for (int i = 0; i < logs.length(); i++) {
                                JSONObject lg = logs.optJSONObject(i);
                                if (lg == null) continue;
                                JSONArray topics = lg.optJSONArray("topics");
                                if (topics == null || topics.length() == 0) continue;
                                String t0 = topics.optString(0, "");
                                if (sigCardTaken.equalsIgnoreCase(t0)) hasCardTaken = true;
                                if (sigGameEnded.equalsIgnoreCase(t0)) hasGameEnded = true;
                                if (sigRewards.equalsIgnoreCase(t0)) hasRewards = true;
                            }
                        }
                        appendLog("[RECEIPT] events: CardTaken=" + hasCardTaken
                                + ", GameEnded=" + hasGameEnded
                                + ", RewardsDistributed=" + hasRewards);
                        if ("0x1".equalsIgnoreCase(status) && hasGameEnded) {
                            tryFinishGameFromReceiptLogs(logs);
                        }
                        if ("0x1".equalsIgnoreCase(status)) {
                            applyRewardsFromTxReceiptLogs(logs);
                        }
                        fetchPlayerCardsBrief(target, "[SNAPSHOT] targetAfter");
                        fetchPlayerCardsBrief(myAddress, "[SNAPSHOT] meAfter");
                    } catch (Exception e) {
                        appendLog("[RECEIPT] 解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("[RECEIPT] 查询失败：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("[RECEIPT] 请求构造失败：" + e.getMessage());
        }
    }

    private void fetchPlayerCardsBrief(String player, String prefix) {
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", ABIUtils.encodeGetPlayerCards(player));
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
                        String raw = res.optString("result", "0x");
                        BigInteger[] cards = ABIUtils.decodeUint256Array(raw, 0, 10);
                        BigInteger joker = ABIUtils.decodeUint256(raw, 10);
                        int numbers = 0;
                        StringBuilder detail = new StringBuilder();
                        detail.append("[");
                        for (int i = 0; i < 10; i++) {
                            int c = cards[i] == null ? 0 : cards[i].intValue();
                            numbers += c;
                            detail.append(c);
                            if (i < 9) detail.append(",");
                        }
                        detail.append("]");
                        appendLog(prefix + " " + shortAddr(player) + " numbers=" + numbers
                                + ", jokers=" + (joker == null ? 0 : joker.intValue())
                                + ", hand10=" + detail);
                    } catch (Exception e) {
                        appendLog(prefix + " 解析失败：" + e.getMessage());
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog(prefix + " 网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog(prefix + " 请求异常：" + e.getMessage());
        }
    }

    private void debugStateAfterGatewayFail(String target) {
        debugCurrentTurn();
        debugTakeTarget();
        fetchPlayerCardsBrief(target, "[DEBUG] targetNow");
        fetchPlayerCardsBrief(myAddress, "[DEBUG] meNow");
    }

    private void debugCurrentTurn() {
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", ABIUtils.encodeGetCurrentTurnPlayer());
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
                        String turn = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        appendLog("[DEBUG] currentTurnNow=" + shortAddr(turn));
                    } catch (Exception e) {
                        appendLog("[DEBUG] currentTurn解析失败：" + e.getMessage());
                    }
                }
                @Override
                public Void onError(Exception e) {
                    appendLog("[DEBUG] currentTurn网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("[DEBUG] currentTurn请求异常：" + e.getMessage());
        }
    }

    private void debugTakeTarget() {
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", ABIUtils.encodeGetTakeTarget());
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
                        String t = ABIUtils.decodeAddress(res.optString("result", "0x"));
                        appendLog("[DEBUG] takeTargetNow=" + shortAddr(t));
                    } catch (Exception e) {
                        appendLog("[DEBUG] takeTarget解析失败：" + e.getMessage());
                    }
                }
                @Override
                public Void onError(Exception e) {
                    appendLog("[DEBUG] takeTarget网络错误：" + e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            appendLog("[DEBUG] takeTarget请求异常：" + e.getMessage());
        }
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

    /** 与链上 indexed gameId topic 一致：支持十进制或 0x 十六进制 gameId */
    private BigInteger parseGameIdBigInt() {
        try {
            if (gameId == null || gameId.trim().isEmpty()) return BigInteger.ZERO;
            String s = gameId.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return new BigInteger(s.substring(2), 16);
            }
            return new BigInteger(s, 10);
        } catch (Exception e) {
            Log.w(TAG, "parseGameIdBigInt: " + gameId, e);
            return BigInteger.ZERO;
        }
    }

    /** 用于与链上 indexed gameId topic 对齐：优先链上 room.gameId()，否则退回 Intent */
    private BigInteger gameIdForEventTopics() {
        return chainRoomGameId != null ? chainRoomGameId : parseGameIdBigInt();
    }

    /**
     * eth_call 当前房间 gameId() 并缓存。不阻塞；失败则仅用 Intent gameId（不写 err 缓存）。
     */
    private void fetchChainRoomGameId(Runnable onDone) {
        if (onDone == null) return;
        if (chainRoomGameId != null) {
            onDone.run();
            return;
        }
        if (roomAddress == null || roomAddress.trim().isEmpty()) {
            onDone.run();
            return;
        }
        try {
            String data = ABIUtils.encodeGetRoomGameId();
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
                        if (!res.has("error")) {
                            String raw = res.optString("result", "0x");
                            if (raw != null && raw.length() >= 66) {
                                chainRoomGameId = ABIUtils.decodeUint256(raw, 0);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "fetchChainRoomGameId 解析", e);
                    }
                    onDone.run();
                }

                @Override
                public Void onError(Exception e) {
                    Log.w(TAG, "fetchChainRoomGameId 网络", e);
                    onDone.run();
                    return null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "fetchChainRoomGameId", e);
            onDone.run();
        }
    }

    private BigInteger[] parseRewardsDistributedFromReceiptLogs(JSONArray logs) {
        if (logs == null) return null;
        String sig = "0x" + ABIUtils.getEventSignatureHash("RewardsDistributed(uint256,uint256,uint256)");
        String vault = GameConfig.STAKING_VAULT_ADDRESS;
        if (vault == null || vault.isEmpty()) return null;
        for (int i = 0; i < logs.length(); i++) {
            JSONObject lg = logs.optJSONObject(i);
            if (lg == null) continue;
            if (!vault.equalsIgnoreCase(lg.optString("address", ""))) continue;
            JSONArray topics = lg.optJSONArray("topics");
            if (topics == null || topics.length() < 1) continue;
            if (!sig.equalsIgnoreCase(topics.optString(0, ""))) continue;
            String hexData = lg.optString("data", "0x");
            BigInteger rewardPool = ABIUtils.decodeUint256(hexData, 0);
            BigInteger fee = ABIUtils.decodeUint256(hexData, 1);
            return new BigInteger[] { rewardPool, fee };
        }
        return null;
    }

    /** 同一笔 takeCard 交易回执内常含 Vault 的 RewardsDistributed，不依赖 eth_getLogs 索引 */
    private void applyRewardsFromTxReceiptLogs(JSONArray logs) {
        BigInteger[] amts = parseRewardsDistributedFromReceiptLogs(logs);
        if (amts == null) return;
        BigInteger rewardPool = amts[0];
        BigInteger fee = amts[1];
        BigInteger total = rewardPool.add(fee);
        appendLog("[RECEIPT] 已从交易回执解析 RewardsDistributed：扣费前合计 " + fromWei(total) + " BKC · 手续费 "
                + fromWei(fee) + " · 分配池 " + fromWei(rewardPool));
        List<String> wcopy = lastSettledWinners == null ? null : new ArrayList<>(lastSettledWinners);
        BigInteger rFinal = rewardPool;
        BigInteger fFinal = fee;
        BigInteger tFinal = total;
        runOnUiThread(() -> showRewardPanelSuccess(tFinal, fFinal, rFinal, wcopy));
    }

    /**
     * eth_getLogs 查不到 Rewards 时：先尝试末笔抽牌交易回执；再 eth_call gamePools；仍无数额才显示结清说明。
     */
    private void tryVaultPoolClearedFallback(List<String> winners) {
        if (rewardPanelHasConcreteAmounts) return;
        BigInteger gid = gameIdForEventTopics();
        String h = lastSubmittedTakeTxHash;
        if (h != null && !h.trim().isEmpty()) {
            fetchReceiptParseRewardsOrElseVaultPool(h.trim(), winners, gid);
            return;
        }
        runVaultGamePoolCheck(winners, gid);
    }

    /** 从指定 tx 回执解析 RewardsDistributed，失败或未包含则走 Vault.gamePools */
    private void fetchReceiptParseRewardsOrElseVaultPool(String txHash, List<String> winners, BigInteger gid) {
        try {
            JSONArray params = new JSONArray();
            params.put(txHash);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getTransactionReceipt");
            req.put("params", params);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    try {
                        if (rewardPanelHasConcreteAmounts) return;
                        JSONObject res = new JSONObject(result);
                        JSONObject receipt = res.optJSONObject("result");
                        if (receipt == null) {
                            runVaultGamePoolCheck(winners, gid);
                            return;
                        }
                        JSONArray logs = receipt.optJSONArray("logs");
                        BigInteger[] amts = parseRewardsDistributedFromReceiptLogs(logs);
                        if (amts != null) {
                            BigInteger rewardPool = amts[0];
                            BigInteger fee = amts[1];
                            BigInteger total = rewardPool.add(fee);
                            appendLog("[FALLBACK] 从末笔抽牌回执解析 RewardsDistributed：扣费前 " + fromWei(total) + " BKC");
                            List<String> wcopy = winners == null ? null : new ArrayList<>(winners);
                            BigInteger rf = rewardPool, ff = fee, tf = total;
                            runOnUiThread(() -> showRewardPanelSuccess(tf, ff, rf, wcopy));
                            return;
                        }
                        runVaultGamePoolCheck(winners, gid);
                    } catch (Exception e) {
                        Log.e(TAG, "fetchReceiptParseRewardsOrElseVaultPool", e);
                        runVaultGamePoolCheck(winners, gid);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    runVaultGamePoolCheck(winners, gid);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchReceiptParseRewardsOrElseVaultPool", e);
            runVaultGamePoolCheck(winners, gid);
        }
    }

    private void runVaultGamePoolCheck(List<String> winners, BigInteger gid) {
        if (rewardPanelHasConcreteAmounts) return;
        try {
            String calldata = ABIUtils.encodeVaultGamePools(gid);
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", GameConfig.STAKING_VAULT_ADDRESS);
            callParams.put("data", calldata);
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
                        if (rewardPanelHasConcreteAmounts) return;
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            runOnUiThread(() -> showRewardPanelError(
                                    "无法确认 Vault 奖池状态：" + formatRpcError(res.optJSONObject("error"))));
                            return;
                        }
                        String raw = res.optString("result", "0x");
                        BigInteger pool = ABIUtils.decodeUint256(raw, 0);
                        if (pool.compareTo(BigInteger.ZERO) == 0) {
                            appendLog("========== 链上分配（推断） ==========");
                            appendLog("Vault gamePools(" + gid + ")=0，奖池已清空，分配已完成");
                            List<String> wcopy = winners == null ? null : new ArrayList<>(winners);
                            renderConcreteRewardsFromRoomState(wcopy, gid, "根据房间状态补算");
                        } else {
                            runOnUiThread(() -> showRewardPanelError(
                                    "未读到 RewardsDistributed，且 Vault 奖池仍为 " + fromWei(pool)
                                            + " BKC。请检查 RPC 的 eth_getLogs 是否可用及 STAKING_VAULT_ADDRESS。"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "runVaultGamePoolCheck", e);
                        runOnUiThread(() -> showRewardPanelError("确认奖池状态失败：" + e.getMessage()));
                    }
                }

                @Override
                public Void onError(Exception e) {
                    runOnUiThread(() -> showRewardPanelError("确认奖池状态网络错误：" + e.getMessage()));
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "runVaultGamePoolCheck", e);
            runOnUiThread(() -> showRewardPanelError(e.getMessage()));
        }
    }

    /**
     * 当事件日志缺失时，按房间状态补算具体分配，尽量与“正常日志路径”展示一致。
     * - 总奖池：sum(playerData.stakeAmount)
     * - 手续费：total * 10 / 1000
     * - 可分配池：total - fee
     * - 若 winners 为空，则用 jokerCount==0 推导赢家集合
     * - 按赢家 stake 比例分配（与 Vault 一致）
     */
    private void renderConcreteRewardsFromRoomState(List<String> winnersFromEvent, BigInteger gid, String source) {
        if (rewardPanelHasConcreteAmounts) return;
        if (playerList == null || playerList.isEmpty()) {
            runOnUiThread(() -> showRewardPanelPoolClearedHint(gid, winnersFromEvent));
            return;
        }
        List<String> players = new ArrayList<>(playerList);
        Map<String, BigInteger> stakeMap = new HashMap<>();
        Map<String, BigInteger> jokerMap = new HashMap<>();
        fetchRoomStateSequentially(players, 0, stakeMap, jokerMap, () -> {
            if (rewardPanelHasConcreteAmounts) return;
            BigInteger totalPool = BigInteger.ZERO;
            for (String p : players) {
                BigInteger st = getStakeOrZero(stakeMap, p);
                totalPool = totalPool.add(st);
            }
            if (totalPool.compareTo(BigInteger.ZERO) <= 0) {
                runOnUiThread(() -> showRewardPanelPoolClearedHint(gid, winnersFromEvent));
                return;
            }
            BigInteger fee = totalPool.multiply(VAULT_FEE_RATE_PER_MILLE).divide(PER_MILLE_BASE);
            BigInteger rewardPool = totalPool.subtract(fee);

            List<String> winners = new ArrayList<>();
            if (winnersFromEvent != null && !winnersFromEvent.isEmpty()) {
                winners.addAll(winnersFromEvent);
            } else {
                for (String p : players) {
                    BigInteger j = jokerMap.get(p.toLowerCase());
                    if (j == null || j.compareTo(BigInteger.ZERO) == 0) {
                        winners.add(p);
                    }
                }
            }
            if (winners.isEmpty()) {
                runOnUiThread(() -> showRewardPanelPoolClearedHint(gid, winnersFromEvent));
                return;
            }
            appendLog("========== 链上分配（补算） ==========");
            appendLog("来源：" + source + " · gameId=" + gid);
            appendLog("总奖池：" + fromWei(totalPool) + " BKC，手续费：" + fromWei(fee) + " BKC，分配池：" + fromWei(rewardPool) + " BKC");
            BigInteger totalPoolFinal = totalPool;
            BigInteger feeFinal = fee;
            BigInteger rewardPoolFinal = rewardPool;
            List<String> winnersFinal = new ArrayList<>(winners);
            runOnUiThread(() -> showRewardPanelSuccess(totalPoolFinal, feeFinal, rewardPoolFinal, winnersFinal));
        });
    }

    private void fetchRoomStateSequentially(
            List<String> players,
            int index,
            Map<String, BigInteger> stakeMap,
            Map<String, BigInteger> jokerMap,
            Runnable done
    ) {
        if (index >= players.size()) {
            if (done != null) done.run();
            return;
        }
        String p = players.get(index);
        fetchPlayerStakeAmountFromRoom(p, stake -> fetchPlayerJokerCountFromRoom(p, joker -> {
            stakeMap.put(p.toLowerCase(), stake == null ? BigInteger.ZERO : stake);
            jokerMap.put(p.toLowerCase(), joker == null ? BigInteger.ZERO : joker);
            fetchRoomStateSequentially(players, index + 1, stakeMap, jokerMap, done);
        }));
    }

    private void fetchPlayerJokerCountFromRoom(String player, JokerResultCallback cb) {
        if (player == null || player.trim().isEmpty()) {
            if (cb != null) cb.onResult(BigInteger.ZERO);
            return;
        }
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", ABIUtils.encodeGetPlayerCards(player));
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
                            if (cb != null) cb.onResult(BigInteger.ZERO);
                            return;
                        }
                        String raw = res.optString("result", "0x");
                        BigInteger joker = ABIUtils.decodeUint256(raw, 10);
                        if (cb != null) cb.onResult(joker == null ? BigInteger.ZERO : joker);
                    } catch (Exception e) {
                        if (cb != null) cb.onResult(BigInteger.ZERO);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    if (cb != null) cb.onResult(BigInteger.ZERO);
                    return null;
                }
            });
        } catch (Exception e) {
            if (cb != null) cb.onResult(BigInteger.ZERO);
        }
    }

    private void showRewardPanelPoolClearedHint(BigInteger gid, List<String> winners) {
        if (rewardPanelHasConcreteAmounts) return;
        if (layoutRewardPanel == null) return;
        layoutRewardPanel.setVisibility(View.VISIBLE);
        tvRewardStatus.setText("链上奖池已结清 · gameId " + gid);
        tvRewardLinePool.setText("奖池（扣费前）　已在 Vault 侧清零");
        tvRewardLineFee.setText("协议手续费　　　请见区块浏览器交易明细");
        tvRewardLineWinner.setText("获胜者分配池　　已按合约转出至各地址");
        StringBuilder ex = new StringBuilder();
        ex.append("StakingVault.gamePools 已为 0，说明本局 distributeRewards 已执行；部分节点 eth_getLogs 无索引，界面未显示事件属于正常情况。");
        ex.append("\n若钱包已收到来自金库地址的 BKC 转账，即与链上一致。");
        if (winners != null && !winners.isEmpty()) {
            ex.append("\n本局链上获胜者 ").append(winners.size()).append(" 人。");
            for (String w : winners) {
                if (w != null && myAddress != null && w.equalsIgnoreCase(myAddress)) {
                    ex.append("\n你为本局获胜方之一。");
                    break;
                }
            }
        }
        tvRewardLineExtra.setText(ex.toString());
        tvResult.setText("本局已结束 · 分配已完成（奖池已清空）");
        scrollToRewardPanel();
    }

    /**
     * 终局统一入口：收据里已能确定 GameEnded 时立即走这里，避免仅依赖 eth_getLogs 导致界面卡住。
     */
    private void onGameSettled(List<String> losers, List<String> winners, String source) {
        if (gameOver) return;
        lastSettledWinners = (winners == null || winners.isEmpty()) ? null : new ArrayList<>(winners);
        gameOver = true;
        isPolling = false;
        isRefreshingHands = false;
        handler.removeCallbacksAndMessages(null);

        appendLog("\n========== 游戏结果（" + source + "） ==========");
        appendLog("失败者：" + formatAddrList(losers));
        appendLog("获胜者：" + formatAddrList(winners));
        runOnUiThread(() -> {
            tvResult.setText("本局已结束");
            tvTargetHint.setText("本局已结束");
            llOpponents.removeAllViews();
            llMyHand.removeAllViews();
            TextView end = new TextView(this);
            end.setText("本局已结束");
            end.setTextSize(14f);
            llMyHand.addView(end);
            showRewardPanelLoading();
        });
        queryRewardsDistributed(winners);
    }

    private void tryFinishGameFromReceiptLogs(JSONArray logs) {
        if (gameOver || logs == null) return;
        String sigGameEnded = "0x" + ABIUtils.getEventSignatureHash("GameEnded(uint256,address[],address[])");
        for (int i = 0; i < logs.length(); i++) {
            JSONObject lg = logs.optJSONObject(i);
            if (lg == null) continue;
            if (!roomAddress.equalsIgnoreCase(lg.optString("address", ""))) continue;
            JSONArray topics = lg.optJSONArray("topics");
            if (topics == null || topics.length() < 1) continue;
            if (!sigGameEnded.equalsIgnoreCase(topics.optString(0, ""))) continue;
            String data = lg.optString("data", "0x");
            List<String> losers = new ArrayList<>();
            List<String> winners = new ArrayList<>();
            ABIUtils.decodeGameEndedLosersWinners(data, losers, winners);
            onGameSettled(losers, winners, "收据 GameEnded");
            return;
        }
    }

    /**
     * 周期性 eth_call gameState()；一旦为 ENDED 则拉取 GameEnded 日志并结算界面。
     * 不依赖「当前是否正在刷新手牌」，解决旁观者客户端卡在加载中的问题。
     */
    private void pollRoomGameEndedState() {
        if (gameOver || roomAddress == null || roomAddress.isEmpty()) return;
        if (!roomGameStatePollInFlight.compareAndSet(false, true)) return;
        try {
            String data = ABIUtils.encodeGameState();
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
                    roomGameStatePollInFlight.set(false);
                    if (gameOver) return;
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) return;
                        String raw = res.optString("result", "0x");
                        if (raw == null || raw.equals("0x") || raw.length() < 3) return;
                        BigInteger st = ABIUtils.decodeUint256(raw, 0);
                        if (GAME_STATE_ENDED.equals(st)) {
                            requestGameEndedLogsBroadAndSettle();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "pollRoomGameEndedState 解析", e);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    roomGameStatePollInFlight.set(false);
                    return null;
                }
            });
        } catch (Exception e) {
            roomGameStatePollInFlight.set(false);
            Log.e(TAG, "pollRoomGameEndedState", e);
        }
    }

    /**
     * 仅按事件签名过滤 GameEnded，再在客户端比对 indexed gameId，避免双 topic 过滤与节点实现不一致导致查不到日志。
     */
    private void requestGameEndedLogsBroadAndSettle() {
        if (gameOver || !gameEndedBroadLogsInFlight.compareAndSet(false, true)) return;
        fetchChainRoomGameId(this::requestGameEndedLogsBroadAndSettleDoRpc);
    }

    private void requestGameEndedLogsBroadAndSettleDoRpc() {
        try {
            JSONObject filter = new JSONObject();
            filter.put("address", roomAddress);
            JSONArray topics = new JSONArray();
            topics.put("0x" + ABIUtils.getEventSignatureHash("GameEnded(uint256,address[],address[])"));
            filter.put("topics", topics);
            filter.put("fromBlock", "0x0");
            filter.put("toBlock", "latest");

            JSONArray rpcParams = new JSONArray();
            rpcParams.put(filter);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");
            req.put("params", rpcParams);
            req.put("id", RequestIdGenerator.getNextId());

            OkhttpUtils.getInstance().doPost(GameConfig.BROKERCHAIN_RPC, req.toString(), new MyCallBack() {
                @Override
                public void onSuccess(String result) {
                    gameEndedBroadLogsInFlight.set(false);
                    if (gameOver) return;
                    try {
                        JSONObject res = new JSONObject(result);
                        if (res.has("error")) {
                            appendLog("GameEnded 宽查询出错：" + formatRpcError(res.optJSONObject("error")));
                            onGameSettled(new ArrayList<>(), new ArrayList<>(), "合约已终局（日志 RPC 报错）");
                            return;
                        }
                        JSONArray logs = res.optJSONArray("result");
                        String wantTopic1 = "0x" + String.format("%064x", gameIdForEventTopics());
                        if (logs != null && logs.length() > 0) {
                            for (int i = logs.length() - 1; i >= 0; i--) {
                                JSONObject lg = logs.optJSONObject(i);
                                if (lg == null) continue;
                                JSONArray tpc = lg.optJSONArray("topics");
                                if (tpc == null || tpc.length() < 2) continue;
                                if (!wantTopic1.equalsIgnoreCase(tpc.optString(1, ""))) continue;
                                String data = lg.optString("data", "0x");
                                List<String> losers = new ArrayList<>();
                                List<String> winners = new ArrayList<>();
                                ABIUtils.decodeGameEndedLosersWinners(data, losers, winners);
                                onGameSettled(losers, winners, "eth_getLogs(宽·gameId 匹配)");
                                return;
                            }
                            JSONObject last = logs.getJSONObject(logs.length() - 1);
                            String data = last.getString("data");
                            List<String> losers = new ArrayList<>();
                            List<String> winners = new ArrayList<>();
                            ABIUtils.decodeGameEndedLosersWinners(data, losers, winners);
                            onGameSettled(losers, winners, "eth_getLogs(宽·末条)");
                            return;
                        }
                        onGameSettled(new ArrayList<>(), new ArrayList<>(), "合约已终局（无 GameEnded 日志）");
                    } catch (Exception e) {
                        Log.e(TAG, "requestGameEndedLogsBroadAndSettle 解析", e);
                        onGameSettled(new ArrayList<>(), new ArrayList<>(), "合约已终局（解析异常）");
                    }
                }

                @Override
                public Void onError(Exception e) {
                    gameEndedBroadLogsInFlight.set(false);
                    if (!gameOver) {
                        appendLog("GameEnded 宽查询网络错误：" + e.getMessage());
                        onGameSettled(new ArrayList<>(), new ArrayList<>(), "合约已终局（网络）");
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            gameEndedBroadLogsInFlight.set(false);
            Log.e(TAG, "requestGameEndedLogsBroadAndSettleDoRpc", e);
        }
    }

    private void queryGameResult() {
        if (gameOver) return;
        fetchChainRoomGameId(this::queryGameResultAfterGid);
    }

    private void queryGameResultAfterGid() {
        if (gameOver) return;
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", roomAddress);
            JSONArray topics = new JSONArray();
            topics.put("0x" + ABIUtils.getEventSignatureHash("GameEnded(uint256,address[],address[])"));
            topics.put("0x" + String.format("%064x", gameIdForEventTopics()));
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
                        // eth_getLogs 可能返回 { "error": ... } 或没有 result，这里要做健壮性判断
                        if (res.has("error")) {
                            Log.w(TAG, "查询 GameEnded 日志出错：" + res.optJSONObject("error"));
                            return;
                        }
                        JSONArray logs = res.optJSONArray("result");
                        if (logs == null || logs.length() <= 0) return;

                        JSONObject log = logs.getJSONObject(0);
                        String data = log.getString("data");
                        List<String> losers = new ArrayList<>();
                        List<String> winners = new ArrayList<>();
                        ABIUtils.decodeGameEndedLosersWinners(data, losers, winners);
                        onGameSettled(losers, winners, "eth_getLogs");
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
            Log.e(TAG, "queryGameResultAfterGid异常", e);
        }
    }

    private void queryRewardsDistributed(List<String> winners) {
        fetchChainRoomGameId(() -> queryRewardsDistributedAfterGid(winners, 0));
    }

    private void queryRewardsDistributedAfterGid(final List<String> winners, final int attempt) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", "eth_getLogs");

            JSONObject filter = new JSONObject();
            filter.put("address", GameConfig.STAKING_VAULT_ADDRESS);
            JSONArray topics = new JSONArray();
            topics.put("0x" + ABIUtils.getEventSignatureHash("RewardsDistributed(uint256,uint256,uint256)"));
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
                        if (res.has("error")) {
                            String err = formatRpcError(res.optJSONObject("error"));
                            if (attempt < 4) {
                                handler.postDelayed(() -> queryRewardsDistributedAfterGid(winners, attempt + 1), 2000);
                                return;
                            }
                            appendLog("\n========== 链上分配 ==========");
                            appendLog("查询 RewardsDistributed RPC 错误：" + err);
                            runOnUiThread(() -> showRewardPanelError("RPC：" + err));
                            return;
                        }
                        JSONArray logs = res.optJSONArray("result");
                        String wantGidTopic = "0x" + String.format("%064x", gameIdForEventTopics());
                        JSONObject log = null;
                        if (logs != null) {
                            for (int i = logs.length() - 1; i >= 0; i--) {
                                JSONObject lg = logs.optJSONObject(i);
                                if (lg == null) continue;
                                JSONArray tpc = lg.optJSONArray("topics");
                                if (tpc == null || tpc.length() < 2) continue;
                                if (!wantGidTopic.equalsIgnoreCase(tpc.optString(1, ""))) continue;
                                log = lg;
                                break;
                            }
                        }
                        if (log == null) {
                            if (attempt < 4) {
                                handler.postDelayed(() -> queryRewardsDistributedAfterGid(winners, attempt + 1), 2000);
                                return;
                            }
                            appendLog("\n========== 链上分配 ==========");
                            appendLog("未查到 RewardsDistributed 事件（已重试），将用 Vault.gamePools 回退判断 · topic gameId="
                                    + gameIdForEventTopics());
                            tryVaultPoolClearedFallback(winners);
                            return;
                        }
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

                        BigInteger totalBeforeFeeFinal = totalBeforeFee;
                        BigInteger feeFinal = fee;
                        BigInteger rewardPoolFinal = rewardPool;
                        List<String> winnersCopy = winners == null ? null : new ArrayList<>(winners);
                        runOnUiThread(() -> showRewardPanelSuccess(totalBeforeFeeFinal, feeFinal, rewardPoolFinal, winnersCopy));
                    } catch (Exception e) {
                        Log.e(TAG, "解析 RewardsDistributed 异常", e);
                        appendLog("解析分配事件失败：" + e.getMessage());
                        runOnUiThread(() -> showRewardPanelError("解析链上事件失败：" + e.getMessage()));
                    }
                }

                @Override
                public Void onError(Exception e) {
                    appendLog("查询 RewardsDistributed 失败：" + e.getMessage());
                    runOnUiThread(() -> showRewardPanelError("网络请求失败：" + e.getMessage()));
                    return null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "queryRewardsDistributed异常", e);
        }
    }

    /** 终局后展开奖励面板：黑白描边样式，加载态 */
    private void showRewardPanelLoading() {
        if (layoutRewardPanel == null) return;
        rewardPanelHasConcreteAmounts = false;
        if (scrollDebugLog != null) scrollDebugLog.setVisibility(View.VISIBLE);
        layoutRewardPanel.setVisibility(View.VISIBLE);
        tvRewardStatus.setText("正在查询 StakingVault 的 RewardsDistributed 事件…");
        tvRewardLinePool.setText("奖池（扣费前）　—");
        tvRewardLineFee.setText("协议手续费　　　—");
        tvRewardLineWinner.setText("获胜者分配池　　—");
        tvRewardLineExtra.setText("说明：数值来自链上事件。若长时间无更新，请检查 RPC 与 GameConfig.STAKING_VAULT_ADDRESS。");
        scrollToRewardPanel();
    }

    private void showRewardPanelSuccess(BigInteger totalBeforeFee, BigInteger fee, BigInteger rewardPool, List<String> winners) {
        if (layoutRewardPanel == null) return;
        rewardPanelHasConcreteAmounts = true;
        if (scrollDebugLog != null) scrollDebugLog.setVisibility(View.GONE);
        layoutRewardPanel.setVisibility(View.VISIBLE);
        tvRewardStatus.setText("链上分配已同步 · gameId " + gameIdForEventTopics());
        tvRewardLinePool.setText("奖池（扣费前）　" + fromWei(totalBeforeFee) + " BKC");
        tvRewardLineFee.setText("协议手续费　　　" + fromWei(fee) + " BKC");
        tvRewardLineWinner.setText("获胜者分配池　　" + fromWei(rewardPool) + " BKC");
        StringBuilder ex = new StringBuilder();
        ex.append("上述获胜者池按各获胜者在 Vault 中的质押比例在赢家之间分配。");
        if (winners != null && !winners.isEmpty()) {
            ex.append("\n本局获胜者 ").append(winners.size()).append(" 人。");
            boolean imWin = false;
            for (String w : winners) {
                if (w != null && myAddress != null && w.equalsIgnoreCase(myAddress)) {
                    imWin = true;
                    break;
                }
            }
            if (imWin) {
                ex.append("\n你为本局获胜方之一。");
            }
        }
        tvRewardLineExtra.setText(ex.toString());
        fetchWinnerBreakdownAndRender(winners, rewardPool);
        tvResult.setText("已结束 · 手续费 " + fromWeiShort(fee) + " · 分配池 " + fromWeiShort(rewardPool));
        scrollToRewardPanel();
    }

    /** 在奖励面板追加“获胜者地址 + 分得奖励”明细。 */
    private void fetchWinnerBreakdownAndRender(List<String> winners, BigInteger rewardPool) {
        if (!rewardPanelHasConcreteAmounts) return;
        if (winners == null || winners.isEmpty()) return;
        if (rewardPool == null || rewardPool.compareTo(BigInteger.ZERO) <= 0) return;

        List<String> winnerCopy = new ArrayList<>();
        for (String w : winners) {
            if (w != null && !w.trim().isEmpty()) winnerCopy.add(w);
        }
        if (winnerCopy.isEmpty()) return;

        Map<String, BigInteger> stakes = new HashMap<>();
        fetchWinnerStakesSequentially(winnerCopy, 0, stakes, () -> {
            if (!rewardPanelHasConcreteAmounts) return;
            BigInteger totalStake = BigInteger.ZERO;
            for (String w : winnerCopy) {
                totalStake = totalStake.add(getStakeOrZero(stakes, w));
            }
            if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
                String base = tvRewardLineExtra.getText() == null ? "" : tvRewardLineExtra.getText().toString();
                tvRewardLineExtra.setText(base + "\n\n获胜者分配明细：\n无法读取赢家质押额，暂无法逐人拆分。");
                return;
            }

            StringBuilder detail = new StringBuilder();
            detail.append("\n\n获胜者分配明细（地址 / 分得奖励）：");
            for (String w : winnerCopy) {
                BigInteger st = getStakeOrZero(stakes, w);
                BigInteger reward = st.multiply(rewardPool).divide(totalStake);
                detail.append("\n")
                        .append(w)
                        .append("\n  ≈ ")
                        .append(fromWei(reward))
                        .append(" BKC");
            }
            String base = tvRewardLineExtra.getText() == null ? "" : tvRewardLineExtra.getText().toString();
            tvRewardLineExtra.setText(base + detail);
        });
    }

    private BigInteger getStakeOrZero(Map<String, BigInteger> stakes, String winnerAddr) {
        if (stakes == null || winnerAddr == null) return BigInteger.ZERO;
        BigInteger v = stakes.get(winnerAddr.toLowerCase());
        return v == null ? BigInteger.ZERO : v;
    }

    private void fetchWinnerStakesSequentially(
            List<String> winners,
            int index,
            Map<String, BigInteger> stakes,
            Runnable done
    ) {
        if (index >= winners.size()) {
            if (done != null) runOnUiThread(done);
            return;
        }
        String w = winners.get(index);
        fetchPlayerStakeAmountFromRoom(w, stake -> {
            stakes.put(w.toLowerCase(), stake != null ? stake : BigInteger.ZERO);
            fetchWinnerStakesSequentially(winners, index + 1, stakes, done);
        });
    }

    /** 从 GameRoom.playerData(address) 读取 stakeAmount（第2个槽位） */
    private void fetchPlayerStakeAmountFromRoom(String player, StakeResultCallback cb) {
        if (player == null || player.trim().isEmpty()) {
            if (cb != null) cb.onResult(BigInteger.ZERO);
            return;
        }
        try {
            JSONObject callParams = new JSONObject();
            callParams.put("from", myAddress);
            callParams.put("to", roomAddress);
            callParams.put("data", ABIUtils.encodePlayerData(player));
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
                            if (cb != null) cb.onResult(BigInteger.ZERO);
                            return;
                        }
                        String raw = res.optString("result", "0x");
                        String stakeHex = ABIUtils.decodeStakeAmount(raw);
                        BigInteger stake = new BigInteger(stakeHex, 16);
                        if (cb != null) cb.onResult(stake);
                    } catch (Exception e) {
                        if (cb != null) cb.onResult(BigInteger.ZERO);
                    }
                }

                @Override
                public Void onError(Exception e) {
                    if (cb != null) cb.onResult(BigInteger.ZERO);
                    return null;
                }
            });
        } catch (Exception e) {
            if (cb != null) cb.onResult(BigInteger.ZERO);
        }
    }

    private void showRewardPanelError(String message) {
        if (rewardPanelHasConcreteAmounts) return;
        if (layoutRewardPanel == null) return;
        layoutRewardPanel.setVisibility(View.VISIBLE);
        tvRewardStatus.setText("奖励分配数据未完整拉取");
        tvRewardLinePool.setText("奖池（扣费前）　—");
        tvRewardLineFee.setText("协议手续费　　　—");
        tvRewardLineWinner.setText("获胜者分配池　　—");
        tvRewardLineExtra.setText(message);
        scrollToRewardPanel();
    }

    private void scrollToRewardPanel() {
        if (scrollBattleRoot == null || layoutRewardPanel == null) return;
        scrollBattleRoot.post(() -> scrollBattleRoot.smoothScrollTo(0, layoutRewardPanel.getTop()));
    }

    private String fromWei(BigInteger wei) {
        if (wei == null) return "0";
        return wei.divide(new BigInteger("1000000000000000000")).toString() + "." +
                String.format("%018d", wei.mod(new BigInteger("1000000000000000000"))).substring(0, 4);
    }

    private String fromWeiShort(BigInteger wei) {
        if (wei == null) return "0";
        return wei.divide(new BigInteger("1000000000000000000")).toString() + "." +
                String.format("%018d", wei.mod(new BigInteger("1000000000000000000"))).substring(0, 2) + "…";
    }

    private String formatAddrList(List<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String a : addrs) sb.append("\n  ").append(a);
        return sb.toString();
    }

    private String shortAddr(String addr) {
        if (addr == null || addr.length() < 10) return addr == null ? "" : addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }

    private void appendLog(String text) {
        Log.i(TAG, text);
        if (!shouldShowOnUi(text)) return;
        runOnUiThread(() -> {
            uiLogLines.addLast(text);
            while (uiLogLines.size() > UI_LOG_MAX) uiLogLines.removeFirst();
            StringBuilder sb = new StringBuilder();
            for (String s : uiLogLines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
            tvLog.setText(sb.toString());
        });
    }

    private boolean shouldShowOnUi(String text) {
        if (text == null) return false;
        String t = text.trim();
        return text.startsWith("========== 游戏开始")
                || t.startsWith("========== 游戏结果")
                || t.startsWith("========== 链上分配")
                || text.startsWith("游戏ID：")
                || text.startsWith("房间地址：")
                || text.startsWith("失败者：")
                || text.startsWith("获胜者：")
                || text.startsWith("获胜者地址数：")
                || text.startsWith("本局奖池")
                || text.startsWith("协议手续费")
                || text.startsWith("分给获胜者")
                || text.startsWith("说明：")
                || text.startsWith("未查到 RewardsDistributed")
                || text.startsWith("查询 RewardsDistributed")
                || text.startsWith("GameEnded 宽查询")
                || text.startsWith("点击抽牌：")
                || text.startsWith("抽牌已提交（网关）：")
                || text.startsWith("抽牌已提交：")
                || text.startsWith("抽牌失败")
                || text.startsWith("解析分配事件失败")
                || text.startsWith("[GATEWAY-RAW]")
                || text.startsWith("[DEBUG]")
                || text.startsWith("[RECEIPT]")
                || text.startsWith("[RECEIPT] 已从")
                || text.startsWith("========== 链上分配（推断）")
                || text.startsWith("Vault gamePools");
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
