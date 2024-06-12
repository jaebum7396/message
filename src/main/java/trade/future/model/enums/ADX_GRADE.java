package trade.future.model.enums;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum ADX_GRADE {
	횡보(0),
	약한추세(1),
	추세확정(2),
	강한추세(3),
	매우강한추세(4);

	private int grade;

	ADX_GRADE(int i) {
		this.grade = i;
	}

	public int getGrade() {
		return this.grade;
	}
}
