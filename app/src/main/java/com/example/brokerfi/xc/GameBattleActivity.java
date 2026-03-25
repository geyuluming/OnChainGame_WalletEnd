package com.example.brokerfi.xc;

import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.brokerfi.R;
import java.util.ArrayList;
import java.util.List;

public class GameBattleActivity extends AppCompatActivity {
    private TextView tvLog, tvResult;
    private GameCoreLogic game;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_battle);
        tvLog = findViewById(R.id.tv_log);
        tvResult = findViewById(R.id.tv_result);

        // 初始化：2~4人可自定义
        startGame(2);
    }

    private void startGame(int playerCount) {
        appendLog("游戏开始，玩家数：" + playerCount);

        // 1. 创建游戏
        game = new GameCoreLogic(playerCount);
        game.distributeCards();

        // 2. 模拟玩家质押BKC
        List<GameCoreLogic.Player> players = new ArrayList<>(game.getPlayers().values());
        players.get(0).stakeBKC = 10;
        players.get(0).address = "0xPlayer1";
        players.get(1).stakeBKC = 10;
        players.get(1).address = "0xPlayer2";

        // 3. 自动运行游戏
        new Thread(() -> {
            game.runToEnd();
            int loser = game.getLoserId();

            runOnUiThread(() -> {
                appendLog("\n游戏结束！");
                appendLog("失败者：玩家" + loser + "（持有小丑牌）");

                // 分配奖励
                List<GameReward.RewardItem> rewards = GameReward.distribute(players, loser);
                showRewards(rewards);
            });
        }).start();
    }

    private void appendLog(String text) {
        tvLog.setText(tvLog.getText() + "\n" + text);
    }

    private void showRewards(List<GameReward.RewardItem> rewards) {
        StringBuilder sb = new StringBuilder("奖励分配：\n");
        for (GameReward.RewardItem r : rewards) {
            sb.append(r.address).append(" 获得：").append(String.format("%.2f", r.rewardBKC)).append(" BKC\n");
        }
        tvResult.setText(sb);
        Toast.makeText(this, "游戏结算完成", Toast.LENGTH_LONG).show();
    }
}