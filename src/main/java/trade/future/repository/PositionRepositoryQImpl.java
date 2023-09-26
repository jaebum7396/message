package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.*;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class PositionRepositoryQImpl implements PositionRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<PositionEntity> getPositionByKlineEndTime(LocalDateTime endTime, String positionStatus) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QPositionEntity qPosition = QPositionEntity.positionEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<PositionEntity> result = queryFactory
            .selectFrom(qPosition)
            .join(qPosition.klineEntity, qKline).fetchJoin()
            .where(
                qPosition.klineEntity.endTime.eq(endTime)
                .and(qPosition.positionStatus.notIn(positionStatus))
            )
            .fetch();
        return result;
    }
}