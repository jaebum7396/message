package trade.future.model.entity;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;
import trade.future.model.enums.ADX_GRADE;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "TECHNICAL_INDICATOR_REPORT")
public class TechnicalIndicatorReportEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "TECHNICAL_INDICATOR_REPORT_CD")
    private String technicalIndicatorReportCd; // ID 필드 추가 (데이터베이스 식별자)

    @Column( name = "SYMBOL")
    private String symbol; // 심볼

    @Column( name = "END_TIME")
    private LocalDateTime endTime; // Kline 종료 시간

    @Column( name = "CURRENT_ADX")
    private double currentAdx;

    @Column( name = "CURRENT_ADX_GRADE")
    private ADX_GRADE currentAdxGrade;

    @Column( name = "PREVIOUS_ADX")
    private double previousAdx;

    @Column( name = "PREVIOUS_ADX_GRADE")
    private ADX_GRADE previousAdxGrade;

    @Column( name = "ADX_SIGNAL")
    private int adxSignal;

    @Column( name = "PLUS_DI")
    private double plusDi;

    @Column( name = "MINUS_DI")
    private double minusDi;

    @Column( name = "DIRECTION_DI")
    private String directionDi;

    @Column( name = "SMA")
    private BigDecimal sma;

    @Column( name = "EMA")
    private BigDecimal ema;

    @Column( name = "DIRECTION_MA")
    private String directionMa;

    @Column( name = "UBB")
    private BigDecimal ubb; // Upper Bollinger Band

    @Column( name = "MBB")
    private BigDecimal mbb; // Middle Bollinger Band

    @Column( name = "LBB")
    private BigDecimal lbb; // Lower Bollinger Band

    @Column( name = "RSI")
    private BigDecimal rsi; // Relative Strength Index

    @Column( name = "MACD")
    private BigDecimal macd; // Moving Average Convergence Divergence

    @Column( name = "MACD_GOLDEN_CROSS")
    private boolean macdGoldenCross;

    @Column( name = "MACD_DEAD_CROSS")
    private boolean macdDeadCross;

    @Override
    public String toString() {
        return null;
    }
}