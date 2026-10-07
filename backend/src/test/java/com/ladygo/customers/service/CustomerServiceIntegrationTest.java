package com.ladygo.customers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.LimitExceededException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.web.dto.CustomerProfileResponse;
import com.ladygo.customers.web.dto.EmergencyContactRequest;
import com.ladygo.customers.web.dto.EmergencyContactResponse;
import com.ladygo.customers.web.dto.SavedLocationRequest;
import com.ladygo.customers.web.dto.SavedLocationResponse;
import com.ladygo.customers.web.dto.UpdateProfileRequest;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

class CustomerServiceIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private CustomerService customerService;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID newCustomerUserId() {
        Customer customer = newCustomer();
        return users.findById(customer.getUserId()).orElseThrow().getId();
    }

    @Test
    void getProfileCombinesUserAndCustomerFields() {
        Customer customer = newCustomer();
        User user = users.findById(customer.getUserId()).orElseThrow();
        user.setName("Asha");
        users.saveAndFlush(user);

        CustomerProfileResponse profile = customerService.getProfile(user.getId());

        assertThat(profile.id()).isEqualTo(customer.getId());
        assertThat(profile.phone()).isEqualTo(user.getPhone());
        assertThat(profile.name()).isEqualTo("Asha");
    }

    @Test
    void updateProfileChangesNameAndEmail() {
        UUID userId = newCustomerUserId();

        CustomerProfileResponse updated = customerService.updateProfile(
                userId, new UpdateProfileRequest("New Name", "new@example.com", null));

        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.email()).isEqualTo("new@example.com");
    }

    @Test
    void updateProfileRejectsAnUnknownDefaultCityId() {
        UUID userId = newCustomerUserId();

        assertThatThrownBy(() -> customerService.updateProfile(
                userId, new UpdateProfileRequest("Name", null, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateProfileRejectsAnEmailAlreadyUsedByAnotherAccount() {
        UUID firstUserId = newCustomerUserId();
        customerService.updateProfile(firstUserId, new UpdateProfileRequest("First", "taken@example.com", null));

        UUID secondUserId = newCustomerUserId();
        assertThatThrownBy(() -> customerService.updateProfile(
                secondUserId, new UpdateProfileRequest("Second", "TAKEN@example.com", null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void savedLocationCrudRoundTrip() {
        UUID userId = newCustomerUserId();

        SavedLocationResponse created = customerService.createSavedLocation(
                userId, new SavedLocationRequest("Home", "Lal Chowk", 34.08, 74.80));
        assertThat(customerService.listSavedLocations(userId)).hasSize(1);

        SavedLocationResponse updated = customerService.updateSavedLocation(
                userId, created.id(), new SavedLocationRequest("Home", "Updated address", 34.09, 74.81));
        assertThat(updated.address()).isEqualTo("Updated address");

        customerService.deleteSavedLocation(userId, created.id());
        assertThat(customerService.listSavedLocations(userId)).isEmpty();
    }

    @Test
    void savedLocationLimitIsEnforced() {
        UUID userId = newCustomerUserId();
        jdbc.update("UPDATE configuration SET value = '2' WHERE key = 'customer.saved_locations.max.count'");

        customerService.createSavedLocation(userId, new SavedLocationRequest("A", "Addr A", 34.0, 74.0));
        customerService.createSavedLocation(userId, new SavedLocationRequest("B", "Addr B", 34.1, 74.1));

        assertThatThrownBy(() -> customerService.createSavedLocation(
                userId, new SavedLocationRequest("C", "Addr C", 34.2, 74.2)))
                .isInstanceOf(LimitExceededException.class);
    }

    /**
     * Adversarial review finding, MEDIUM: a plain count-then-insert has no protection against two
     * concurrent requests both passing the check before either commits. Two real threads (each gets
     * its own transaction, same as two concurrent HTTP requests) race to create a second saved
     * location against a limit of 1; {@code CustomerRepository#lockById} must serialize them so
     * exactly one succeeds and the final count never exceeds the limit.
     */
    @Test
    void concurrentSavedLocationCreatesNeverExceedTheLimit() throws InterruptedException {
        Customer customer = newCustomer();
        jdbc.update("UPDATE configuration SET value = '1' WHERE key = 'customer.saved_locations.max.count'");
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable createHome = () -> {
            ready.countDown();
            await(go);
            try {
                customerService.createSavedLocation(customer.getUserId(), new SavedLocationRequest("Home", "Addr A", 34.0, 74.0));
            } catch (Throwable ex) {
                errors.add(ex);
            }
        };
        Runnable createWork = () -> {
            ready.countDown();
            await(go);
            try {
                customerService.createSavedLocation(customer.getUserId(), new SavedLocationRequest("Work", "Addr B", 34.1, 74.1));
            } catch (Throwable ex) {
                errors.add(ex);
            }
        };
        Thread t1 = new Thread(createHome);
        Thread t2 = new Thread(createWork);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM saved_locations WHERE customer_id = ?", Integer.class, customer.getId());
        assertThat(count).isEqualTo(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(LimitExceededException.class);

        jdbc.update("DELETE FROM saved_locations WHERE customer_id = ?", customer.getId());
        jdbc.update("DELETE FROM customers WHERE id = ?", customer.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", customer.getUserId());
        TestTransaction.flagForCommit();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void savedLocationOwnershipIsEnforced() {
        UUID ownerId = newCustomerUserId();
        SavedLocationResponse location = customerService.createSavedLocation(
                ownerId, new SavedLocationRequest("Home", "Addr", 34.0, 74.0));

        UUID strangerId = newCustomerUserId();
        assertThatThrownBy(() -> customerService.deleteSavedLocation(strangerId, location.id()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void duplicateLabelForTheSameCustomerIsRejected() {
        UUID userId = newCustomerUserId();
        customerService.createSavedLocation(userId, new SavedLocationRequest("Home", "Addr A", 34.0, 74.0));

        assertThatThrownBy(() -> customerService.createSavedLocation(
                userId, new SavedLocationRequest("Home", "Addr B", 34.1, 74.1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void emergencyContactCrudRoundTrip() {
        UUID userId = newCustomerUserId();

        EmergencyContactResponse created = customerService.createEmergencyContact(
                userId, new EmergencyContactRequest("Mother", nextPhone(), "Parent"));
        assertThat(customerService.listEmergencyContacts(userId)).hasSize(1);

        EmergencyContactResponse updated = customerService.updateEmergencyContact(
                userId, created.id(), new EmergencyContactRequest("Mum", created.phone(), "Mother"));
        assertThat(updated.name()).isEqualTo("Mum");
        assertThat(updated.relationship()).isEqualTo("Mother");

        customerService.deleteEmergencyContact(userId, created.id());
        assertThat(customerService.listEmergencyContacts(userId)).isEmpty();
    }

    @Test
    void emergencyContactLimitIsEnforced() {
        UUID userId = newCustomerUserId();
        jdbc.update("UPDATE configuration SET value = '1' WHERE key = 'customer.emergency_contacts.max.count'");

        customerService.createEmergencyContact(userId, new EmergencyContactRequest("Mother", nextPhone(), "Parent"));

        assertThatThrownBy(() -> customerService.createEmergencyContact(
                userId, new EmergencyContactRequest("Father", nextPhone(), "Parent")))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    void acceptDeclarationSucceeds() {
        UUID userId = newCustomerUserId();

        var response = customerService.acceptDeclaration(userId, "v1");

        assertThat(response.declarationVersion()).isEqualTo("v1");
    }

    @Test
    void captureRideSelfieSucceeds() {
        UUID userId = newCustomerUserId();
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", realJpegBytes());

        var response = customerService.captureRideSelfie(userId, file);

        assertThat(response.rideSelfieId()).isNotNull();
    }
}
