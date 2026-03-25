package com.example.brokerfi.xc;

import java.util.ArrayList;
import java.util.List;

public class GameReward {
    private static final double FEE_RATE = 0.05; // 5% 手续费

    public static List<RewardItem> distribute(
            List<GameCoreLogic.Player> players,
            int loserId
    ) {
        // 总质押
        double totalStake = 0.0;
        for (GameCoreLogic.Player p : players) {  // 这里 Player 是你的实体类名，不用改
            totalStake += p.stakeBKC;
        }
        double fee = totalStake * FEE_RATE;
        double pool = totalStake - fee;

        // 赢家总质押
        double winnerStakeTotal = 0;
        List<GameCoreLogic.Player> winners = new ArrayList<>();
        for (GameCoreLogic.Player p : players) {
            if (p.id != loserId) {
                winners.add(p);
                winnerStakeTotal += p.stakeBKC;
            }
        }

        // 按质押比例分配
        List<RewardItem> result = new ArrayList<>();
        for (GameCoreLogic.Player w : winners) {
            double reward = pool * (w.stakeBKC / winnerStakeTotal);
            RewardItem item = new RewardItem();
            item.address = w.address;
            item.rewardBKC = reward;
            result.add(item);
        }
        return result;
    }

    public static class RewardItem {
        public String address;
        public double rewardBKC;
    }
}