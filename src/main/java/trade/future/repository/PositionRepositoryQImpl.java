package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.QKlineEntity;
import trade.future.model.entity.QPositionEntity;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class PositionRepositoryQImpl implements PositionRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<PositionEntity> getPositionByKlineEndTime(String symbol, LocalDateTime endTime, String positionStatus) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QPositionEntity qPosition = QPositionEntity.positionEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<PositionEntity> result = queryFactory
            .selectFrom(qPosition)
            .join(qPosition.klineEntity, qKline).fetchJoin()
            .where(
                qPosition.klineEntity.symbol.eq(symbol)
                .and(qPosition.positionStatus.eq(positionStatus)
                    .or(qPosition.klineEntity.endTime.eq(endTime).and(qPosition.positionStatus.ne("NONE"))))
            )
            .fetch();
        return result;
    }
}