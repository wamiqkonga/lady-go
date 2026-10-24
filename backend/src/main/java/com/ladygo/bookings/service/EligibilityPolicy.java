package com.ladygo.bookings.service;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.customers.domain.Customer;

/**
 * Seam for D5 (rider/driver eligibility - docs/development/roadmap.md), deliberately not enforced
 * here (docs/api/booking.md section 7). D5 itself is undecided; this interface exists so a real
 * policy can be built later without touching booking logic, not to encode a business rule now.
 */
public interface EligibilityPolicy {

    EligibilityDecision checkCustomerEligibility(Customer customer);

    EligibilityDecision checkCompanions(BookingRequest request);
}
