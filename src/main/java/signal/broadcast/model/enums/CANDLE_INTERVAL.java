package signal.broadcast.model.enums;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public enum CANDLE_INTERVAL {
	ONE_MINUTE("1m"),
	THREE_MINUTE("3m"),
	FIVE_MINUTE("5m"),
	FIFTEEN_MINUTE("15m"),
	THIRTY_MINUTE("30m"),
	ONE_HOUR("1h"),
	FOUR_HOUR("4h"),
	ONE_DAY("1d"),
	ONE_WEEK("1w"),
	ONE_MONTH("1M");

	private String interval;

	CANDLE_INTERVAL(String interval) {
		this.interval = interval;
	}

	public String getInterval() {
		return interval;
	}

	public static List<String> getIntervalList() {
		return Arrays.asList(
				  "1m"
				//, "3m"
				, "5m"
				, "15m"
				//, "30m"
				, "1h"
				, "4h"
				, "1d"
				, "1w"
				//, "1M"
		);
	}
}
