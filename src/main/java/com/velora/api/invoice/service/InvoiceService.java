package com.velora.api.invoice.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.export.service.PdfRenderer;
import com.velora.api.export.service.TemplateRenderer;
import com.velora.api.invoice.domain.Invoice;
import com.velora.api.invoice.domain.InvoiceSequence;
import com.velora.api.invoice.domain.InvoiceStatus;
import com.velora.api.invoice.dto.InvoiceResponse;
import com.velora.api.invoice.dto.InvoiceView;
import com.velora.api.invoice.repository.InvoiceRepository;
import com.velora.api.invoice.repository.InvoiceSequenceRepository;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.OrderItem;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.store.domain.StoreProfile;
import com.velora.api.store.service.StoreProfileService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues tax invoices.
 *
 * <p>Two rules govern this class:
 * <ol>
 *   <li><b>The number sequence has no gaps.</b> It is allocated under a row lock in
 *       the same transaction that writes the invoice, so a rollback returns the
 *       number instead of burning it. This is why invoice numbering is completely
 *       separate from the order number, which is random on purpose.</li>
 *   <li><b>Both parties are snapshotted.</b> The seller's legal address will change;
 *       an invoice must always show what was printed on it.</li>
 * </ol>
 *
 * <p>Issued on DELIVERY, not on confirmation. With cash on delivery, refusal and
 * failed delivery are common, and an invoice for a sale that never happened needs a
 * credit note to undo — accounting complexity with no upside here.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final InvoiceRepository invoiceRepository;
    private final InvoiceSequenceRepository sequenceRepository;
    private final OrderRepository orderRepository;
    private final StoreProfileService storeProfileService;
    private final TemplateRenderer templateRenderer;
    private final PdfRenderer pdfRenderer;
    private final StorageService storageService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoiceSequenceRepository sequenceRepository,
                          OrderRepository orderRepository,
                          StoreProfileService storeProfileService,
                          TemplateRenderer templateRenderer,
                          PdfRenderer pdfRenderer,
                          StorageService storageService) {
        this.invoiceRepository = invoiceRepository;
        this.sequenceRepository = sequenceRepository;
        this.orderRepository = orderRepository;
        this.storeProfileService = storeProfileService;
        this.templateRenderer = templateRenderer;
        this.pdfRenderer = pdfRenderer;
        this.storageService = storageService;
    }

    // ------------------------------------------------------------------- issue

    /**
     * Issues the invoice for a delivered order.
     *
     * <p>Called automatically when an order reaches DELIVERED, and available manually
     * for the orders that slipped through. Idempotent: a second call returns the
     * existing invoice rather than burning another number.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Invoice issueForOrder(Long orderId) {
        Invoice existing = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            log.debug("Order {} already has invoice {}", orderId, existing.getInvoiceNumber());
            return existing;
        }

        CustomerOrder order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "An invoice is issued on delivery. This order is " 
                            + order.getFulfillmentStatus());
        }

        StoreProfile seller = storeProfileService.require();

        int year = LocalDate.now().getYear();
        int sequence = allocateNumber(year);
        String invoiceNumber = "VLR-INV-%d-%06d".formatted(year, sequence);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setFiscalYear(year);
        invoice.setSequenceNumber(sequence);
        invoice.setOrder(order);
        invoice.setStatus(InvoiceStatus.ISSUED);

        // ---- seller snapshot ----
        invoice.setSellerName(seller.getLegalName());
        invoice.setSellerAddress(seller.getAddress());
        invoice.setSellerPhone(seller.getPhone());
        invoice.setSellerEmail(seller.getEmail());
        invoice.setSellerTaxNumber(seller.getTaxNumber());
        invoice.setSellerCommercialRegister(seller.getCommercialRegister());

        // ---- buyer snapshot ----
        invoice.setBuyerName(order.getContactName());
        invoice.setBuyerPhone(order.getContactPhone());
        invoice.setBuyerAddress(order.formattedAddress());

        // ---- money snapshot ----
        invoice.setCurrency(order.getCurrency());
        invoice.setSubtotalGross(order.getSubtotalGross());
        invoice.setDiscountTotal(order.getDiscountTotal());
        invoice.setShippingCost(order.getShippingCost());
        invoice.setCodFee(order.getCodFee());
        invoice.setAssemblyTotal(order.getAssemblyTotal());
        invoice.setGrandTotal(order.getGrandTotal());
        invoice.setTaxTotal(order.getTaxTotal());
        invoice.setNetTotal(order.getNetTotal());
        invoice.setPaymentMethod(order.getPaymentMethod().name());

        Invoice saved = invoiceRepository.save(invoice);

        // The PDF is generated after the row exists so a rendering failure cannot
        // leave a numbered invoice with no record. It is regenerable at any time.
        generateAndStorePdf(saved, order, seller);

        log.info("Issued invoice {} for order {} ({} {})",
                invoiceNumber, order.getOrderNumber(),
                order.getGrandTotal(), order.getCurrency());

        return saved;
    }

    /**
     * Takes the next number for the year, under a row lock.
     *
     * <p>Concurrent issuers block here rather than reading the same value. The lock
     * is released when the transaction ends, so it is held for milliseconds.
     */
    int allocateNumber(int year) {
        // First invoice of a new year: the counter row is created here, seeded from the
        // highest existing number so a manually inserted row cannot cause a duplicate.
        // Done as one atomic statement, not "look for it, else insert": two invoices
        // issued at the same instant would otherwise both insert, and one would fail.
        sequenceRepository.ensureYear(year);

        InvoiceSequence sequence = sequenceRepository.lockForYear(year)
                .orElseThrow(() -> new IllegalStateException(
                        "Invoice sequence row for " + year + " disappeared after being ensured"));

        int next = sequence.next();
        sequenceRepository.save(sequence);
        return next;
    }

    // ---------------------------------------------------------------- cancel

    /**
     * Voids an invoice. The number stays consumed and the row stays in place — a
     * cancelled invoice still has to be explainable to an auditor.
     */
    @Transactional
    public Invoice cancel(Long invoiceId, String reason) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Invoice not found"));

        if (invoice.isCancelled()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This invoice is already cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to cancel an invoice");
        }

        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelledAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        invoice.setCancelReason(reason);

        log.warn("Cancelled invoice {}: {}", invoice.getInvoiceNumber(), reason);
        return invoiceRepository.save(invoice);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public byte[] renderPdf(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Invoice not found"));
        return renderPdf(invoice);
    }

    @Transactional(readOnly = true)
    public byte[] renderPdfForCustomer(Long customerId, String invoiceNumber) {
        Invoice invoice = invoiceRepository
                .findByNumberAndCustomer(invoiceNumber, customerId)
                // Same response whether it does not exist or belongs to someone else.
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Invoice not found"));
        return renderPdf(invoice);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long invoiceId) {
        return toResponse(invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Invoice not found")));
    }

    @Transactional(readOnly = true)
    public com.velora.api.common.dto.PageResponse<InvoiceResponse> list(
            org.springframework.data.domain.Pageable pageable) {
        return com.velora.api.common.dto.PageResponse.from(
                invoiceRepository.findAllByOrderByIssuedAtDesc(pageable), this::toResponse);
    }

    /** Delivered orders with no invoice. Should always be empty. */
    @Transactional(readOnly = true)
    public List<Long> findUninvoicedDeliveredOrders() {
        return invoiceRepository.findDeliveredOrderIdsWithoutInvoice();
    }

    /**
     * The number for {@code GET /me/invoices/{invoiceNumber}/pdf}, or {@code null} if
     * the order has not reached DELIVERED yet — invoices are issued on delivery, not
     * on confirmation, so this is legitimately absent for most open orders.
     */
    @Transactional(readOnly = true)
    public String findInvoiceNumberForOrder(Long orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .map(Invoice::getInvoiceNumber)
                .orElse(null);
    }

    // ------------------------------------------------------------------ internal

private byte[] renderPdf(Invoice invoice) {
        CustomerOrder order = orderRepository.findWithItemsById(invoice.getOrder().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        InvoiceView view = buildView(invoice, order);
        String html = templateRenderer.render("invoice", "view", view);


        return pdfRenderer.render(html);
    }

    private void generateAndStorePdf(Invoice invoice, CustomerOrder order,
                                     StoreProfile seller) {
        try {
            InvoiceView view = buildView(invoice, order);
            String html = templateRenderer.render("invoice", "view", view);
            byte[] pdf = pdfRenderer.render(html);

            String key = storageService.storeBytes(pdf,
                    "invoices", invoice.getInvoiceNumber() + ".pdf", "application/pdf");
            invoice.setPdfKey(key);
            invoiceRepository.save(invoice);

        } catch (Exception ex) {
            // The invoice record is what matters legally; the PDF is a rendering of
            // it and can be produced again on demand. Failing the delivery over a
            // font problem would be the wrong trade.
            log.error("Could not store the PDF for invoice {}. The invoice itself is "
                    + "recorded and the PDF can be regenerated.",
                    invoice.getInvoiceNumber(), ex);
        }
    }

    /**
     * Builds the template model.
     *
     * <p>Line values come from the ORDER ITEM snapshot, never from the catalog — the
     * invoice must show what was charged, not today's price.
     */
    private InvoiceView buildView(Invoice invoice, CustomerOrder order) {
        List<InvoiceView.Line> lines = new ArrayList<>();

        for (OrderItem item : order.getItems()) {
            BigDecimal lineGross = item.getLineTotalGross()
                    .subtract(item.getAllocatedCartDiscount())
                    .subtract(item.getLineDiscount());
            BigDecimal lineTax = MoneyUtils.taxFromGross(lineGross, item.getTaxRate());
            BigDecimal lineNet = MoneyUtils.round(lineGross.subtract(lineTax));

            lines.add(new InvoiceView.Line(
                    item.getSku(),
                    item.getProductNameAr(),
                    item.getVariantSummary(),
                    item.getQuantity(),
                    item.getUnitPriceGross(),
                    item.getAllocatedCartDiscount().add(item.getLineDiscount()),
                    lineNet,
                    item.getTaxRate().multiply(BigDecimal.valueOf(100)),
                    lineTax,
                    MoneyUtils.round(lineGross)));
        }

        return new InvoiceView(
                invoice.getInvoiceNumber(),
                invoice.getIssuedAt().toLocalDate().format(DATE),
                order.getOrderNumber(),
                order.getPlacedAt().toLocalDate().format(DATE),
                invoice.isCancelled(),

                invoice.getSellerName(),
                invoice.getSellerAddress(),
                PhoneNormalizer.toLocalFormat(invoice.getSellerPhone()),
                invoice.getSellerEmail(),
                invoice.getSellerTaxNumber(),
                invoice.getSellerCommercialRegister(),
                storeProfileService.require().getInvoiceFooterNote(),

                invoice.getBuyerName(),
                PhoneNormalizer.toLocalFormat(invoice.getBuyerPhone()),
                invoice.getBuyerAddress(),

                lines,
                invoice.getSubtotalGross(),
                invoice.getDiscountTotal(),
                invoice.getShippingCost(),
                invoice.getCodFee(),
                invoice.getAssemblyTotal(),
                invoice.getNetTotal(),
                invoice.getTaxTotal(),
                invoice.getGrandTotal(),
                arabicPaymentMethod(invoice.getPaymentMethod()),
                invoice.getCurrency());
    }

    private String arabicPaymentMethod(String method) {
        return switch (method) {
            case "COD" -> "الدفع عند الاستلام";
            case "CARD" -> "بطاقة ائتمان";
            case "WALLET" -> "محفظة إلكترونية";
            case "FAWRY" -> "فوري";
            default -> method;
        };
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getStatus().name(),
                invoice.getOrder().getId(),
                invoice.getOrder().getOrderNumber(),
                invoice.getBuyerName(),
                PhoneNormalizer.toLocalFormat(invoice.getBuyerPhone()),
                invoice.getGrandTotal(),
                invoice.getTaxTotal(),
                invoice.getNetTotal(),
                invoice.getCurrency(),
                invoice.getPdfKey() == null ? null : storageService.urlFor(invoice.getPdfKey()),
                invoice.getIssuedAt(),
                invoice.getCancelReason());
    }
}
