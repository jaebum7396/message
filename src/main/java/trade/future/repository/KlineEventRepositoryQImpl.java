package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.QKlineEntity;
import trade.future.model.entity.QKlineEventEntity;
import trade.future.model.entity.QPositionEntity;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;

@Repository
public class KlineEventRepositoryQImpl implements KlineEventRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    public List<KlineEventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QKlineEventEntity qKlineEvent = QKlineEventEntity.klineEventEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<KlineEventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.klineEntity, qKline).fetchJoin()
            .join(qKline.positionEntity, qPosition).fetchJoin()
            .where(
                qKlineEvent.klineEntity.symbol.eq(symbol),
                qPosition.goalPriceCheck.eq(false),
                qPosition.plusGoalPrice.lt(currentPrice)
            )
            .fetch();
        return result;
    }

    public List<KlineEventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QKlineEventEntity qKlineEvent = QKlineEventEntity.klineEventEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<KlineEventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.klineEntity, qKline).fetchJoin()
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