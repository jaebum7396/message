package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.*;

import javax.persistence.EntityManager;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepositoryQImpl implements EventRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    public Optional<EventEntity> findEventBySymbolAndPositionStatus(String symbol, String positionStatus) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qEventEntity = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKlineEntity = QKlineEntity.klineEntity;
        QPositionEntity qPositionEntity = QPositionEntity.positionEntity;
        QTechnicalIndicatorReportEntity qTechnicalIndicatorReportEntity = QTechnicalIndicatorReportEntity.technicalIndicatorReportEntity;

        try {
            EventEntity eventEntity = queryFactory
                    .select(qEventEntity)
                    .from(qEventEntity)
                    .join(qEventEntity.klineEntity, qKlineEntity).fetchJoin()
                    .join(qEventEntity.tradingEntity, qTradingEntity).fetchJoin()
                    .join(qKlineEntity.positionEntity, qPositionEntity).fetchJoin()
                    .join(qKlineEntity.technicalIndicatorReportEntity, qTechnicalIndicatorReportEntity).fetchJoin()
                    .where(
                            qKlineEntity.symbol.eq(symbol),
                            qPositionEntity.positionStatus.eq(positionStatus)
                    )
                    .fetchOne();

            return Optional.ofNullable(eventEntity);
        } catch (NonUniqueResultException e) {
            // 예외 처리: 결과가 둘 이상일 경우 예외가 발생할 수 있습니다.
            // 적절한 예외 처리 로직을 추가합니다.
            return Optional.empty();
        }
    }


    public List<EventEntity> findEventByPositionStatus(String positionStatus) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<EventEntity> result =
            queryFactory
                .selectFrom(qKlineEvent)
                .join(qKlineEvent.klineEntity, qKline).fetchJoin()
                .join(qKlineEvent.tradingEntity, qTradingEntity).fetchJoin()
                .join(qKline.positionEntity, qPosition).fetchJoin()
                .where(
                    qPosition.positionStatus.eq(positionStatus)
                )
                .fetch();
        return result;
    }

    public List<EventEntity> findEventsByTradingCd(String tradingCd) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<EventEntity> result =
                queryFactory
                        .selectFrom(qKlineEvent)
                        .join(qKlineEvent.klineEntity, qKline).fetchJoin()
                        .join(qKlineEvent.tradingEntity, qTradingEntity).fetchJoin()
                        .join(qKline.positionEntity, qPosition).fetchJoin()
                        .where(
                            qTradingEntity.tradingCd.eq(tradingCd)
                        )
                        .orderBy(qKlineEvent.eventTime.desc())
                        .fetch();
        return result;
    }

    public List<EventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<EventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.klineEntity, qKline).fetchJoin()
            .join(qKlineEvent.tradingEntity, qTradingEntity).fetchJoin()
            .join(qKline.positionEntity, qPosition).fetchJoin()
            .where(
                qKlineEvent.klineEntity.symbol.eq(symbol),
                qPosition.goalPriceCheck.eq(false),
                qPosition.plusGoalPrice.lt(currentPrice)
            )
            .fetch();
        return result;
    }

    public List<EventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<EventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.klineEntity, qKline).fetchJoin()
            .join(qKlineEvent.tradingEntity, qTradingEntity).fetchJoin()
            .join(qKline.positionEntity, qPosition).fetchJoin()
            .where(
                qKlineEvent.klineEntity.symbol.eq(symbol),
                qPosition.goalPriceCheck.eq(false),
                qPosition.minusGoalPrice.gt(currentPrice)
            )
            .fetch();

        return result;
    }
}