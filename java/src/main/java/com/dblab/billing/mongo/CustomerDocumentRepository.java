package com.dblab.billing.mongo;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Loading customer documents. findByPremisesMetersSerialNumber walks into the lists: the filter it builds is
 * { "premises.meters.serial_number": ? }, served by Stage 5's multikey index.
 */
public interface CustomerDocumentRepository extends MongoRepository<CustomerDocument, Long> {

    Optional<CustomerDocument> findByPremisesMetersSerialNumber(String serialNumber);
}