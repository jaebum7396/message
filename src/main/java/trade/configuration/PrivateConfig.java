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
}