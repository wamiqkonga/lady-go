package com.ladygo.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ValidationException;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.web.dto.DeclarationResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SelfDeclarationServiceTest extends PersistenceTestSupport {

    @Autowired
    private SelfDeclarationService service;

    @Test
    void acceptingTheCurrentVersionSucceeds() {
        UUID subjectId = UUID.randomUUID();

        DeclarationResponse response = service.accept(SubjectType.CUSTOMER, subjectId, "v1");

        assertThat(response.declarationVersion()).isEqualTo("v1");
        assertThat(service.hasAcceptedCurrentVersion(SubjectType.CUSTOMER, subjectId)).isTrue();
    }

    @Test
    void acceptingAnUnknownVersionIsRejected() {
        UUID subjectId = UUID.randomUUID();

        assertThatThrownBy(() -> service.accept(SubjectType.CUSTOMER, subjectId, "not-a-real-version"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptingTheSameVersionTwiceIsIdempotent() {
        UUID subjectId = UUID.randomUUID();
        service.accept(SubjectType.CUSTOMER, subjectId, "v1");

        DeclarationResponse second = service.accept(SubjectType.CUSTOMER, subjectId, "v1");

        assertThat(second.declarationVersion()).isEqualTo("v1");
    }

    @Test
    void hasAcceptedCurrentVersionIsFalseBeforeAcceptance() {
        assertThat(service.hasAcceptedCurrentVersion(SubjectType.DRIVER, UUID.randomUUID())).isFalse();
    }

    @Test
    void acceptanceIsScopedBySubjectTypeAndId() {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        service.accept(SubjectType.CUSTOMER, customerId, "v1");

        assertThat(service.hasAcceptedCurrentVersion(SubjectType.CUSTOMER, customerId)).isTrue();
        assertThat(service.hasAcceptedCurrentVersion(SubjectType.DRIVER, driverId)).isFalse();
        assertThat(service.hasAcceptedCurrentVersion(SubjectType.CUSTOMER, UUID.randomUUID())).isFalse();
    }
}
