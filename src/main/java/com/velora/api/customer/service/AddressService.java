package com.velora.api.customer.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customer.domain.CustomerAddress;
import com.velora.api.customer.dto.AddressRequest;
import com.velora.api.customer.dto.AddressResponse;
import com.velora.api.customer.repository.CustomerAddressRepository;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.repository.ShippingRateRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's address book.
 *
 * <p>Every lookup is scoped by user id, not just address id. Fetching by id alone
 * would let one customer read another's address by changing the number in the URL.
 */
@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);
    private static final int MAX_ADDRESSES = 10;

    private final CustomerAddressRepository addressRepository;
    private final GovernorateRepository governorateRepository;
    private final ShippingRateRepository rateRepository;
    private final AppUserRepository userRepository;

    public AddressService(CustomerAddressRepository addressRepository,
                          GovernorateRepository governorateRepository,
                          ShippingRateRepository rateRepository,
                          AppUserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.governorateRepository = governorateRepository;
        this.rateRepository = rateRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(Long userId, String locale) {
        return addressRepository
                .findByUserIdAndDeletedAtIsNullOrderByDefaultAddressDescIdDesc(userId)
                .stream()
                .map(address -> toResponse(address, locale))
                .toList();
    }

    @Transactional
    public AddressResponse create(Long userId, AddressRequest request, String locale) {
        if (addressRepository.countByUserIdAndDeletedAtIsNull(userId) >= MAX_ADDRESSES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "You can save at most %d addresses".formatted(MAX_ADDRESSES));
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        CustomerAddress address = new CustomerAddress();
        address.setUser(user);
        apply(address, request);

        // The first address saved becomes the default automatically.
        boolean isFirst = addressRepository.countByUserIdAndDeletedAtIsNull(userId) == 0;
        if (Boolean.TRUE.equals(request.makeDefault()) || isFirst) {
            addressRepository.clearDefaultFor(userId);
            address.setDefaultAddress(true);
        }

        CustomerAddress saved = addressRepository.save(address);
        log.info("Address {} created for user {}", saved.getId(), userId);
        return toResponse(saved, locale);
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressRequest request,
                                  String locale) {
        CustomerAddress address = load(userId, addressId);
        apply(address, request);

        if (Boolean.TRUE.equals(request.makeDefault())) {
            addressRepository.clearDefaultFor(userId);
            address.setDefaultAddress(true);
        }
        return toResponse(addressRepository.save(address), locale);
    }

    /**
     * Soft delete. Orders snapshot the address rather than referencing it, so
     * removing a row cannot damage order history — but keeping it makes the
     * customer's own history readable.
     */
    @Transactional
    public void delete(Long userId, Long addressId) {
        CustomerAddress address = load(userId, addressId);
        boolean wasDefault = address.isDefaultAddress();

        address.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        address.setDefaultAddress(false);
        addressRepository.save(address);

        // Never leave the customer without a default.
        if (wasDefault) {
            addressRepository
                    .findByUserIdAndDeletedAtIsNullOrderByDefaultAddressDescIdDesc(userId)
                    .stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefaultAddress(true);
                        addressRepository.save(next);
                    });
        }
    }

    @Transactional
    public AddressResponse setDefault(Long userId, Long addressId, String locale) {
        CustomerAddress address = load(userId, addressId);
        addressRepository.clearDefaultFor(userId);
        address.setDefaultAddress(true);
        return toResponse(addressRepository.save(address), locale);
    }

    /** Used by checkout, which needs the entity rather than the DTO. */
    @Transactional(readOnly = true)
    public CustomerAddress requireOwned(Long userId, Long addressId) {
        return load(userId, addressId);
    }

    // ------------------------------------------------------------------ internal

    private void apply(CustomerAddress address, AddressRequest request) {
        String phone = PhoneNormalizer.toE164(request.phone());
        if (phone == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT);
        }
        String altPhone = request.altPhone() == null || request.altPhone().isBlank()
                ? null : PhoneNormalizer.toE164(request.altPhone());
        if (request.altPhone() != null && !request.altPhone().isBlank() && altPhone == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT,
                    "The alternate phone number is not a valid Egyptian mobile");
        }

        Governorate governorate = governorateRepository.findById(request.governorateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Governorate not found"));

        // Better to refuse here than to let the customer reach checkout and discover
        // we cannot deliver to them.
        if (rateRepository.findAllForGovernorate(governorate.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.GOVERNORATE_NOT_SERVED,
                    "We do not deliver to %s yet".formatted(governorate.getNameAr()));
        }

        address.setLabel(request.label());
        address.setRecipientName(request.recipientName().trim());
        address.setPhoneE164(phone);
        address.setAltPhoneE164(altPhone);
        address.setGovernorate(governorate);
        address.setArea(trim(request.area()));
        address.setStreetAddress(request.streetAddress().trim());
        address.setBuilding(trim(request.building()));
        address.setFloor(trim(request.floor()));
        address.setApartment(trim(request.apartment()));
        address.setLandmark(trim(request.landmark()));
    }

    private CustomerAddress load(Long userId, Long addressId) {
        return addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                // Identical response whether it does not exist or belongs to someone
                // else — otherwise the API becomes an address-id enumerator.
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Address not found"));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AddressResponse toResponse(CustomerAddress address, String locale) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getRecipientName(),
                PhoneNormalizer.toLocalFormat(address.getPhoneE164()),
                PhoneNormalizer.toLocalFormat(address.getAltPhoneE164()),
                address.getGovernorate().getId(),
                address.getGovernorate().nameFor(locale),
                address.getArea(),
                address.getStreetAddress(),
                address.getBuilding(),
                address.getFloor(),
                address.getApartment(),
                address.getLandmark(),
                address.isDefaultAddress(),
                address.formatted());
    }
}
