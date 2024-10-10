package signal.broadcast.model.enums;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public enum MODEL_TYPE {
	NORMAL("NORMAL"),
	TREND_PREDICT("TREND_PREDICT"),
	GENETIC("GENETIC"),
	DEEP_LEARNING("DEEP_LEARNING"),
	REWARD_RATIO("REWARD_RATIO");


	private String modelType;

	MODEL_TYPE(String modelType) {
		this.modelType = modelType;
	}

	public String getModelType() {
		return modelType;
	}

	public static List<String> getModelTypes() {
		return Arrays.asList(NORMAL.getModelType(), TREND_PREDICT.getModelType(), GENETIC.getModelType(), DEEP_LEARNING.getModelType(), REWARD_RATIO.getModelType());
	}
}
