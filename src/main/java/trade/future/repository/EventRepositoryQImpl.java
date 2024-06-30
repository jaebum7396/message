package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.*;

import javax.persistence.EntityManager;
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

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;
        QTechnicalIndicatorReportEntity qTechnicalIndicatorReport = QTechnicalIndicatorReportEntity.technicalIndicatorReportEntity;

        Optional<EventEntity> result = Optional.ofNullable(
                queryFactory
                .selectFrom(qKlineEvent)
                .join(qKlineEvent.klineEntity, qKline).fetchJoin()
                .join(qKlineEvent.tradingEntity, qTradingEntity).fetchJoin()
                .join(qKlineEvent.klineEntity.positionEntity, qPosition).fetchJoin()
                .join(qKlineEvent.klineEntity.technicalIndicatorReportEntity, qTechnicalIndicatorReport).fetchJoin()
                .where(
                        qKlineEvent.klineEntity.symbol.eq(symbol),
                        qPosition.positionStatus.eq(positionStatus)
                )
                .fetchOne());
        return result;
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