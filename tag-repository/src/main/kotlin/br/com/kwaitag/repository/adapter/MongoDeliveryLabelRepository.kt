package br.com.kwaitag.repository.adapter

import br.com.kwaitag.repository.model.OrderDeliveryLabelEntity
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class MongoDeliveryLabelRepository : PanacheMongoRepositoryBase<OrderDeliveryLabelEntity, String>

//Ao herdar de PanacheMongoRepositoryBase, a classe ganha métodos como findById, persist, delete, sem escrever nenhum.
//Os dois tipos entre < > dizem: "esta classe mexe com OrderDeliveryLabelEntity, e o id dela é String".