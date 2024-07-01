package trade.future.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "EVENT")
public class EventEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "EVENT_CD")
    private String EventCd; // ID 필드 추가 (데이터베이스 식별자)

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "KLINE_CD", nullable = false)
    private KlineEntity klineEntity;

    //연관관계의 주인
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TRADING_CD", nullable = false)
    private TradingEntity tradingEntity;

    @Column( name = "EVENT_TYPE")
    private String eventType; // 이벤트 타입

    @Column( name = "EVENT_TIME")
    private LocalDateTime eventTime; // 이벤트 시간
}