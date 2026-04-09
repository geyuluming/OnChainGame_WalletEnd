package com.example.brokerfi.config;

import java.math.BigInteger;

public class GameConfig {
    // BrokerChain测试网RPC地址（从钱包浏览器URL获取）
    public static final String BROKERCHAIN_RPC = "http://192.168.1.13:36944";
    //public static final String BROKERCHAIN_RPC = "dash.broker-chain.com";

    // 部署后的合约地址（替换为实际部署地址）
    public static final String STAKING_VAULT_ADDRESS = "0x83A95D452DB0743853e2Fcac57A5fc815bF4E5d8";
    public static final String GAME_FACTORY_ADDRESS = "0xBAbCA55d77De68b09ab2941CBe3Dcde745c91355";

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

    /**
     * 若为 true：游戏「写交易」走与首页转账/NFT 相同的 BrokerChain HTTPS 网关（ECDSA + {@code eth_sendTransaction}），
     * 使用当前选中账户私钥，链上 from 与钱包一致。需保证下方合约地址部署在网关所连链上。
     * 仅连本地 RPC、合约未部署在官方链时，请保持 false。
     */
    public static final boolean USE_HTTP_GATEWAY_FOR_GAME_TX = true;




}