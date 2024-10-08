package signal.common;

import org.ta4j.core.Bar;

import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class 포맷유틸 {
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

    /**
     * <h3>BigDecimal dollar 포맷.</h3>
     */
    public static String getDollarFormat(String amount) {
        // 숫자 포맷 지정
        DecimalFormat df = new DecimalFormat("#,###.##");
        return "$" + df.format(Double.parseDouble(amount));
    }

    /**
     * <h3>타임스탬프를 LocalDateTime으로 변환합니다.</h3>
     */
    public static LocalDateTime convertTimestampToDateTime(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return LocalDateTime.ofInstant(instant, ZoneId.of(ZoneOffset.UTC.getId()));
    }

    public static LocalDateTime convertTimestampToDateTimeKr(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return LocalDateTime.ofInstant(instant, ZoneId.of(ZoneOffset.UTC.getId())).atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
    }
    public static String krTimeExpression(Bar bar){
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = bar.getEndTime(); //캔들이 !!!끝나는 시간!!!
        return krTimeExpression(utcEndTime);
    }
    public static String krTimeExpression(ZonedDateTime utcEndTime){
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul")); //한국시간 설정
        String formattedEndTime = formatter.format(kstEndTime);
        String krTimeExpression = "["+formattedEndTime+"]";
        return krTimeExpression;
    }
}
