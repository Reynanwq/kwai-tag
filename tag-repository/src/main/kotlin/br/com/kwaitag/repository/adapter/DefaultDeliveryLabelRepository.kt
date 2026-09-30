package br.com.kwaitag.repository.adapter

//A classe que implementa a porta do domínio.


import br.com.kwaitag.domain.model.OrderDeliveryLabel
import br.com.kwaitag.domain.port.spi.DeliveryLabelRepository
import br.com.kwaitag.repository.model.OrderDeliveryLabelEntity
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DefaultDeliveryLabelRepository(
    private val mongo: MongoDeliveryLabelRepository,
) : DeliveryLabelRepository {

    override fun findByOrderId(accountId: String, orderId: String): OrderDeliveryLabel? =
        mongo.findById(OrderDeliveryLabelEntity.idOf(accountId, orderId))?.toDomain()

    override fun save(label: OrderDeliveryLabel): OrderDeliveryLabel {
        val expectedVersion = label.version
        val entity = OrderDeliveryLabelEntity.from(label, version = expectedVersion + 1)

        if (expectedVersion == 0L) insert(entity) else update(entity, expectedVersion)

        return entity.toDomain()
    }

    // Versão 0 = label novo: se já existir no banco, outro processo criou antes.
    private fun insert(entity: OrderDeliveryLabelEntity) {
        try {
            mongo.persist(entity)
        } catch (e: MongoWriteException) {
            if (e.error.category == ErrorCategory.DUPLICATE_KEY) {
                throw DeliveryLabelConcurrencyException("Label ${entity.id} was created by another process")
            }
            throw e
        }
    }

    // Só substitui se a versão no banco ainda for a que foi lida.
    private fun update(entity: OrderDeliveryLabelEntity, expectedVersion: Long) {
        val result = mongo.mongoCollection().replaceOne(
            Filters.and(Filters.eq("_id", entity.id), Filters.eq("version", expectedVersion)),
            entity,
        )
        if (result.matchedCount == 0L) {
            throw DeliveryLabelConcurrencyException("Label ${entity.id} changed since version $expectedVersion")
        }
    }
}