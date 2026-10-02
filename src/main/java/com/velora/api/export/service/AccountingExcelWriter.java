package com.velora.api.export.service;

import com.velora.api.common.util.MoneyUtils;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.order.domain.CustomerOrder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * The accounting archive: one row per order, with a totals row.
 *
 * <p>Built for a person to read and an accountant to reconcile, so the numbers are
 * real numbers rather than strings — the recipient can sum a column, sort by
 * governorate and pivot without retyping anything.
 *
 * <p>The sheet is set right-to-left, which flips column order and freezes the pane
 * from the right. Without it an Arabic sheet reads backwards.
 */
@Service
public class AccountingExcelWriter {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String[] HEADERS = {
            "رقم الطلب", "التاريخ", "العميل", "الموبايل", "المحافظة", "المنطقة",
            "عدد الأصناف", "الكمية", "الإجمالي الفرعي", "الخصم", "الشحن",
            "التركيب", "رسوم الدفع عند الاستلام", "الإجمالي", "منه ضريبة", "الصافي", "طريقة الدفع", "حالة الدفع", "حالة الطلب"
    };

    private static final int[] WIDTHS = {
            18, 17, 20, 14, 14, 18, 9, 8, 14, 11, 10, 11, 16, 14, 12, 14, 12, 13, 16
    };

    public byte[] write(List<CustomerOrder> orders, String title) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("الطلبات");
            // Arabic sheets read from the right. Without this the columns run backwards.
            sheet.setRightToLeft(true);

            Styles styles = new Styles(workbook);

            int rowIndex = 0;
            rowIndex = writeTitle(sheet, styles, title, rowIndex);
            rowIndex = writeHeader(sheet, styles, rowIndex);

            Totals totals = new Totals();
            for (CustomerOrder order : orders) {
                writeOrder(sheet, styles, order, rowIndex++);
                totals.add(order);
            }

            writeTotals(sheet, styles, totals, orders.size(), rowIndex);

