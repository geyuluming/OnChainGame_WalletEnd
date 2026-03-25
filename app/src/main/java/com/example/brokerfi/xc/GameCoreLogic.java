package com.example.brokerfi.xc;

import java.util.*;

public class GameCoreLogic {
    private final int playerCount;          // 玩家数（2~n）
    private final List<Integer> cards;      // 总牌堆
    private final Map<Integer, Player> players; // 玩家：编号 -> 数据
    private Integer loserPlayerId = null;   // 持有小丑牌输掉的人
    private final Random random = new Random();

    // 玩家结构
    public static class Player {
        public int id;
        public List<Integer> hand = new ArrayList<>();
        public boolean isOut = false;       // 是否出局（无牌）
        public String address;              // 钱包地址
        public double stakeBKC;             // 质押数量
    }

    // 初始化游戏
    public GameCoreLogic(int playerCount) {
        this.playerCount = playerCount;
        this.cards = new ArrayList<>();
        this.players = new HashMap<>();

        // 生成牌：4个1~10 + 1张小丑(0)
        for (int i = 1; i <= 10; i++) {
            for (int j = 0; j < 4; j++) {
                cards.add(i);
            }
        }
        cards.add(0);
        Collections.shuffle(cards);
    }

    // 发牌（每人相差不超过1张）
    public void distributeCards() {
        int per = cards.size() / playerCount;
        int extra = cards.size() % playerCount;
        int idx = 0;

        for (int i = 1; i <= playerCount; i++) {
            Player p = new Player();
            p.id = i;
            int take = per + (i <= extra ? 1 : 0);
            p.hand.addAll(cards.subList(idx, idx + take));
            idx += take;
            eliminatePairs(p); // 初始消除对子
            players.put(i, p);
        }
    }

    // 消除对子：成对消除，三张消两张，留单张
    public void eliminatePairs(Player p) {
        Map<Integer, Integer> count = new HashMap<>();
        for (int c : p.hand) {
            int num = count.containsKey(c) ? count.get(c) : 0;
            count.put(c, num + 1);
        }

        List<Integer> newHand = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : count.entrySet()) {
            int num = entry.getKey();
            int cnt = entry.getValue();
            if (cnt % 2 != 0) newHand.add(num); // 留单数
        }

        p.hand = newHand;
        if (p.hand.isEmpty()) p.isOut = true;
    }

    // 一轮抽牌：1→2，2→3，…n→1
    public void runOneRound() {
        List<Integer> order = new ArrayList<>(players.keySet());
        Collections.sort(order);

        for (int i = 0; i < order.size(); i++) {
            int drawerId = order.get(i);
            int targetId = order.get((i + 1) % order.size());
            Player drawer = players.get(drawerId);
            Player target = players.get(targetId);

            if (drawer.isOut || target.isOut) continue;
            if (target.hand.isEmpty()) continue;

            // 随机抽1张
            int idx = random.nextInt(target.hand.size());
            int card = target.hand.remove(idx);
            drawer.hand.add(card);

            eliminatePairs(drawer); // 抽完立即消除
        }
    }

    // 判断游戏结束：数字牌全部消完 → 小丑持有者输
    public boolean isGameEnd() {
        int totalNumberCards = 0;
        int jokerHolder = -1;

        for (Player p : players.values()) {
            for (int c : p.hand) {
                if (c == 0) jokerHolder = p.id;
                else totalNumberCards++;
            }
        }

        if (totalNumberCards == 0) {
            loserPlayerId = jokerHolder;
            return true;
        }
        return false;
    }

    // 自动运行到结束
    public void runToEnd() {
        while (!isGameEnd()) runOneRound();
    }

    // 获取失败者ID（持小丑牌）
    public Integer getLoserId() { return loserPlayerId; }
    public Map<Integer, Player> getPlayers() { return players; }
}