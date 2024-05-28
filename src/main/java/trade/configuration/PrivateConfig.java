package trade.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrivateConfig {
    @Value("${binance.real.api}")
    public static String BINANCE_REAL_API_KEY;
    @Value("${binance.real.secret}")
    public static String BINANCE_REAL_SECRET_KEY;
    @Value("${binance.testnet.api}")
    public static String BINANCE_TEST_API_KEY;
    @Value("${binance.testnet.secret}")
    public static String BINANCE_TEST_SECRET_KEY;

    public static final String BASE_URL = "https://testnet.binance.vision";
    public static final String API_KEY = "";
    public static final String SECRET_KEY = ""; // Unnecessary if PRIVATE_KEY_PATH is used
    public static final String PRIVATE_KEY_PATH = ""; // Key must be PKCS#8 standard

    public static final String TESTNET_API_KEY = "";
    public static final String TESTNET_SECRET_KEY = ""; // Unnecessary if TESTNET_PRIVATE_KEY_PATH is used
    public static final String TESTNET_PRIVATE_KEY_PATH = ""; //Key must be PKCS#8 standard

    public static final String PROD_URL = "https://api.binance.com";
    public static final String WS_URL = "wss://stream.binance.com:9443";
    public static final String WS_API_URL = "wss://ws-api.binance.com:443/ws-api/v3";
    public static final String TESTNET_URL = "https://testnet.binance.vision";
    public static final String TESTNET_WS_URL = "wss://testnet.binance.vision";
    public static final String TESTNET_WS_API_URL = "wss://testnet.binance.vision/ws-api/v3";
    public static final String MARKET_URL = "https://data-api.binance.vision";
    public static final String MARKET_WS_URL = "wss://data-stream.binance.vision";
}