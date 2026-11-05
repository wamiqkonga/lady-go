package com.ladygo.admin.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.ladygo.admin.repository.AdminVerificationQueueRepository;
import com.ladygo.admin.repository.AdminVerificationQueueRepository.Filter;
import com.ladygo.admin.repository.AdminVerificationQueueRepository.QueueRow;
import com.ladygo.admin.web.dto.DriverVerificationQueueItemResponse;
import com.ladygo.admin.web.dto.DriverVerificationQueueItemResponse.RequiredItemCounts;
import com.ladygo.common.api.PageResponse;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.vehicles.repository.VehicleRepository;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.service.SelfDeclarationService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The captain verification queue (docs/product/admin-flows.md section 4): one row per captain, oldest submission
 * first, so the person reviewing always starts with whoever has waited longest.
 *
 * <p>"Submitted" is the captain's FIRST upload (a document, the vehicle or the declaration; the account's creation
 * time if somehow none exists). The clock deliberately starts there rather than when the application became complete:
 * it is simple, it can be ordered in SQL, and it never understates how long someone has been waiting. A captain who
 * has not finished still shows how many items are missing, and {@code readyOnly} hides them.
 *
 * <p>Required items are the captain's full name, the vehicle (with its RC photo), the selfie and the self-declaration, plus police
 * verification when that switch is on - the same list {@link AdminVerificationService#approveDriver} insists on.
 */
@Service
public class AdminVerificationQueueService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_OVERDUE_HOURS = 24;
    private static final UUID NIL = new UUID(0L, 0L);

    private final AdminVerificationQueueRepository queue;
    private final DriverDocumentRepository documents;
    private final VehicleRepository vehicles;
    private final SelfDeclarationService declarations;
    private final ConfigurationService config;

    public AdminVerificationQueueService(AdminVerificationQueueRepository queue, DriverDocumentRepository documents,
            VehicleRepository vehicles, SelfDeclarationService declarations, ConfigurationService config) {
        this.queue = queue;
        this.documents = documents;
        this.vehicles = vehicles;
        this.declarations = declarations;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public PageResponse<DriverVerificationQueueItemResponse> list(String q, DriverVerificationStatus status,
            boolean overdueOnly, boolean readyOnly, Pageable requested) {
        Instant now = Instant.now();
        Duration overdueAfter = overdueAfter();
        boolean policeRequired = config.getBoolean("driver.police_verification.required", false);
        String term = q == null ? "" : q.trim();
        String digits = isPhoneLike(term) ? term.replaceAll("\\D", "") : "";

        Filter filter = new Filter(
                status == null ? DriverVerificationStatus.PENDING_REVIEW : status,
                term.isEmpty(), parseUuidOrNil(term), likePattern(term), !digits.isEmpty(), "%" + digits + "%",
                overdueOnly, now.minus(overdueAfter), readyOnly,
                declarations.currentVersionName(SubjectType.DRIVER), policeRequired);

        int size = Math.max(1, Math.min(requested.getPageSize(), MAX_PAGE_SIZE));
        int page = Math.max(0, requested.getPageNumber());
        long total = queue.count(filter);
        List<QueueRow> rows = total == 0 ? List.of() : queue.page(filter, size, (long) page * size);

        List<DriverVerificationQueueItemResponse> content = toItems(rows, now, overdueAfter, policeRequired);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private List<DriverVerificationQueueItemResponse> toItems(
            List<QueueRow> rows, Instant now, Duration overdueAfter, boolean policeRequired) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = rows.stream().map(QueueRow::driverId).collect(Collectors.toSet());
        Map<UUID, List<DriverDocument>> docsByDriver = documents.findByDriverIdIn(ids).stream()
                .collect(Collectors.groupingBy(DriverDocument::getDriverId));
        Map<UUID, Vehicle> vehicleByDriver = new HashMap<>();
        for (Vehicle v : vehicles.findByDriverIdIn(ids)) {
            vehicleByDriver.merge(v.getDriverId(), v,
                    (a, b) -> a.getCreatedAt().isBefore(b.getCreatedAt()) ? a : b);
        }
        Set<UUID> declared = declarations.subjectsWhoAcceptedCurrentVersion(SubjectType.DRIVER, ids);

        return rows.stream().map(row -> {
            List<DriverDocument> docs = docsByDriver.getOrDefault(row.driverId(), List.of());
            Vehicle vehicle = vehicleByDriver.get(row.driverId());
            boolean nameMissing = row.name() == null || row.name().isBlank();
            RequiredItemCounts counts = count(docs, vehicle, declared.contains(row.driverId()), !nameMissing,
                    policeRequired);
            long waiting = Math.max(0, Duration.between(row.submittedAt(), now).getSeconds());
            return new DriverVerificationQueueItemResponse(row.driverId(), row.name(), PhoneMask.mask(row.phone()),
                    row.city(), vehicle == null ? null : vehicle.getRegistrationNumber(), row.status(), counts,
                    hasOptionalDocument(docs, policeRequired), nameMissing, counts.missing() == 0,
                    row.submittedAt(), waiting, isOverdue(row.submittedAt(), now, overdueAfter));
        }).toList();
    }

    /** Over, not at: a captain who has waited exactly the limit is not yet overdue. */
    static boolean isOverdue(Instant submittedAt, Instant now, Duration limit) {
        return Duration.between(submittedAt, now).compareTo(limit) > 0;
    }

    private Duration overdueAfter() {
        return Duration.ofHours(Math.max(1, config.getInt("verification.queue.overdue.hours", DEFAULT_OVERDUE_HOURS)));
    }

    private enum ItemState { APPROVED, PENDING, REJECTED, MISSING }

    private static RequiredItemCounts count(List<DriverDocument> docs, Vehicle vehicle, boolean declared, boolean named,
            boolean policeRequired) {
        DocumentStatus rc = latest(docs, DocumentType.VEHICLE_REGISTRATION);
        ItemState vehicleItem = vehicle == null || rc == null ? ItemState.MISSING
                : combine(vehicleState(vehicle.getVerificationStatus()), state(rc));
        ItemState selfie = stateOrMissing(latest(docs, DocumentType.SELFIE));
        ItemState declaration = declared ? ItemState.APPROVED : ItemState.MISSING;
        // The full name has no review step: given means done, absent means missing.
        ItemState name = named ? ItemState.APPROVED : ItemState.MISSING;

        List<ItemState> items = new java.util.ArrayList<>(List.of(name, vehicleItem, selfie, declaration));
        if (policeRequired) {
            items.add(stateOrMissing(latest(docs, DocumentType.POLICE_VERIFICATION)));
        }
        return new RequiredItemCounts(tally(items, ItemState.APPROVED), tally(items, ItemState.PENDING),
                tally(items, ItemState.REJECTED), tally(items, ItemState.MISSING));
    }

    private static int tally(List<ItemState> items, ItemState wanted) {
        return (int) items.stream().filter(s -> s == wanted).count();
    }

    /** Status of the newest upload of this type (a resubmission supersedes an earlier one); null when never uploaded. */
    private static DocumentStatus latest(List<DriverDocument> docs, DocumentType type) {
        return docs.stream().filter(d -> d.getType() == type)
                .max(Comparator.comparing(DriverDocument::getCreatedAt))
                .map(DriverDocument::getStatus).orElse(null);
    }

    private static ItemState stateOrMissing(DocumentStatus status) {
        return status == null ? ItemState.MISSING : state(status);
    }

    private static ItemState state(DocumentStatus status) {
        if (status == DocumentStatus.APPROVED) {
            return ItemState.APPROVED;
        }
        return status == DocumentStatus.REJECTED ? ItemState.REJECTED : ItemState.PENDING;
    }

    private static ItemState vehicleState(VehicleVerificationStatus status) {
        if (status == VehicleVerificationStatus.APPROVED) {
            return ItemState.APPROVED;
        }
        boolean bad = status == VehicleVerificationStatus.REJECTED || status == VehicleVerificationStatus.SUSPENDED;
        return bad ? ItemState.REJECTED : ItemState.PENDING;
    }

    /** One item made of two parts: any rejected part rejects it, otherwise any pending part keeps it pending. */
    private static ItemState combine(ItemState a, ItemState b) {
        if (a == ItemState.REJECTED || b == ItemState.REJECTED) {
            return ItemState.REJECTED;
        }
        return a == ItemState.PENDING || b == ItemState.PENDING ? ItemState.PENDING : ItemState.APPROVED;
    }

    /** Any upload that is not one of the required documents (licence, fitness certificate, ...). */
    private static boolean hasOptionalDocument(List<DriverDocument> docs, boolean policeRequired) {
        return docs.stream().anyMatch(d -> d.getType() != DocumentType.VEHICLE_REGISTRATION
                && d.getType() != DocumentType.SELFIE
                && !(policeRequired && d.getType() == DocumentType.POLICE_VERIFICATION));
    }

    /** True for text that can only be a phone number (digits and the usual separators), so a name search never
     *  turns into a digit search. */
    private static boolean isPhoneLike(String term) {
        return !term.isEmpty() && term.matches("[+0-9\\s\\-()]+");
    }

    private static UUID parseUuidOrNil(String term) {
        try {
            return UUID.fromString(term);
        } catch (IllegalArgumentException e) {
            return NIL;
        }
    }

    /** Lower-cased LIKE pattern with the user's own %, _ and \ escaped so they match literally. */
    private static String likePattern(String term) {
        String escaped = term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
