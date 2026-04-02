package com.example.brokerfi.config;

public class GameConfig {
    // BrokerChain测试网RPC地址（从钱包浏览器URL获取）
    public static final String BROKERCHAIN_RPC = "http://192.168.1.13:36944";

    // 部署后的合约地址（替换为实际部署地址）
    public static final String STAKING_VAULT_ADDRESS = "0x69e3c96Df4Fa9567B1b7d83749C37e6FFd4B5Be6";
    public static final String GAME_FACTORY_ADDRESS = "0x986137E257593E2E574147d77873618D53CDd73A";

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