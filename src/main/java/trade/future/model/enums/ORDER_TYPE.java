package trade.future.model.enums;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public enum ORDER_TYPE {
	LIMIT("LIMIT"),
	MARKET("MARKET"),
	STOP("STOP"),
	STOP_MARKET("STOP_MARKET"),
	TAKE_PROFIT("TAKE_PROFIT"),
	TAKE_PROFIT_MARKET("TAKE_PROFIT_MARKET"),
	TRAILING_STOP_MARKET("TRAILING_STOP_MARKET");

	private String orderType;

	ORDER_TYPE(String orderType) {
		this.orderType = orderType;
	}

	public String getOrderType() {
		return orderType;
	}

	public static List<String> getOrderTypeList() {
		return Arrays.asList("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET");
	}
}
