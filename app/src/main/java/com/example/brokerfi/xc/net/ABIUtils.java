package com.example.brokerfi.xc.net;

import android.util.Log;

import org.web3j.utils.Numeric;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class ABIUtils {

    private static final String MINT_SELECTOR = "d41d9a27";
    private static final String LIST_SELECTOR = "b0136c8d";
    private static final String UNLIST_SELECTOR = "961f0944";
    private static final String GET_MY_NFTS_SELECTOR = "629cb2e4";
    private static final String GET_LISTED_NFTS_SELECTOR = "a3f7f7d8";
    private static final String BUY_SELECTOR = "1d85bf03";
    
    // 勋章系统选择器
    private static final String GET_USER_MEDALS_SELECTOR = "5ef4daa5";  // getUserMedals(address)
    private static final String GET_GLOBAL_STATS_SELECTOR = "fa55312b";  // getGlobalStats()
    
    // NFT查询选择器
    private static final String GET_USER_NFTS_SELECTOR = "8f4f4f4f";  // getUserNfts(address) - 需要计算正确的Keccak256哈希
    private static final String GET_NFT_DATA_SELECTOR = "9f5f5f5f";   // getNftData(uint256) - 需要计算正确的Keccak256哈希



    public static String encodeMint(String name, String base64Image, BigInteger shares) {

        int baseOffset = 32 * 3;
        int nameContentLength = 32 + ((name.getBytes().length + 31) / 32) * 32;
        int imageOffset = baseOffset + nameContentLength;

        StringBuilder data = new StringBuilder();

        data.append(MINT_SELECTOR);

        data.append(padLeft(Integer.toHexString(baseOffset), 64));

        data.append(padLeft(Integer.toHexString(imageOffset), 64));

        data.append(padLeft(shares.toString(16), 64));


        data.append(encodeDynamicString(name));
        data.append(encodeDynamicString(base64Image));

        return "0x" + data.toString();
    }

    private static String encodeDynamicString(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        String length = padLeft(Integer.toHexString(bytes.length), 64);

        String content = Numeric.toHexStringNoPrefix(bytes);

        int totalBytes = ((bytes.length + 31) / 32) * 32;
        content = padRight(content, totalBytes * 2);

        return length + content;
    }

    public static String encodeList(BigInteger nftId, BigInteger shares, BigInteger price) {
        StringBuilder data = new StringBuilder();
        data.append(LIST_SELECTOR);
        data.append(padLeft(nftId.toString(16), 64));
        data.append(padLeft(shares.toString(16), 64));
        data.append(padLeft(price.toString(16), 64));

        return "0x" + data.toString();
    }

    public static String encodeUnlist(BigInteger listingId) {
        StringBuilder data = new StringBuilder();
        data.append(UNLIST_SELECTOR);
        data.append(padLeft(listingId.toString(16), 64));

        return "0x" + data.toString();
    }

    public static String encodeBuy(BigInteger listingId, BigInteger shares) {
        StringBuilder data = new StringBuilder();
        data.append(BUY_SELECTOR);
        data.append(padLeft(listingId.toString(16), 64));
        data.append(padLeft(shares.toString(16), 64));

        return "0x" + data.toString();
    }

    public static String encodeGetMyNFTs() {
        return "0x" + GET_MY_NFTS_SELECTOR;
    }

    public static String encodeGetListedNFTs() {
        return "0x" + GET_LISTED_NFTS_SELECTOR;
    }
    
    // 勋章查询编码方法
    public static String encodeGetUserMedals(String address) {
        StringBuilder data = new StringBuilder();
        data.append(GET_USER_MEDALS_SELECTOR);
        data.append(padLeft(address.substring(2), 64)); // 移除0x前缀并填充到64字符
        return "0x" + data.toString();
    }
    
    public static String encodeGetGlobalStats() {
        return "0x" + GET_GLOBAL_STATS_SELECTOR;
    }
    
    // NFT查询编码方法
    public static String encodeGetUserNfts(String address) {
        StringBuilder data = new StringBuilder();
        data.append(GET_USER_NFTS_SELECTOR);
        data.append(padLeft(address.substring(2), 64)); // 移除0x前缀并填充到64字符
        return "0x" + data.toString();
    }
    
    public static String encodeGetNftData(BigInteger tokenId) {
        StringBuilder data = new StringBuilder();
        data.append(GET_NFT_DATA_SELECTOR);
        data.append(padLeft(tokenId.toString(16), 64));
        return "0x" + data.toString();
    }


    private static String padLeft(String s, int length) {
        if (s.length() >= length) return s;
        return String.format("%" + length + "s", s).replace(' ', '0');
    }

    private static String padRight(String s, int targetLength) {
        if (s.length() >= targetLength) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < targetLength) {
            sb.append('0');
        }
        return sb.toString();
    }



    public static class MyNFTsResult {
        public BigInteger[] nftIds;
        public String[] names;
        public String[] images;
        public BigInteger[] sharesList;
        public BigInteger[] pricesList;
        public boolean[] listingStatus;
        public BigInteger[] listingIds;
    }

    public static MyNFTsResult decodeGetMyNFTs(String hexResponse) {

        MyNFTsResult result = new MyNFTsResult();
        try {

            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {

                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);


            // 1. 解析7个动态数组的偏移量
            int[] arrayOffsets = new int[7];
            for (int i = 0; i < 7; i++) {
                arrayOffsets[i] = Numeric.toBigInt(data, i * 32, 32).intValue();
            }


            result.nftIds = decodeUint256Array(data, arrayOffsets[0]);
            result.names = decodeStringArray(data, arrayOffsets[1]);
            result.images = decodeStringArray(data, arrayOffsets[2]);
            result.sharesList = decodeUint256Array(data, arrayOffsets[3]);
            result.pricesList = decodeUint256Array(data, arrayOffsets[4]);
            result.listingStatus = decodeBoolArray(data, arrayOffsets[5]);
            result.listingIds = decodeUint256Array(data, arrayOffsets[6]);


        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码失败: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }


    public static class ListedNFTsResult {

        public String[] addressList;
        public BigInteger[] nftIds;
        public String[] names;
        public String[] images;
        public BigInteger[] sharesList;
        public BigInteger[] pricesList;
        public BigInteger[] listingIds;
    }

    public static ListedNFTsResult decodeGetListedNFTs(String hexResponse) {

        ListedNFTsResult result = new ListedNFTsResult();
        try {

            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {
                Log.e("ABI_DECODE", "空响应数据");
                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);
            Log.d("ABI_DECODE", "字节数组长度: " + data.length);


            int[] arrayOffsets = new int[7];
            for (int i = 0; i < 7; i++) {
                arrayOffsets[i] = Numeric.toBigInt(data, i * 32, 32).intValue();
            }


            result.addressList = decodeAddressArray(data, arrayOffsets[0]);
            result.nftIds = decodeUint256Array(data, arrayOffsets[1]);
            result.names = decodeStringArray(data, arrayOffsets[2]);
            result.images = decodeStringArray(data, arrayOffsets[3]);
            result.sharesList = decodeUint256Array(data, arrayOffsets[4]);
            result.pricesList = decodeUint256Array(data, arrayOffsets[5]);
            result.listingIds = decodeUint256Array(data, arrayOffsets[6]);

            Log.d("ABI_DECODE", "addressList: " + Arrays.toString(result.addressList));
            Log.d("ABI_DECODE", "nftIds: " + Arrays.toString(result.nftIds));
            Log.d("ABI_DECODE", "names: " + Arrays.toString(result.names));
            Log.d("ABI_DECODE", "images: " + Arrays.toString(result.images));
            Log.d("ABI_DECODE", "sharesList: " + Arrays.toString(result.sharesList));
            Log.d("ABI_DECODE", "pricesList: " + Arrays.toString(result.pricesList));
            Log.d("ABI_DECODE", "listingIds: " + Arrays.toString(result.listingIds));

        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码失败: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }


    private static String[] decodeStringArray(byte[] data, int offset) {
        int arrayLength = Numeric.toBigInt(data, offset, 32).intValue();
        String[] result = new String[arrayLength];

        for (int i = 0; i < arrayLength; i++) {

            int elementOffset = offset + 32 + (i * 32);
            int stringOffset = Numeric.toBigInt(data, elementOffset, 32).intValue();


            int absoluteStringOffset = offset + 32 + stringOffset;


            result[i] = decodeStringStrict(data, absoluteStringOffset);
        }
        return result;
    }

    private static String decodeStringStrict(byte[] data, int offset) {

        if (offset + 32 > data.length) {
            Log.e("DECODE_ERROR", "长度头越界！需要读取32字节，但剩余数据只有" + (data.length - offset) + "字节");
            return "";
        }


        int length = Numeric.toBigInt(data, offset, 32).intValue();
        int contentStart = offset + 32;


        if (contentStart + length > data.length) {
            Log.e("DECODE_ERROR", "内容越界！需要读取" + length + "字节，但剩余数据只有" + (data.length - contentStart) + "字节");
            return "";
        }

        byte[] realContent = Arrays.copyOfRange(data, contentStart, contentStart + length);

        Log.d("DECODE_HEX", "原始字节(hex): " + Numeric.toHexString(realContent));


        String result = new String(realContent, StandardCharsets.UTF_8);
        return result;
    }

    private static BigInteger[] decodeUint256Array(byte[] data, int offset) {
        int arrayLength = Numeric.toBigInt(data, offset, 32).intValue();
        BigInteger[] array = new BigInteger[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            array[i] = Numeric.toBigInt(data, offset + 32 + i * 32, 32);
        }
        return array;
    }

    private static boolean[] decodeBoolArray(byte[] data, int offset) {
        int arrayLength = Numeric.toBigInt(data, offset, 32).intValue();
        boolean[] array = new boolean[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            array[i] = Numeric.toBigInt(data, offset + 32 + i * 32, 32).intValue() != 0;
        }
        return array;
    }

    private static String[] decodeAddressArray(byte[] data, int offset) {
        int arrayLength = Numeric.toBigInt(data, offset, 32).intValue();
        String[] array = new String[arrayLength];
        for (int i = 0; i < arrayLength; i++) {

            String fullAddress = Numeric.toHexStringNoPrefix(
                    Arrays.copyOfRange(data, offset + 32 + i * 32, offset + 32 + i * 32 + 32)
            );

            array[i] = fullAddress.substring(24);
        }
        return array;
    }
    
    // 勋章查询结果类
    public static class MedalQueryResult {
        public int goldMedals;
        public int silverMedals;
        public int bronzeMedals;
        public int totalScore;
    }
    
    public static class GlobalStatsResult {
        public int totalUsers;
        public int totalGoldMedals;
        public int totalSilverMedals;
        public int totalBronzeMedals;
        public int totalMedals;
    }
    
    // NFT查询结果类
    public static class UserNftsResult {
        public BigInteger[] tokenIds;
        public String[] names;
        public String[] descriptions;
        public String[] imageUrls;
    }
    
    public static class NftDataResult {
        public String name;
        public String description;
        public String imageUrl;
        public String owner;
    }
    
    // 勋章查询解码方法
    public static MedalQueryResult decodeGetUserMedals(String hexResponse) {
        MedalQueryResult result = new MedalQueryResult();
        try {
            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {
                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);
            
            result.goldMedals = Numeric.toBigInt(data, 0, 32).intValue();
            result.silverMedals = Numeric.toBigInt(data, 32, 32).intValue();
            result.bronzeMedals = Numeric.toBigInt(data, 64, 32).intValue();
            result.totalScore = Numeric.toBigInt(data, 96, 32).intValue();
        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码勋章数据失败: " + e.getMessage());
        }
        return result;
    }
    
    public static GlobalStatsResult decodeGetGlobalStats(String hexResponse) {
        GlobalStatsResult result = new GlobalStatsResult();
        try {
            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {
                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);
            
            result.totalUsers = Numeric.toBigInt(data, 0, 32).intValue();
            result.totalGoldMedals = Numeric.toBigInt(data, 32, 32).intValue();
            result.totalSilverMedals = Numeric.toBigInt(data, 64, 32).intValue();
            result.totalBronzeMedals = Numeric.toBigInt(data, 96, 32).intValue();
            result.totalMedals = Numeric.toBigInt(data, 128, 32).intValue();
        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码全局统计失败: " + e.getMessage());
        }
        return result;
    }
    
    // NFT查询解码方法
    public static UserNftsResult decodeGetUserNfts(String hexResponse) {
        UserNftsResult result = new UserNftsResult();
        try {
            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {
                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);
            
            // 解析动态数组偏移量
            int[] arrayOffsets = new int[4];
            for (int i = 0; i < 4; i++) {
                arrayOffsets[i] = Numeric.toBigInt(data, i * 32, 32).intValue();
            }
            
            result.tokenIds = decodeUint256Array(data, arrayOffsets[0]);
            result.names = decodeStringArray(data, arrayOffsets[1]);
            result.descriptions = decodeStringArray(data, arrayOffsets[2]);
            result.imageUrls = decodeStringArray(data, arrayOffsets[3]);
        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码用户NFT失败: " + e.getMessage());
        }
        return result;
    }
    
    public static NftDataResult decodeGetNftData(String hexResponse) {
        NftDataResult result = new NftDataResult();
        try {
            String cleanHex = hexResponse.startsWith("0x") ? hexResponse.substring(2) : hexResponse;
            if (cleanHex.isEmpty()) {
                return result;
            }
            byte[] data = Numeric.hexStringToByteArray(cleanHex);
            
            // 解析动态字符串偏移量
            int[] stringOffsets = new int[4];
            for (int i = 0; i < 4; i++) {
                stringOffsets[i] = Numeric.toBigInt(data, i * 32, 32).intValue();
            }
            
            result.name = decodeStringStrict(data, stringOffsets[0]);
            result.description = decodeStringStrict(data, stringOffsets[1]);
            result.imageUrl = decodeStringStrict(data, stringOffsets[2]);
            result.owner = decodeStringStrict(data, stringOffsets[3]);
        } catch (Exception e) {
            Log.e("ABI_DECODE", "解码NFT数据失败: " + e.getMessage());
        }
        return result;
    }


    public static String encodeCreateGameRoom(
            int minPlayers, int maxPlayers, BigInteger minStake,
            BigInteger maxStake, BigInteger[] cardCounts, int jokerCount
    ) {
        // 函数选择器（createGameRoom的ABI选择器，从Remix导出）
        String SELECTOR = "0x1ce07d51";

        StringBuilder data = new StringBuilder(SELECTOR);
        // 编码参数：minPlayers（uint256）
        data.append(padLeft(BigInteger.valueOf(minPlayers).toString(16), 64));
        // 编码maxPlayers（uint256）
        data.append(padLeft(BigInteger.valueOf(maxPlayers).toString(16), 64));
        // 编码minStake（uint256）
        data.append(padLeft(minStake.toString(16), 64));
        // 编码maxStake（uint256）
        data.append(padLeft(maxStake.toString(16), 64));
        // 编码cardCounts（uint256[10]）
        for (BigInteger count : cardCounts) {
            data.append(padLeft(count.toString(16), 64));
        }
        // 编码jokerCount（uint256）
        data.append(padLeft(BigInteger.valueOf(jokerCount).toString(16), 64));

        return data.toString();
    }

    // 调用 gameRooms(uint256) → 返回地址
    public static String encodeGameRooms(BigInteger gameId) {
        String selector = "0x426c4641"; // gameRooms(uint256)
        String param = padLeft(gameId.toString(16), 64);
        return selector + param;
    }

    public static String decodeAddress(String result) {
        // eth_call 可能返回 "0x" / "0x0" / 非32字节长度，这里做健壮处理，避免崩溃
        if (result == null) return "0x0000000000000000000000000000000000000000";
        if (result.startsWith("0x")) result = result.substring(2);
        // 空/太短直接视为零地址
        if (result.length() < 40) return "0x0000000000000000000000000000000000000000";
        // 取最后 40 个字符（地址长度为 20 字节 = 40 个十六进制字符）
        String addr = result.substring(result.length() - 40);
        return "0x" + addr;
    }

    // 在 ABIUtils.java 中添加
    public static String encodeJoinGame() {
        // 函数选择器：joinGame() 的 Keccak-256 哈希前 4 字节
        // 从 Remix IDE 合约编译界面的 "ABI" 或 "Bytecode" 中复制
        String selector = "0xd4f77b1c"; // 替换为实际的函数选择器，例如 "0x7e2f2f2"
        return selector; // 无参函数仅需函数选择器
    }

    public static String encodeGameState() {
        return "0xd1f9c24d"; // gameState()
    }

    public static String encodeConfig() {
        return "0x79502c55"; // config()
    }

    // 根据索引获取玩家地址 players(uint256)
    public static String encodeGetPlayerByIndex(int index) {
        String selector = "0xf71d96cb";
        String param = padLeft(Integer.toHexString(index), 64);
        return selector + param;
    }

    // 获取玩家信息（含质押金额）playerData(address)
    public static String encodePlayerData(String addr) {
        String selector = "0x424aeded";
        String param = padLeft(addr.substring(2), 64);
        return selector + param;
    }

    // ====================== 工具 ======================

    public static String decodeStakeAmount(String hex) {
        // playerData 返回第 2 个参数是 stakeAmount（uint256）
        // 结构：addr(20)+stake(32)+isActive(32)+handCards(32*10)+joker(32)
        if (hex.length() < 130) return "0";
        return hex.substring(66, 130);
    }

    // ====================== Game: Encode ======================

    /**
     * createGameRoom(..., vrf..., ecvrfRelay)。
     * vrfCoordinator 非 0：Chainlink；否则 ecvrfRelay 非 0：ECVRF 中继；否则同步发牌。
     */
    public static String encodeCreateGameRoomV2(
            BigInteger minPlayers,
            BigInteger maxPlayers,
            BigInteger minStake,
            BigInteger maxStake,
            BigInteger jokerCount,
            BigInteger[] cardCounts,
            String vrfCoordinator,
            BigInteger vrfSubId,
            String vrfKeyHashHexNoPrefix,
            BigInteger vrfCallbackGasLimit,
            BigInteger vrfRequestConfirmations,
            String ecvrfRelay
    ) {
        String sig = "createGameRoom(uint256,uint256,uint256,uint256,uint256,uint256[10],address,uint64,bytes32,uint32,uint16,address)";
        StringBuilder data = new StringBuilder(selector(sig));
        data.append(padLeft(minPlayers.toString(16), 64));
        data.append(padLeft(maxPlayers.toString(16), 64));
        data.append(padLeft(minStake.toString(16), 64));
        data.append(padLeft(maxStake.toString(16), 64));
        data.append(padLeft(jokerCount.toString(16), 64));
        for (int i = 0; i < 10; i++) {
            BigInteger v = (cardCounts != null && i < cardCounts.length && cardCounts[i] != null)
                    ? cardCounts[i] : BigInteger.ZERO;
            data.append(padLeft(v.toString(16), 64));
        }
        data.append(padLeft(cleanAddress(vrfCoordinator), 64));
        BigInteger sub = vrfSubId != null ? vrfSubId : BigInteger.ZERO;
        data.append(padLeft(sub.toString(16), 64));
        data.append(padBytes32Hex(vrfKeyHashHexNoPrefix));
        BigInteger gas = vrfCallbackGasLimit != null ? vrfCallbackGasLimit : BigInteger.ZERO;
        data.append(padLeft(gas.toString(16), 64));
        BigInteger conf = vrfRequestConfirmations != null ? vrfRequestConfirmations : BigInteger.ZERO;
        data.append(padLeft(conf.toString(16), 64));
        data.append(padLeft(cleanAddress(ecvrfRelay), 64));
        return data.toString();
    }

    /** bytes32：无 0x 的 hex，不足 64 字符左侧补 0 */
    private static String padBytes32Hex(String hexNoPrefix) {
        String h = cleanHex(hexNoPrefix);
        if (h.length() > 64) {
            h = h.substring(h.length() - 64);
        }
        return padLeft(h, 64);
    }

    public static String encodeGetGameRoom(BigInteger gameId) {
        String selector = selector("getGameRoom(uint256)");
        return selector + padLeft(gameId.toString(16), 64);
    }

    public static String encodeGetGameConfig(BigInteger gameId) {
        String selector = selector("gameConfigs(uint256)");
        return selector + padLeft(gameId.toString(16), 64);
    }

    public static String encodeJoinGameV2() {
        return selector("joinGame()");
    }

    public static String encodeStartGame() {
        return selector("startGame()");
    }

    /** GameRoom 不可变房主，用于等待页判断谁可点「开始」 */
    public static String encodeRoomOwner() {
        return selector("roomOwner()");
    }

    public static String encodeGetPlayerCards(String player) {
        return selector("getPlayerCards(address)") + padLeft(cleanAddress(player), 64);
    }

    public static String encodeHandDisplaySeed(String player) {
        return selector("handDisplaySeed(address)") + padLeft(cleanAddress(player), 64);
    }

    public static String encodeGetPlayerStake(BigInteger gameId, String player) {
        return selector("getPlayerStake(uint256,address)")
                + padLeft(gameId.toString(16), 64)
                + padLeft(cleanAddress(player), 64);
    }

    // 兼容旧签名 takeCard(address,uint8)
    public static String encodeTakeCard(String target, int cardNumber) {
        return selector("takeCard(address,uint8)")
                + padLeft(cleanAddress(target), 64)
                + padLeft(Integer.toHexString(cardNumber), 64);
    }

    // 新签名 takeCard(uint8)
    public static String encodeTakeCard(int cardNumber) {
        return selector("takeCard(uint8)") + padLeft(Integer.toHexString(cardNumber), 64);
    }

    public static String encodeGetCurrentTurnPlayer() {
        return selector("getCurrentTurnPlayer()");
    }

    public static String encodeGetTakeTarget() {
        return selector("getTakeTarget()");
    }

    /** GameRoom.getPlayers()：用于在 RPC 不支持 eth_getLogs 时拉取玩家列表 */
    public static String encodeGetPlayers() {
        return selector("getPlayers()");
    }

    public static String encodeGetPlayerCount() {
        return selector("getPlayerCount()");
    }

    // ====================== Vault: Encode ======================

    public static String encodeVaultFactory() {
        return selector("factory()");
    }

    public static String encodeVaultFeeReceiver() {
        return selector("feeReceiver()");
    }

    // ====================== Factory: Encode ======================

    /** GameFactory.stakingVault() */
    public static String encodeFactoryStakingVault() {
        return selector("stakingVault()");
    }

    // ====================== Game: Decode ======================

    public static BigInteger decodeUint256(String hexData, int index) {
        String clean = cleanHex(hexData);
        int start = index * 64;
        int end = start + 64;
        if (clean.length() < end) return BigInteger.ZERO;
        return new BigInteger(clean.substring(start, end), 16);
    }

    public static BigInteger[] decodeUint256Array(String hexData, int startIndex, int length) {
        BigInteger[] arr = new BigInteger[length];
        for (int i = 0; i < length; i++) {
            arr[i] = decodeUint256(hexData, startIndex + i);
        }
        return arr;
    }

    /**
     * 解码单个 ABI 动态 address[]（从 data 开头即该数组的 head 开始）。
     */
    public static List<String> decodeAddressArray(String hexData, int ignoredOffset) {
        List<String> out = new ArrayList<>();
        String clean = cleanHex(hexData);
        if (clean.length() < 128) return out;

        int arrOffsetBytes = new BigInteger(clean.substring(0, 64), 16).intValue();
        int arrOffset = arrOffsetBytes * 2;
        if (clean.length() < arrOffset + 64) return out;

        int len = new BigInteger(clean.substring(arrOffset, arrOffset + 64), 16).intValue();
        int cursor = arrOffset + 64;
        for (int i = 0; i < len; i++) {
            int end = cursor + 64;
            if (clean.length() < end) break;
            out.add("0x" + clean.substring(cursor + 24, end));
            cursor = end;
        }
        return out;
    }

    /**
     * GameEnded(uint256 indexed gameId, address[] losers, address[] winners) 的 data 区：
     * 前两字为两个动态数组的偏移量，随后依次展开两个 address[]。
     */
    public static void decodeGameEndedLosersWinners(String hexData, List<String> losersOut, List<String> winnersOut) {
        losersOut.clear();
        winnersOut.clear();
        String clean = cleanHex(hexData);
        if (clean.length() < 128) return;

        int offLosersBytes = new BigInteger(clean.substring(0, 64), 16).intValue();
        int offWinnersBytes = new BigInteger(clean.substring(64, 128), 16).intValue();
        int offLosers = offLosersBytes * 2;
        int offWinners = offWinnersBytes * 2;

        if (clean.length() < offLosers + 64) return;
        int lenL = new BigInteger(clean.substring(offLosers, offLosers + 64), 16).intValue();
        int cur = offLosers + 64;
        for (int i = 0; i < lenL; i++) {
            if (clean.length() < cur + 64) break;
            losersOut.add("0x" + clean.substring(cur + 24, cur + 64));
            cur += 64;
        }

        if (clean.length() < offWinners + 64) return;
        int lenW = new BigInteger(clean.substring(offWinners, offWinners + 64), 16).intValue();
        cur = offWinners + 64;
        for (int i = 0; i < lenW; i++) {
            if (clean.length() < cur + 64) break;
            winnersOut.add("0x" + clean.substring(cur + 24, cur + 64));
            cur += 64;
        }
    }

    // ====================== Helpers ======================

    public static String getEventSignatureHash(String signature) {
        return keccak256Hex(signature).substring(0, 64);
    }

    private static String selector(String signature) {
        return "0x" + keccak256Hex(signature).substring(0, 8);
    }

    private static String keccak256Hex(String text) {
        // IMPORTANT: Ethereum uses Keccak-256 (not standardized SHA3-256).
        // Use web3j's Keccak implementation for Android compatibility.
        String hex = Hash.sha3String(text); // returns 0x-prefixed hex
        return cleanHex(hex);
    }

    private static String cleanHex(String s) {
        return s != null && s.startsWith("0x") ? s.substring(2) : (s == null ? "" : s);
    }

    private static String cleanAddress(String addr) {
        if (addr == null) return "";
        return addr.startsWith("0x") ? addr.substring(2) : addr;
    }


}