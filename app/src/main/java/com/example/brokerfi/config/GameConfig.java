package com.example.brokerfi.config;

import java.math.BigInteger;

public class GameConfig {
    // BrokerChain测试网RPC地址（从钱包浏览器URL获取）
    public static final String BROKERCHAIN_RPC = "http://192.168.1.13:36944";

    // 部署后的合约地址（替换为实际部署地址）
    public static final String STAKING_VAULT_ADDRESS = "0x69e3c96Df4Fa9567B1b7d83749C37e6FFd4B5Be6";
    public static final String GAME_FACTORY_ADDRESS = "0x986137E257593E2E574147d77873618D53CDd73A";

    /**
     * Chainlink VRF v2 订阅模式（可选）。
     * coordinator 为 0 地址时房间满员后仍同步发牌，与未接入 VRF 时行为一致。
     * 使用 VRF 时：需在 Chainlink 订阅中将每个新部署的 GameRoom 地址添加为 consumer，
     * 并填写本链的 Coordinator 地址、subscriptionId、keyHash（见官方文档 gas lane）。
     */
    public static final String VRF_COORDINATOR_ADDRESS = "0x0000000000000000000000000000000000000000";
    public static final BigInteger VRF_SUBSCRIPTION_ID = BigInteger.ZERO;
    /** 64 位十六进制、无 0x；未启用 VRF 时可全 0 */
    public static final String VRF_KEY_HASH_HEX = "0000000000000000000000000000000000000000000000000000000000000000";
    public static final BigInteger VRF_CALLBACK_GAS_LIMIT = BigInteger.valueOf(500_000L);
    public static final BigInteger VRF_REQUEST_CONFIRMATIONS = BigInteger.valueOf(3L);

    /**
     * ECVRF 中继合约地址（{@code ECVRFRelay}）。全 0 表示不启用；启用时需与 vrfCoordinator 全 0 互斥。
     * 满员后发 {@code ECVRFRandomRequested}，链下对 abi.encode(gameId, room) 做 Prove 后由 relayer 调 relay.submitRandomWord。
     */
    public static final String ECVRF_RELAY_ADDRESS = "0x0000000000000000000000000000000000000000";

    // 合约ABI
    public static final String GAME_FACTORY_ABI = "[\n" +
            "  {\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"minPlayers\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"maxPlayers\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"minStake\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"maxStake\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"jokerCount\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256[10]\",\n" +
            "        \"name\": \"cardCounts\",\n" +
            "        \"type\": \"uint256[10]\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"name\": \"createGameRoom\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"components\": [\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"gameId\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"address\",\n" +
            "            \"name\": \"room\",\n" +
            "            \"type\": \"address\"\n" +
            "          }\n" +
            "        ],\n" +
            "        \"internalType\": \"struct GameFactory.CreateResult\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"tuple\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"nonpayable\",\n" +
            "    \"type\": \"function\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"address payable\",\n" +
            "        \"name\": \"_stakingVault\",\n" +
            "        \"type\": \"address\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"nonpayable\",\n" +
            "    \"type\": \"constructor\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"anonymous\": false,\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"indexed\": true,\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"gameId\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"indexed\": true,\n" +
            "        \"internalType\": \"address\",\n" +
            "        \"name\": \"host\",\n" +
            "        \"type\": \"address\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"indexed\": true,\n" +
            "        \"internalType\": \"address\",\n" +
            "        \"name\": \"room\",\n" +
            "        \"type\": \"address\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"components\": [\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"minPlayers\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"maxPlayers\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"minStake\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"maxStake\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"uint256\",\n" +
            "            \"name\": \"jokerCount\",\n" +
            "            \"type\": \"uint256\"\n" +
            "          },\n" +
            "          {\n" +
            "            \"internalType\": \"uint256[10]\",\n" +
            "            \"name\": \"cardCounts\",\n" +
            "            \"type\": \"uint256[10]\"\n" +
            "          }\n" +
            "        ],\n" +
            "        \"indexed\": false,\n" +
            "        \"internalType\": \"struct GameRoom.GameConfig\",\n" +
            "        \"name\": \"cfg\",\n" +
            "        \"type\": \"tuple\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"name\": \"GameRoomCreated\",\n" +
            "    \"type\": \"event\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"name\": \"gameConfigs\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"minPlayers\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"maxPlayers\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"minStake\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"maxStake\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"jokerCount\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"view\",\n" +
            "    \"type\": \"function\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [],\n" +
            "    \"name\": \"gameIdCounter\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"view\",\n" +
            "    \"type\": \"function\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"name\": \"gameRooms\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"address\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"address\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"view\",\n" +
            "    \"type\": \"function\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"uint256\",\n" +
            "        \"name\": \"gameId\",\n" +
            "        \"type\": \"uint256\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"name\": \"getGameRoom\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"contract GameRoom\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"address\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"view\",\n" +
            "    \"type\": \"function\"\n" +
            "  },\n" +
            "  {\n" +
            "    \"inputs\": [],\n" +
            "    \"name\": \"stakingVault\",\n" +
            "    \"outputs\": [\n" +
            "      {\n" +
            "        \"internalType\": \"contract StakingVault\",\n" +
            "        \"name\": \"\",\n" +
            "        \"type\": \"address\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"stateMutability\": \"view\",\n" +
            "    \"type\": \"function\"\n" +
            "  }\n" +
            "]";

    //public static final String GAME_ROOM_ABI =
}