package trade.common;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class 출력유틸 {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId seoulZone = ZoneId.of("Asia/Seoul");
    private static final int TIMESTAMP_WIDTH = 21;  // [YYYY-MM-DD HH:MM:SS] 길이
    private static final int SYMBOL_WIDTH = 15;     // 가장 긴 심볼 길이에 맞춤
    private static final String FORMAT = "%-" + TIMESTAMP_WIDTH + "s %-" + SYMBOL_WIDTH + "s %s%n";
    private static final StringBuilder sb = new StringBuilder(100);  // 예상 최대 길이로 초기화

    public static void printAlignedOutput(String symbol, String message) {
        sb.setLength(0);  // StringBuilder 초기화
        sb.append('[')
                .append(formatter.format(ZonedDateTime.now(seoulZone)))
                .append(']');

        System.out.printf(FORMAT, sb.toString(), symbol.toUpperCase(), message);
    }
}
