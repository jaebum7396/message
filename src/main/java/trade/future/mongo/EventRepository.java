package trade.future.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;
import trade.future.model.entity.EventEntity;

public interface EventRepository extends MongoRepository<EventEntity, String> {

}