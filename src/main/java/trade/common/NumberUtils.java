package trade.common;

import java.math.BigDecimal;

public class NumberUtils {
    public static BigDecimal doulbleToBigDecimal(double value) {
        try{
            if (Double.isInfinite(value) || Double.isNaN(value)) {
                throw new IllegalArgumentException("Value cannot be infinite or NaN: " + value);
            }
        } catch (IllegalArgumentException e) {
            value = 0;
        }
        return BigDecimal.valueOf(value);
    }
}
