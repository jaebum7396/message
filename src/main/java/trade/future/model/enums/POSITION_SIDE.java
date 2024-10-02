package trade.future.model.enums;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public enum POSITION_SIDE {
	LONG("LONG"),
	SHORT("SHORT");

	private String positionSide;

	POSITION_SIDE(String positionSide) {
		this.positionSide = positionSide;
	}

	public String getPositionSide() {
		return positionSide;
	}

	public static List<String> getPositionSideList() {
		return Arrays.asList("LONG", "SHORT");
	}
}
