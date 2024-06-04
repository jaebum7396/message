package trade.future.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;
import trade.future.model.entity.PositionEntity;

public interface PositionRepository extends MongoRepository<PositionEntity, String> {

}