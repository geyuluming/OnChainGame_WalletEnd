package com.example.brokerfi.config;

import java.math.BigInteger;

public class GameConfig {
    // BrokerChain测试网RPC地址（从钱包浏览器URL获取）
    public static final String BROKERCHAIN_RPC = "http://192.168.1.13:36944";
    //public static final String BROKERCHAIN_RPC = "dash.broker-chain.com";

    // 部署后的合约地址（替换为实际部署地址）
    public static final String STAKING_VAULT_ADDRESS = "0x097d38E2F2D86206FF967890825DEC473adB2414";
    public static final String GAME_FACTORY_ADDRESS = "0xd24F6d9A685EccF270241f40804a6Ebf5A1E2f54";

    /**
     * ECVRF 中继合约地址（{@code ECVRFRelay}）。全 0 表示不启用（房间用区块时间戳同步发牌）。
     * 非 0 时满员后发 {@code ECVRFRandomRequested}，链下对 abi.encode(gameId, room) Prove 后由 relayer 调 {@code submitRandomWord}。
     */
    public static final String ECVRF_RELAY_ADDRESS = "0x023B27D2edA8F0B2CDb1C9d5e8172f75fCc7b81F";
    //public static final String ECVRF_RELAY_ADDRESS = "0x0000000000000000000000000000000000000000";

    /**
     * 若为 true：游戏「写交易」走与首页转账/NFT 相同的 BrokerChain HTTPS 网关（ECDSA + {@code eth_sendTransaction}），
     * 使用当前选中账户私钥，链上 from 与钱包一致。需保证下方合约地址部署在网关所连链上。
     */
    public static final boolean USE_HTTP_GATEWAY_FOR_GAME_TX = true;




}