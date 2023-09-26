package trade.future.model.entity;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "KLINE_EVENT")
public class KlineEventEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "KLINE_EVENT_CD")
    private String kLineEventCd; // ID 필드 추가 (데이터베이스 식별자)

    @OneToOne(cascade = CascadeType.ALL) // CascadeType.ALL을 사용하여 관련된 KlineEntity도 저장 및 업데이트
    @JoinColumn(name = "KLINE_CD") // KlineEntity와의 관계를 설정하는 외래 키
    private KlineEntity kline;

    @Column( name = "EVENT_TYPE")
    private String eventType; // 이벤트 타입

    @Column( name = "EVENT_TIME")
    private LocalDateTime eventTime; // 이벤트 시간

    @Column( name = "GOAL_PRICE_CHECK")
    @Builder.Default
    private boolean goalPriceCheck = false; // 목표가 도달 여부

    @Column( name = "PLUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal plusGoalPrice;

    @Column( name = "MINUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal minusGoalPrice;

    @Column( name = "GOAL_PRICE_PERCENT", precision = 19, scale = 8)
    private int goalPricePercent;
}