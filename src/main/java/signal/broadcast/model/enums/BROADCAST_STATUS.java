package signal.broadcast.model.enums;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public enum BROADCAST_STATUS {
	ACTIVE("ACTIVE"),
	CLOSE("CLOSE");

	@Getter
	private String broadcastStatus;

	BROADCAST_STATUS(String orderType) {
		this.broadcastStatus = broadcastStatus;
	}

	public static List<String> getBroadcastStatusList() {
		return Arrays.asList(ACTIVE.getBroadcastStatus(), CLOSE.getBroadcastStatus());
	}
}