            for (int i = 0; i < WIDTHS.length; i++) {
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            // Keep the header visible while scrolling a long archive.
            sheet.createFreezePane(0, 3);

            workbook.write(out);
            return out.toByteArray();

        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the Excel export", ex);
        }
    }

    // ------------------------------------------------------------------ sections

    private int writeTitle(Sheet sheet, Styles styles, String title, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(28);
        var cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, HEADERS.length - 1));
        return rowIndex + 2;
    }

    private int writeHeader(Sheet sheet, Styles styles, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(22);
        for (int i = 0; i < HEADERS.length; i++) {
            var cell = row.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(styles.header);
        }
        return rowIndex + 1;
    }

    private void writeOrder(Sheet sheet, Styles styles, CustomerOrder order, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        int col = 0;

        text(row, col++, order.getOrderNumber(), styles.text);
        text(row, col++, order.getPlacedAt().format(DATE_TIME), styles.text);
        text(row, col++, order.getContactName(), styles.text);
        text(row, col++, PhoneNormalizer.toLocalFormat(order.getContactPhone()), styles.text);
        text(row, col++, order.getShipGovernorateName(), styles.text);
        text(row, col++, order.getShipArea(), styles.text);

        number(row, col++, order.getItems().size(), styles.integer);
        number(row, col++, order.totalQuantity(), styles.integer);

        money(row, col++, order.getSubtotalGross(), styles.money);
        money(row, col++, order.getDiscountTotal(), styles.money);
        money(row, col++, order.getShippingCost(), styles.money);
        money(row, col++, order.getAssemblyTotal(), styles.money);
        money(row, col++, order.getCodFee(), styles.money);
        money(row, col++, order.getGrandTotal(), styles.moneyBold);
        money(row, col++, order.getTaxTotal(), styles.money);
        money(row, col++, order.getNetTotal(), styles.money);

        text(row, col++, arabicPaymentMethod(order.getPaymentMethod().name()), styles.text);
        text(row, col++, arabicPaymentStatus(order.getPaymentStatus().name()), styles.text);
        text(row, col, arabicFulfillment(order.getFulfillmentStatus().name()), styles.text);
    }

    private void writeTotals(Sheet sheet, Styles styles, Totals totals,
                             int orderCount, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(22);

        var label = row.createCell(0);
        label.setCellValue("الإجمالي (%d طلب)".formatted(orderCount));
        label.setCellStyle(styles.totalLabel);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 5));

        for (int i = 1; i <= 5; i++) {
            row.createCell(i).setCellStyle(styles.totalLabel);
        }

        number(row, 6, totals.itemCount, styles.totalInteger);
        number(row, 7, totals.quantity, styles.totalInteger);
        money(row, 8, totals.subtotal, styles.totalMoney);
        money(row, 9, totals.discount, styles.totalMoney);
        money(row, 10, totals.shipping, styles.totalMoney);
        money(row, 11, totals.assembly, styles.totalMoney);
        money(row, 12, totals.codFee, styles.totalMoney);
        money(row, 13, totals.grandTotal, styles.totalMoney);
        money(row, 14, totals.tax, styles.totalMoney);
        money(row, 15, totals.net, styles.totalMoney);

        for (int i = 16; i < HEADERS.length; i++) {
            row.createCell(i).setCellStyle(styles.totalLabel);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void text(Row row, int col, String value, CellStyle style) {
        var cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void number(Row row, int col, int value, CellStyle style) {
        var cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /**
     * Written as a NUMBER, not a formatted string. The accountant needs to sum the
     * column; a string that looks like "2,400.00 ج.م" cannot be summed.
     */
    private void money(Row row, int col, BigDecimal value, CellStyle style) {
        var cell = row.createCell(col);
        cell.setCellValue(MoneyUtils.nullSafe(value).doubleValue());
        cell.setCellStyle(style);
    }

    private String arabicFulfillment(String status) {
        return switch (status) {
            case "PENDING" -> "بانتظار التأكيد";
            case "CONFIRMED" -> "مؤكد";
            case "PROCESSING" -> "قيد التجهيز";
            case "SHIPPED" -> "تم الشحن";
            case "OUT_FOR_DELIVERY" -> "خرج للتوصيل";
            case "DELIVERED" -> "تم التسليم";
            case "DELIVERY_FAILED" -> "فشل التوصيل";
            case "REFUSED_ON_DELIVERY" -> "رفض الاستلام";
            case "RETURNED_TO_SELLER" -> "مرتجع للبائع";
            case "CANCELLED" -> "ملغي";
            case "RETURNED" -> "مرتجع";
            case "PARTIALLY_RETURNED" -> "مرتجع جزئياً";
            default -> status;
        };
    }

    private String arabicPaymentStatus(String status) {
        return switch (status) {
            case "PENDING" -> "لم يُحصّل";
            case "PAID" -> "محصّل";
            case "PARTIALLY_REFUNDED" -> "مسترد جزئياً";
            case "REFUNDED" -> "مسترد";
            case "FAILED" -> "فشل";
            case "EXPIRED" -> "منتهي";
            case "AUTHORIZED" -> "محجوز";
            default -> status;
        };
    }

    private String arabicPaymentMethod(String method) {
        return switch (method) {
            case "COD" -> "عند الاستلام";
            case "CARD" -> "بطاقة";
            case "WALLET" -> "محفظة";
            case "FAWRY" -> "فوري";
            default -> method;
        };
    }

    /** Running totals for the summary row. */
    private static final class Totals {
        private BigDecimal subtotal = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal shipping = BigDecimal.ZERO;
        private BigDecimal assembly = BigDecimal.ZERO;
        private BigDecimal codFee = BigDecimal.ZERO;
        private BigDecimal grandTotal = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;
        private BigDecimal net = BigDecimal.ZERO;
        private int itemCount;
        private int quantity;

        void add(CustomerOrder order) {
            subtotal = subtotal.add(MoneyUtils.nullSafe(order.getSubtotalGross()));
            discount = discount.add(MoneyUtils.nullSafe(order.getDiscountTotal()));
            shipping = shipping.add(MoneyUtils.nullSafe(order.getShippingCost()));
            assembly = assembly.add(MoneyUtils.nullSafe(order.getAssemblyTotal()));
            codFee = codFee.add(MoneyUtils.nullSafe(order.getCodFee()));
            grandTotal = grandTotal.add(MoneyUtils.nullSafe(order.getGrandTotal()));
            tax = tax.add(MoneyUtils.nullSafe(order.getTaxTotal()));
            net = net.add(MoneyUtils.nullSafe(order.getNetTotal()));
            itemCount += order.getItems().size();
            quantity += order.totalQuantity();
        }
    }

    /** Cell styles, created once per workbook — POI limits how many can exist. */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle integer;
        private final CellStyle money;
        private final CellStyle moneyBold;
        private final CellStyle totalLabel;
        private final CellStyle totalMoney;
        private final CellStyle totalInteger;

        Styles(Workbook workbook) {
            short moneyFormat = workbook.createDataFormat().getFormat("#,##0.00");

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 15);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(header);

            text = workbook.createCellStyle();
            text.setAlignment(HorizontalAlignment.RIGHT);
            border(text);

            integer = workbook.createCellStyle();
            integer.setAlignment(HorizontalAlignment.CENTER);
            border(integer);

            money = workbook.createCellStyle();
            money.setDataFormat(moneyFormat);
            money.setAlignment(HorizontalAlignment.LEFT);
            border(money);

            moneyBold = workbook.createCellStyle();
            moneyBold.setDataFormat(moneyFormat);
            moneyBold.setFont(boldFont);
            moneyBold.setAlignment(HorizontalAlignment.LEFT);
            border(moneyBold);

            totalLabel = workbook.createCellStyle();
            totalLabel.setFont(boldFont);
            totalLabel.setAlignment(HorizontalAlignment.CENTER);
            totalLabel.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            totalLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(totalLabel);

            totalMoney = workbook.createCellStyle();
            totalMoney.setDataFormat(moneyFormat);
            totalMoney.setFont(boldFont);
            totalMoney.setAlignment(HorizontalAlignment.LEFT);
            totalMoney.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            totalMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(totalMoney);

            totalInteger = workbook.createCellStyle();
            totalInteger.setFont(boldFont);
            totalInteger.setAlignment(HorizontalAlignment.CENTER);
            totalInteger.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            totalInteger.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(totalInteger);
        }

        private void border(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
